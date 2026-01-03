package org.mlm.mages.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.mlm.mages.matrix.MatrixProvider
import org.mlm.mages.platform.SettingsProvider

class NotificationEnrichWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val roomId = inputData.getString(KEY_ROOM_ID) ?: return Result.failure()
        val eventId = inputData.getString(KEY_EVENT_ID) ?: return Result.failure()

        val settingsRepo = SettingsProvider.get(applicationContext)
        val settings = settingsRepo.flow.first()
        if (!settings.notificationsEnabled) return Result.success()

        val svc = MatrixProvider.getReady(applicationContext) ?: return Result.retry()

        val rendered = withTimeoutOrNull(7_000) {
            svc.port.fetchNotification(roomId, eventId)
        } ?: return Result.retry()

        if (!rendered.isNoisy) return Result.success()
        if (settings.mentionsOnly and !rendered.hasMention) return Result.success()

        AndroidNotificationHelper.showSingleEvent(
            applicationContext,
            AndroidNotificationHelper.NotificationText(
                title = "${rendered.sender} • ${rendered.roomName}",
                body = rendered.body
            ),
            roomId = roomId,
            eventId = eventId
        )

        return Result.success()
    }

    companion object {
        const val KEY_ROOM_ID = "roomId"
        const val KEY_EVENT_ID = "eventId"
    }
}