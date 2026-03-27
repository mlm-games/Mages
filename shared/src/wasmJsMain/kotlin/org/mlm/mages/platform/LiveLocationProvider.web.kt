package org.mlm.mages.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

actual class LiveLocationProvider actual constructor() {
    actual val isSupported: Boolean = false
    actual val canSend: Boolean = false

    actual suspend fun getCurrentLocation(): LocationResult = LocationResult.NotSupported

    actual suspend fun startLocationUpdates() = Unit

    actual fun locationUpdates(): Flow<LocationData> = emptyFlow()

    actual fun stopLocationUpdates() = Unit

    actual fun hasLocationPermission(): Boolean = false

    actual suspend fun requestLocationPermission(): Boolean = false
}
