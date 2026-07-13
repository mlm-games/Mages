package org.mlm.mages.push

import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mlm.mages.MatrixService

class NotificationReconcileWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val service: MatrixService by inject()

    override suspend fun doWork(): Result {
        runCatching { service.initFromDisk() }

        val port = service.portOrNull ?: return Result.success()

        val ctx = applicationContext
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return Result.success()

        val active = mgr.activeNotifications ?: return Result.success()

        for (notif in active) {
            val roomId = notif.notification.extras
                .getString(EXTRA_MATRIX_ROOM_ID)
                ?: continue

            val stats = runCatching { port.roomUnreadStats(roomId) }.getOrNull()
            if (stats == null) {
                // Room no longer available → clean up.
                AndroidNotificationHelper.cancelRoomNotification(ctx, roomId)
                continue
            }

            if (stats.notifications == 0L && stats.mentions == 0L) {
                AndroidNotificationHelper.cancelRoomNotification(ctx, roomId)
            }
        }

        return Result.success()
    }

    companion object {
        const val EXTRA_MATRIX_ROOM_ID = "org.mlm.mages.notification.ROOM_ID"
        const val EXTRA_MATRIX_EVENT_ID = "org.mlm.mages.notification.EVENT_ID"
        const val EXTRA_MATRIX_ACCOUNT_ID = "org.mlm.mages.notification.ACCOUNT_ID"
    }
}
