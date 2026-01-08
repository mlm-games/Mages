package org.mlm.mages.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mlm.mages.MatrixService
import org.mlm.mages.platform.SettingsProvider

class NotificationEnrichWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val service: MatrixService by inject()

    override suspend fun doWork(): Result {
        val roomId = inputData.getString(KEY_ROOM_ID) ?: return Result.failure()
        val eventId = inputData.getString(KEY_EVENT_ID) ?: return Result.failure()

        val settingsRepo = SettingsProvider.get(applicationContext)
        val settings = settingsRepo.flow.first()
        if (!settings.notificationsEnabled) return Result.success()

        runCatching { service.initFromDisk() }

        val port = service.portOrNull
        if (port == null || !service.isLoggedIn()) {
            return Result.success()
        }

        val rendered = withTimeoutOrNull(7_000) {
            port.fetchNotification(roomId, eventId)
        } ?: return Result.retry()

        if (!rendered.isNoisy) return Result.success()
        if (settings.mentionsOnly && !rendered.hasMention) return Result.success()

        val title = if (rendered.sender == rendered.roomName) { // later use rendered.isDm ||
            rendered.sender
        } else {
            "${rendered.sender} • ${rendered.roomName}"
        }

        AndroidNotificationHelper.showSingleEvent(
            applicationContext,
            AndroidNotificationHelper.NotificationText(
                title,
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