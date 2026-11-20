package org.mlm.mages.push

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import org.mlm.mages.platform.Notifier as PlatformNotifier
import kotlin.time.Clock

private const val LIVE_STATE_TIMEOUT_MS = 4_000L
private const val TAG = "NotifReconcile"

class NotificationReconcileWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val service: MatrixService by inject()
    private var foregroundServiceHeld = false

    override suspend fun doWork(): Result {
        foregroundServiceHeld = FetchPushForegroundServiceManager.acquire(applicationContext)
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
        val ctx = applicationContext
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return Result.success()

        val targetRoomId = inputData.getString(KEY_ROOM_ID)
        val unread = inputData.getInt(KEY_UNREAD, -1)
        val mentions = inputData.getInt(KEY_MENTIONS, -1)
        val hasCounts = inputData.getBoolean(KEY_HAS_COUNTS, false)
        if (hasCounts && unread <= 0 && mentions <= 0) {
            if (targetRoomId != null) {
                if (!PlatformNotifier.isRoomVisible(targetRoomId)) {
                    AndroidNotificationHelper.cancelRoomNotification(ctx, targetRoomId)
                }
            } else {
                for (notif in mgr.activeNotifications) {
                    val roomId = notif.notification.extras
                        .getString(EXTRA_MATRIX_ROOM_ID)
                        ?: continue
                    if (PlatformNotifier.isRoomVisible(roomId)) continue
                    AndroidNotificationHelper.cancelRoomNotification(ctx, roomId)
                }
            }
            return Result.success()
        }

        runCatching { service.initFromDisk() }

        val port = service.portOrNull ?: return Result.success()

        var raisedForeground = false
        try {
            val active = mgr.activeNotifications ?: return Result.success()
            val conversation = active.filter { entry ->
                val roomId = entry.notification.extras.getString(EXTRA_MATRIX_ROOM_ID)
                    ?: return@filter false
                if (targetRoomId != null && roomId != targetRoomId) return@filter false
                val channel = entry.notification.channelId
                channel == AppNotificationChannels.CHANNEL_MESSAGES ||
                    channel == AppNotificationChannels.CHANNEL_MESSAGES_SILENT
            }

            val latestTsByRoom = mutableMapOf<String, Long>()
            val notifiedRooms = linkedSetOf<String>()
            for (entry in conversation) {
                val roomId = entry.notification.extras.getString(EXTRA_MATRIX_ROOM_ID) ?: continue
                notifiedRooms += roomId
                val ts = entry.notification.extras.getLong(EXTRA_MATRIX_TS, 0L)
                if (ts > 0L) latestTsByRoom[roomId] = maxOf(latestTsByRoom[roomId] ?: 0L, ts)
            }

            val wasForeground = port.isForegrounded()
            runCatching { port.enterForeground() }
            raisedForeground = true

            if (!wasForeground && notifiedRooms.isNotEmpty()) {
                runCatching { port.subscribeToVisibleRooms(notifiedRooms.toList()) }
            }
            runCatching { service.startSupervisedSync() }

            val targets = notifiedRooms
                .filterNot { PlatformNotifier.isRoomVisible(it) }
                .toList()
            val readRooms = NotificationReadState.awaitCleared(port, targets, latestTsByRoom)
            Log.i(TAG, "reconcile notified=$notifiedRooms read=$readRooms")
            for (roomId in readRooms) {
                AndroidNotificationHelper.cancelRoomNotification(ctx, roomId)
            }

            for (notif in active) {
                val roomId = notif.notification.extras
                    .getString(EXTRA_MATRIX_ROOM_ID)
                    ?: continue
                if (targetRoomId != null && roomId != targetRoomId) continue

                val threadId = notif.notification.extras.getString(EXTRA_MATRIX_THREAD_ID) ?: continue
                if (threadId.isBlank()) continue
                if (PlatformNotifier.isThreadVisible(roomId, threadId)) continue
                if (ThreadUnreadGate.isRead(service, roomId, threadId)) {
                    AndroidNotificationHelper.cancelThreadNotification(ctx, roomId, threadId)
                }
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
                        AndroidNotificationHelper.dismissCallUi(ctx, roomId, eventId, silent = false)
                    }
                    if (targetRoomId != null) break
                }
            }

            return Result.success()
        } finally {
            if (raisedForeground) {
                runCatching { service.portOrNull?.enterBackground() }
            }
        }
    }

    companion object {
        const val EXTRA_MATRIX_ROOM_ID = "org.mlm.mages.notification.ROOM_ID"
        const val EXTRA_MATRIX_EVENT_ID = "org.mlm.mages.notification.EVENT_ID"
        const val EXTRA_MATRIX_THREAD_ID = "org.mlm.mages.notification.THREAD_ID"
        const val EXTRA_MATRIX_ACCOUNT_ID = "org.mlm.mages.notification.ACCOUNT_ID"
        const val EXTRA_MATRIX_TS = "org.mlm.mages.notification.TS"

        const val KEY_ROOM_ID = "reconcileRoomId"
        const val KEY_UNREAD = "reconcileUnread"
        const val KEY_MENTIONS = "reconcileMentions"
        const val KEY_HAS_COUNTS = "reconcileHasCounts"
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
