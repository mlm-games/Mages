package org.mlm.mages.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mlm.mages.MatrixService

class DeclineInviteWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val service: MatrixService by inject()

    override suspend fun doWork(): Result {
        val roomId = inputData.getString(KEY_ROOM_ID) ?: return Result.failure()

        runCatching { service.initFromDisk() }
        val port = service.portOrNull
        if (port == null || !service.isLoggedIn()) return Result.success()

        return if (runCatching { port.leaveRoom(roomId) }.isSuccess) {
            Result.success()
        } else {
            Result.retry()
        }
    }

    companion object {
        const val KEY_ROOM_ID = "roomId"
        fun uniqueName(roomId: String) = "decline_invite:$roomId"
    }
}
