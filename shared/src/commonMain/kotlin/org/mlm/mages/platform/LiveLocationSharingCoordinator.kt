package org.mlm.mages.platform

expect object LiveLocationSharingCoordinator {
    fun recover()
    suspend fun startShare(roomId: String, durationMinutes: Int): Result<String>
    fun confirmShare(roomId: String, eventId: String, durationMinutes: Int)
    suspend fun stopShare(roomId: String): Result<Unit>
    fun dispatchLocation(lat: Double, lon: Double, accuracy: Float?)
    fun beaconEventId(roomId: String): String?
    val isSharing: Boolean
    fun isSharing(roomId: String): Boolean
    var onChanged: ((Boolean, Int) -> Unit)?
    var onFirstStarted: (() -> Unit)?
    var onAllStopped: (() -> Unit)?
    var onLocationDispatched: ((Double, Double) -> Unit)?
}
