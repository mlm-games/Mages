package org.mlm.mages.platform

import dev.jordond.compass.Location as CompassLocation
import dev.jordond.compass.Priority
import dev.jordond.compass.geolocation.GeolocatorResult
import dev.jordond.compass.geolocation.LocationRequest
import dev.jordond.compass.geolocation.TrackingStatus
import dev.jordond.compass.geolocation.BrowserGeolocator
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val WEB_FOREGROUND_MESSAGE = "Keep Mages open to continue sharing"

private object WebLocationVisibility {
    val isVisible: MutableStateFlow<Boolean> = MutableStateFlow(
        documentVisibilityState() == "visible",
    )

    init {
        document.addEventListener("visibilitychange") {
            isVisible.value = documentVisibilityState() == "visible"
        }
    }
}

actual class LiveLocationProvider actual constructor() : LocationSource {
    private val liveGeolocator = BrowserGeolocator()
    private val currentGeolocator = BrowserGeolocator()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updates = newLocationUpdates()
    private var errors = newLocationErrors()
    private val startMutex = Mutex()
    private var shouldTrack = false
    private var nativeTracking = false

    private fun newLocationUpdates() = MutableSharedFlow<LocationData>(
        replay = 1,
        extraBufferCapacity = 8,
    )

    private fun newLocationErrors() = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 8,
    )

    init {
        scope.launch {
            liveGeolocator.trackingStatus.collect { status ->
                when (status) {
                    is TrackingStatus.Update -> updates.emit(status.location.toLocationData())
                    is TrackingStatus.Error -> {
                        errors.emit(status.cause.message)
                        shouldTrack = false
                        nativeTracking = false
                        liveGeolocator.stopTracking()
                    }
                    TrackingStatus.Idle,
                    TrackingStatus.Tracking -> Unit
                }
            }
        }
        scope.launch {
            WebLocationVisibility.isVisible.collect { visible ->
                    if (!shouldTrack) return@collect
                    if (visible) {
                        val result = startMutex.withLock {
                            if (shouldTrack && !nativeTracking) beginTracking()
                            else LocationResult.Started
                        }
                        if (result is LocationResult.Error) {
                            errors.emit(result.message)
                            shouldTrack = false
                            nativeTracking = false
                            liveGeolocator.stopTracking()
                        } else if (result == LocationResult.NotSupported) {
                            errors.emit("Location is not supported on this device")
                            shouldTrack = false
                            nativeTracking = false
                            liveGeolocator.stopTracking()
                        } else if (result == LocationResult.PermissionDenied) {
                            errors.emit("Location permission denied")
                            shouldTrack = false
                            nativeTracking = false
                            liveGeolocator.stopTracking()
                        }
                    } else {
                        liveGeolocator.stopTracking()
                        nativeTracking = false
                    }
                }
        }
    }

    actual override val isSupported: Boolean = true
    actual override val canSend: Boolean = true
    actual override val isForeground: StateFlow<Boolean> = WebLocationVisibility.isVisible

    actual override suspend fun getCurrentLocation(): LocationResult {
        if (!isForeground.value) return foregroundOnlyResult()
        val result = withTimeoutOrNull(20_000) {
            currentGeolocator.current(
                LocationRequest(
                    priority = Priority.HighAccuracy,
                    interval = 5_000L,
                )
            )
        } ?: return LocationResult.Error("Timed out waiting for a location fix")
        return result.toLocationResult()
    }

    actual override suspend fun startLocationUpdates(): LocationResult = startMutex.withLock {
        if (!isForeground.value) return@withLock foregroundOnlyResult()
        shouldTrack = true
        beginTracking()
    }

    actual override fun locationUpdates(): Flow<LocationData> = updates

    actual override fun locationErrors(): Flow<String> = errors

    actual override fun stopLocationUpdates() {
        shouldTrack = false
        nativeTracking = false
        liveGeolocator.stopTracking()
        updates = newLocationUpdates()
        errors = newLocationErrors()
    }

    actual override fun hasLocationPermission(): Boolean = false

    actual override suspend fun requestLocationPermission(): Boolean =
        getCurrentLocation() is LocationResult.Success

    private suspend fun beginTracking(): LocationResult {
        if (!isForeground.value) return foregroundOnlyResult()
        if (nativeTracking) return LocationResult.Started

        val statusFlow = liveGeolocator.track(
            LocationRequest(
                priority = Priority.HighAccuracy,
                interval = 5_000L,
            )
        )
        val status = withTimeoutOrNull(20_000) {
            statusFlow.first { it is TrackingStatus.Update || it is TrackingStatus.Error }
        }
        if (status == null) {
            liveGeolocator.stopTracking()
            return LocationResult.Error("Timed out waiting for a location fix")
        }

        return when (status) {
            is TrackingStatus.Update -> {
                nativeTracking = true
                LocationResult.Started
            }
            is TrackingStatus.Error -> {
                nativeTracking = false
                status.cause.toLocationResult()
            }
            TrackingStatus.Idle,
            TrackingStatus.Tracking -> {
                nativeTracking = true
                LocationResult.Started
            }
        }
    }

    private fun foregroundOnlyResult(): LocationResult = LocationResult.Error(
        WEB_FOREGROUND_MESSAGE,
    )
}

private fun GeolocatorResult.toLocationResult(): LocationResult = when (this) {
    is GeolocatorResult.Success -> LocationResult.Success(data.toLocationData())
    is GeolocatorResult.PermissionDenied -> LocationResult.PermissionDenied
    GeolocatorResult.NotSupported -> LocationResult.NotSupported
    GeolocatorResult.NotFound -> LocationResult.Error("No location found")
    is GeolocatorResult.GeolocationFailed -> LocationResult.Error(message)
    is GeolocatorResult.Error -> LocationResult.Error(message)
}

private fun CompassLocation.toLocationData() = LocationData(
    latitude = coordinates.latitude,
    longitude = coordinates.longitude,
    accuracy = accuracy.toFloat(),
    altitude = ellipsoidalAltitude?.meters ?: mslAltitude?.meters,
    speed = speed?.mps,
    bearing = azimuth?.degrees,
)
