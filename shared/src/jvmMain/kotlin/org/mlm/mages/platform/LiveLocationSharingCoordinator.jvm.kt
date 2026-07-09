package org.mlm.mages.platform

actual object LiveLocationSharingCoordinator {
    actual val isSharing: Boolean = false
    actual fun isSharing(roomId: String): Boolean = false
    actual fun recover() {}
    actual suspend fun startShare(roomId: String, durationMinutes: Int): Result<String> =
        Result.failure(IllegalStateException("Live location not supported on JVM"))

    actual suspend fun stopShare(roomId: String): Result<Unit> =
        Result.failure(IllegalStateException("Live location not supported on JVM"))

    actual fun dispatchLocation(lat: Double, lon: Double, accuracy: Float?) {}
    actual var onLocationDispatched: ((Double, Double) -> Unit)? = null
}
