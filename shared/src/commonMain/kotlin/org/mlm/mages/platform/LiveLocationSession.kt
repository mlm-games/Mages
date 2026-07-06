package org.mlm.mages.platform

class LiveLocationSession {
    private var currentRoomId: String? = null

    suspend fun startSharing(roomId: String, durationMinutes: Int): Result<Unit> {
        val result = LiveLocationSharingCoordinator.startShare(roomId, durationMinutes)
        if (result.isSuccess) {
            currentRoomId = roomId
        }
        return result
    }

    suspend fun stopSharing(roomId: String): Result<Unit> {
        val result = LiveLocationSharingCoordinator.stopShare(roomId)
        if (result.isSuccess) {
            currentRoomId = null
        }
        return result
    }
}
