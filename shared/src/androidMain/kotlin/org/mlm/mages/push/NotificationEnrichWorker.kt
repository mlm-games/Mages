package org.mlm.mages.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.NotificationKind
import org.mlm.mages.platform.SettingsProvider
import org.mlm.mages.shared.R

private fun parseNotifiedRooms(json: String): Set<String> {
    if (json.isBlank()) return emptySet()
    return runCatching { Json.decodeFromString<Set<String>>(json) }.getOrElse { emptySet() }
}

class NotificationEnrichWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val service: MatrixService by inject()

    override suspend fun doWork(): Result {
        AppNotificationChannels.ensureCreated(applicationContext)

        val roomId = inputData.getString(KEY_ROOM_ID) ?: return Result.failure()
        val eventId = inputData.getString(KEY_EVENT_ID) ?: return Result.failure()

        // Placeholder + message notification share the same ID (to update after enrich).
        val notifId = (roomId + eventId).hashCode()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val settingsRepo = SettingsProvider.get(applicationContext)
        val settings = settingsRepo.flow.first()

        // If user disabled notifications, remove placeholder immediately.
        if (!settings.notificationsEnabled) {
            nm.cancel(notifId)
            return Result.success()
        }

        runCatching { service.initFromDisk() }

        val port = service.portOrNull
        if (port == null || !service.isLoggedIn()) {
            // Logged out / no session: remove placeholder to avoid stuck junk.
            nm.cancel(notifId)
            return Result.success()
        }

        // Distinguish timeout from "null result".
        data class Fetch(val timedOut: Boolean, val rendered: org.mlm.mages.matrix.RenderedNotification?)

        val fetch = withTimeoutOrNull(7_000) {
            // fetchNotification returns RenderedNotification? (null is a normal outcome)
            val r = runCatching { port.fetchNotification(roomId, eventId) }.getOrNull()
            Fetch(timedOut = false, rendered = r)
        } ?: Fetch(timedOut = true, rendered = null)

        if (fetch.timedOut) {
            // Retry a couple times, then stop (keep placeholder or cancel—choose one).
            // I recommend cancelling after a few attempts to avoid WorkManager spam + stale notifs.
            return if (runAttemptCount < 3) Result.retry() else {
                nm.cancel(notifId)
                Result.success()
            }
        }

        val rendered = fetch.rendered
        if (rendered == null) {
            // Event filtered out / not found / cannot be rendered: cancel placeholder and stop.
            nm.cancel(notifId)
            return Result.success()
        }

        when (rendered.kind) {
            NotificationKind.StateEvent -> {
                // Don't show state events; but cancel placeholder.
                nm.cancel(notifId)
                return Result.success()
            }

            NotificationKind.Invite -> {
                nm.cancel(notifId)

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
                    nm.cancel(notifId)
                    return Result.success()
                }

                // Expired? cancel placeholder (and don’t show).
                val expiresAt = rendered.expiresAtMs
                if (expiresAt != null && System.currentTimeMillis() > expiresAt) {
                    nm.cancel(notifId)
                    return Result.success()
                }

                // Replace placeholder with a call notification.
                nm.cancel(notifId)

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
                    callerAvatarPath = callerAvatarPath
                )
                return Result.success()
            }

            NotificationKind.Message -> {
                // If the SDK says "not noisy" or local mentions-only filter suppresses, cancel placeholder.
                if (!rendered.isNoisy) {
                    nm.cancel(notifId)
                    return Result.success()
                }
                if (settings.mentionsOnly && !rendered.hasMention) {
                    nm.cancel(notifId)
                    return Result.success()
                }

                val title = if (rendered.isDm || rendered.sender == rendered.roomName) {
                    rendered.sender
                } else {
                    "${rendered.sender} • ${rendered.roomName}"
                }

                val playSound = if (settings.notificationSound) {
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

                // No need to cancel here; showConversationNotification uses the same notifId and will replace.
                val notificationId = (roomId).hashCode()
                val bubbleActivityClass = try {
                    Class.forName("org.mlm.mages.activities.BubbleConversationActivity")
                } catch (_: ClassNotFoundException) {
                    null // not possible
                }

                val isDm = rendered.isDm

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

                Notifier.showConversationNotification(
                    context = applicationContext,
                    roomId = roomId,
                    roomName = rendered.roomName,
                    senderName = rendered.sender,
                    senderUserId = rendered.senderUserId,
                    messageBody = rendered.body,
                    eventId = eventId,
                    timestamp = System.currentTimeMillis(),
                    notificationId = notificationId,
                    bubbleActivityClass = bubbleActivityClass,
                    fullOpenIntent = buildFullOpenIntent(applicationContext, roomId),
                    senderAvatar = senderAvatar,
                    roomAvatar = roomAvatar,
                    isDm = isDm,
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

private fun buildFullOpenIntent(context: Context, roomId: String): PendingIntent {
    val uri = Uri.Builder()
        .scheme("mages")
        .authority("room")
        .appendQueryParameter("id", roomId)
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
