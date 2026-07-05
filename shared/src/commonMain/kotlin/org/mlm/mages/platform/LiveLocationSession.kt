package org.mlm.mages.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LiveLocationSession {
    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

    private var currentRoomId: String? = null

    suspend fun startSharing(roomId: String, durationMinutes: Int): Result<Unit> {
        val result = LiveLocationSharingCoordinator.startShare(roomId, durationMinutes)
        if (result.isSuccess) {
            currentRoomId = roomId
            _isSharing.value = true
        }
        return result
    }

    suspend fun stopSharing(): Result<Unit> {
        val roomId = currentRoomId
            ?: return Result.failure(IllegalStateException("No live location share is active"))
        val result = LiveLocationSharingCoordinator.stopShare(roomId)
        if (result.isSuccess) {
            currentRoomId = null
            _isSharing.value = false
        }
        return result
    }
}
