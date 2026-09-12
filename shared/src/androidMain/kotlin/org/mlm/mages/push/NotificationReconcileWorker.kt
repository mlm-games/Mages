package org.mlm.mages.push

import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mlm.mages.MatrixService
import org.mlm.mages.calls.CALL_END_GRACE_MS
import org.mlm.mages.calls.isExpired
import org.mlm.mages.calls.isRingingCall
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.RoomCallState
import org.mlm.mages.matrix.MatrixPort.RoomCallStateObserver
import kotlin.time.Clock

private const val LIVE_STATE_TIMEOUT_MS = 4_000L

class NotificationReconcileWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val service: MatrixService by inject()

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return Result.success()

        val targetRoomId = inputData.getString(KEY_ROOM_ID)
        val unread = inputData.getInt(KEY_UNREAD, -1)
        val mentions = inputData.getInt(KEY_MENTIONS, -1)
        val hasCounts = inputData.getBoolean(KEY_HAS_COUNTS, false)
        if (hasCounts && unread <= 0 && mentions <= 0) {
            if (targetRoomId != null) {
                AndroidNotificationHelper.cancelRoomNotification(ctx, targetRoomId, force = true)
            } else {
                for (notif in mgr.activeNotifications) {
                    val roomId = notif.notification.extras
                        .getString(EXTRA_MATRIX_ROOM_ID)
                        ?: continue
                    AndroidNotificationHelper.cancelRoomNotification(ctx, roomId, force = true)
                }
            }
            return Result.success()
        }

        runCatching { service.initFromDisk() }

        val port = service.portOrNull ?: return Result.success()

        runCatching { port.enterForeground() }
        runCatching { service.startSupervisedSync() }
        delay(SYNC_SETTLE_MS)

        val active = mgr.activeNotifications ?: return Result.success()

        for (notif in active) {
            val roomId = notif.notification.extras
                .getString(EXTRA_MATRIX_ROOM_ID)
                ?: continue
            if (targetRoomId != null && roomId != targetRoomId) continue

            val stats = runCatching { port.roomUnreadStats(roomId) }.getOrNull()
            if (stats == null) {
                AndroidNotificationHelper.cancelRoomNotification(ctx, roomId, force = true)
                continue
            }

            if (stats.notifications == 0L && stats.mentions == 0L) {
                AndroidNotificationHelper.cancelRoomNotification(ctx, roomId, force = true)
            }

            if (targetRoomId != null) break
        }

        runCatching {
            val callNotifs = (mgr.activeNotifications ?: emptyArray())
                .filter { it.notification.channelId == AppNotificationChannels.CHANNEL_CALLS }
            for (entry in callNotifs) {
                val roomId = entry.notification.extras.getString(EXTRA_MATRIX_ROOM_ID)
                    ?: continue
                if (targetRoomId != null && roomId != targetRoomId) continue
                val eventId = entry.notification.extras.getString(EXTRA_MATRIX_EVENT_ID)
                    ?: continue
                val rendered = runCatching { port.fetchNotification(roomId, eventId) }.getOrNull()
                val stale = when {
                    rendered == null -> true
                    !rendered.kind.isRingingCall() -> true
                    rendered.isExpired() -> true
                    liveCallEnded(port, roomId, rendered.tsMs) -> true
                    else -> false
                }
                if (stale) {
                    AndroidNotificationHelper.cancelCallNotification(ctx, roomId)
                }
                if (targetRoomId != null) break
            }
        }

        return Result.success()
    }

    companion object {
        const val EXTRA_MATRIX_ROOM_ID = "org.mlm.mages.notification.ROOM_ID"
        const val EXTRA_MATRIX_EVENT_ID = "org.mlm.mages.notification.EVENT_ID"
        const val EXTRA_MATRIX_ACCOUNT_ID = "org.mlm.mages.notification.ACCOUNT_ID"

        const val KEY_ROOM_ID = "reconcileRoomId"
        const val KEY_UNREAD = "reconcileUnread"
        const val KEY_MENTIONS = "reconcileMentions"
        const val KEY_HAS_COUNTS = "reconcileHasCounts"

        private const val SYNC_SETTLE_MS = 2_500L
    }
}

private suspend fun liveCallEnded(
    port: MatrixPort,
    roomId: String,
    inviteTsMs: Long,
): Boolean {
    val snapshot: RoomCallState? = try {
        withTimeoutOrNull(LIVE_STATE_TIMEOUT_MS) {
            val first = CompletableDeferred<RoomCallState>()
            val token = port.observeRoomCallState(roomId, object : RoomCallStateObserver {
                override fun onUpdate(state: RoomCallState) {
                    first.complete(state)
                }
            })
            try {
                first.await()
            } finally {
                runCatching { port.unobserveRoomCallState(token) }
            }
        }
    } catch (e: Exception) {
        null
    }
    if (snapshot == null) return false
    return !snapshot.hasActiveCall &&
        Clock.System.now().toEpochMilliseconds() - inviteTsMs > CALL_END_GRACE_MS
}
