package org.mlm.mages.push

import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.NotificationKind
import org.mlm.mages.platform.SettingsProvider

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
                // Don’t show state events; but cancel placeholder.
                nm.cancel(notifId)
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

                AndroidNotificationHelper.showIncomingCall(
                    applicationContext,
                    roomId = roomId,
                    eventId = eventId,
                    callerName = rendered.sender,
                    roomName = rendered.roomName
                )
                return Result.success()
            }

            NotificationKind.Message -> {
                // If the SDK says “not noisy” or local mentions-only filter suppresses, cancel placeholder.
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

                // No need to cancel here; showSingleEvent uses the same notifId and will replace.
                AndroidNotificationHelper.showSingleEvent(
                    applicationContext,
                    AndroidNotificationHelper.NotificationText(
                        title = title,
                        body = rendered.body
                    ),
                    roomId = roomId,
                    eventId = eventId
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