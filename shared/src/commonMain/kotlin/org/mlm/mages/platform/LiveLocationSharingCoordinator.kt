package org.mlm.mages.platform

expect object LiveLocationSharingCoordinator {
    fun recover()
    suspend fun startShare(roomId: String, durationMinutes: Int): Result<String>
    fun confirmShare(roomId: String, durationMinutes: Int)
    suspend fun stopShare(roomId: String): Result<Unit>
    fun dispatchLocation(lat: Double, lon: Double, accuracy: Float?)
    val isSharing: Boolean
    fun isSharing(roomId: String): Boolean
    var onLocationDispatched: ((Double, Double) -> Unit)?
}
