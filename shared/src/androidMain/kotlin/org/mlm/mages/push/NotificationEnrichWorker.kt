package org.mlm.mages.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mlm.mages.MatrixService
import org.mlm.mages.calls.IncomingCall
import org.mlm.mages.calls.IncomingCallTracker
import org.mlm.mages.calls.isExpired
import org.mlm.mages.calls.ringing
import org.mlm.mages.matrix.NotificationKind
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.matrix.RenderedNotification
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.platform.SettingsProvider
import org.mlm.mages.shared.R
import java.util.Calendar

private fun parseNotifiedRooms(json: String): Set<String> {
    if (json.isBlank()) return emptySet()
    return runCatching { Json.decodeFromString<Set<String>>(json) }.getOrElse { emptySet() }
}

class NotificationEnrichWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val service: MatrixService by inject()
    private val incomingCalls: IncomingCallTracker by inject()
    private var foregroundServiceHeld = false

    override suspend fun doWork(): Result {
        foregroundServiceHeld = FetchPushForegroundServiceManager.acquire(applicationContext)
        AppNotificationChannels.ensureCreated(applicationContext)
        return try {
            doWorkInternal()
        } finally {
            if (foregroundServiceHeld) {
                withContext(NonCancellable) {
                    FetchPushForegroundServiceManager.release(applicationContext)
                }
            }
        }
    }

    private suspend fun doWorkInternal(): Result {
        val roomId = inputData.getString(KEY_ROOM_ID) ?: return Result.failure()
        val eventId = inputData.getString(KEY_EVENT_ID) ?: return Result.failure()

        // One notification per room, holding every unread message in its MessagingStyle.
        // Cancelling it therefore drops the whole conversation's history, so a decision
        // to withhold *this* event must never cancel: it just skips the event.
        val notifId = (roomId).hashCode()

        val settingsRepo = SettingsProvider.get(applicationContext)
        val settings = settingsRepo.flow.first()

        val bubbleActivityClass = try {
            Class.forName("org.mlm.mages.activities.BubbleConversationActivity")
        } catch (_: ClassNotFoundException) {
            null // not possible
        }

        // Notifications off entirely, or no session to attribute them to: nothing
        // this room is showing is still wanted, so the room is cleared.
        if (!settings.notificationsEnabled) {
            AndroidNotificationHelper.cancelRoomNotification(applicationContext, roomId, force = true)
            return Result.success()
        }

        runCatching { service.initFromDisk() }

        val port = service.portOrNull
        if (port == null || !service.isLoggedIn()) {
            AndroidNotificationHelper.cancelRoomNotification(applicationContext, roomId, force = true)
            return Result.success()
        }

        // Distinguish timeout from "null result".
        data class Fetch(val timedOut: Boolean, val rendered: RenderedNotification?)

        val fetch = withTimeoutOrNull(7_000) {
            // fetchNotification returns RenderedNotification? (null is a normal outcome)
            val r = runCatching { port.fetchNotification(roomId, eventId) }.getOrNull()
            Fetch(timedOut = false, rendered = r)
        } ?: Fetch(timedOut = true, rendered = null)

        if (fetch.timedOut) {
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }

        val rendered = fetch.rendered
        if (rendered == null) {
            return Result.success()
        }

        // Mute and mentions-only suppress this event; they say nothing about the
        // messages already in the room's notification, so the room is left alone.
        val notifMode = runCatching { port.roomNotificationMode(roomId) }.getOrNull()
        if (notifMode == RoomNotificationMode.Mute) {
            return Result.success()
        }
        if (notifMode == RoomNotificationMode.MentionsAndKeywordsOnly && !rendered.hasMention) {
            return Result.success()
        }

        when (rendered.kind) {
            NotificationKind.StateEvent -> {
                return Result.success()
            }

            NotificationKind.Invite -> {
                // The invite is its own notification, so the room's is replaced rather than kept.
                AndroidNotificationHelper.cancelRoomNotification(applicationContext, roomId, force = true)

                if (settings.autoJoinInvites) {
                    runCatching {
                        port.acceptInvite(roomId)
                    }
                    return Result.success()
                }

                AndroidNotificationHelper.showInviteNotification(
                    applicationContext,
                    roomId = roomId,
                    eventId = eventId,
                    inviterName = rendered.sender,
                    roomName = rendered.roomName
                )
                return Result.success()
            }

            NotificationKind.CallRing,
            NotificationKind.CallInvite,
            NotificationKind.CallNotify -> {
                // Respect user call setting.
                if (!settings.callNotificationsEnabled) {
                    return Result.success()
                }
                if (rendered.isExpired()) {
                    return Result.success()
                }

                // The call takes over the room's notification for the duration of the ring.
                AndroidNotificationHelper.cancelRoomNotification(applicationContext, roomId, force = true)

                val callerAvatarPath = runCatching {
                    val members = port.listMembers(roomId)
                    val sender = members.find { it.userId == rendered.senderUserId }
                    sender?.avatarUrl?.let { avatarUrl ->
                        service.avatars.resolve(avatarUrl, px = 256, crop = true)
                    }
                }.getOrNull()

                AndroidNotificationHelper.showIncomingCall(
                    applicationContext,
                    roomId = roomId,
                    eventId = eventId,
                    callerName = rendered.sender,
                    roomName = rendered.roomName,
                    callerAvatarPath = callerAvatarPath,
                    callerUserId = rendered.senderUserId,
                    isDm = rendered.isDm,
                    expiresAtMs = rendered.expiresAtMs,
                )
                runCatching { incomingCalls.report(IncomingCall.ringing(rendered)) }
                return Result.success()
            }

            NotificationKind.Reaction,
            NotificationKind.Message -> {
                val inQuietHours = settings.quietHoursEnabled && isInQuietHours(settings)

                val wantsAlert = settings.notificationSound || settings.notificationVibrate
                val playSound = if (!inQuietHours && wantsAlert && rendered.isNoisy) {
                    if (settings.notifySoundOncePerRoom) {
                        val notifiedRooms = parseNotifiedRooms(settings.notifiedRoomsJson)
                        if (!notifiedRooms.contains(roomId)) {
                            val updated = notifiedRooms + roomId
                            settingsRepo.update { it.copy(notifiedRoomsJson = Json.encodeToString(updated)) }
                            true
                        } else {
                            false
                        }
                    } else {
                        true
                    }
                } else {
                    false
                }

                val senderAvatarUrl = runCatching {
                    port.getUserProfile(rendered.senderUserId)?.avatarUrl
                }.getOrNull()

                val roomAvatarUrl = runCatching {
                    port.roomProfile(roomId)?.avatarUrl
                }.getOrNull()

                val senderAvatar = NotificationAvatarHelper.resolve(
                    context = applicationContext,
                    service = service,
                    avatarUrl = senderAvatarUrl,
                    displayName = rendered.sender,
                    userId = rendered.senderUserId,
                    fallbackRes = R.drawable.ic_notif_status_bar,
                )
                val roomAvatar = NotificationAvatarHelper.resolve(
                    context = applicationContext,
                    service = service,
                    avatarUrl = roomAvatarUrl,
                    displayName = rendered.roomName,
                    userId = roomId,
                    fallbackRes = R.drawable.ic_notif_status_bar,
                )

                val presentation = NotificationPresentation.of(
                    notification = rendered,
                    showPreview = settings.notificationShowPreview,
                    redactedBody = applicationContext.getString(R.string.notif_new_message),
                    // MessagingStyle labels the line with whoever sent it.
                    senderShownByPlatform = true
                )
                val media = presentation.media
                    ?.takeIf { NotificationMediaPolicy.allowed(settings) }
                    ?.let { NotificationMediaPolicy.preview(port, it.attachment) }

                Notifier.showConversationNotification(
                    context = applicationContext,
                    roomId = roomId,
                    roomName = rendered.roomName,
                    senderName = rendered.sender,
                    senderUserId = rendered.senderUserId,
                    messageBody = presentation.body,
                    caption = presentation.bodyWithMedia,
                    eventId = eventId,
                    timestamp = rendered.tsMs,
                    notificationId = notifId,
                    bubbleActivityClass = bubbleActivityClass,
                    fullOpenIntent = buildFullOpenIntent(applicationContext, roomId, eventId),
                    senderAvatar = senderAvatar,
                    roomAvatar = roomAvatar,
                    isDm = rendered.isDm,
                    playSound = playSound,
                    mediaPath = media,
                )
                return Result.success()
            }
        }
    }

    companion object {
        const val KEY_ROOM_ID = "roomId"
        const val KEY_EVENT_ID = "eventId"
    }
}

private fun isInQuietHours(settings: AppSettings): Boolean {
    if (!settings.quietHoursEnabled) return false
    val now = Calendar.getInstance()
    val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 +
        now.get(Calendar.MINUTE)
    val start = settings.quietHoursStartMinutes
    val end = settings.quietHoursEndMinutes
    return if (start <= end) {
        minuteOfDay in start until end
    } else {
        minuteOfDay !in end..<start
    }
}

private fun buildFullOpenIntent(context: Context, roomId: String, eventId: String? = null): PendingIntent {
    val uri = Uri.Builder()
        .scheme("mages")
        .authority("room")
        .appendQueryParameter("id", roomId)
        .apply { eventId?.let { appendQueryParameter("event", it) } }
        .build()
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage(context.packageName)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(
        context, roomId.hashCode(),
        intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
