package org.mlm.mages.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mages.DesktopLocationObserver
import mages.DesktopLocationSource
import mages.DesktopLocationUpdate
import mages.FfiException
import javax.swing.SwingUtilities

private class DesktopLocationRuntime {
    private val locationDispatcher =
        if (System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)) {
            Dispatchers.IO
        } else {
            Dispatchers.Swing
        }
    private val source = DesktopLocationSource()
    @Volatile
    private var updates = newLocationUpdates()
    @Volatile
    private var errors = newLocationErrors()
    private val mutex = Mutex()

    @Volatile
    private var started = false

    private val observer = object : DesktopLocationObserver {
        override fun onLocation(location: DesktopLocationUpdate) {
            updates.tryEmit(
                LocationData(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = location.altitude,
                    speed = location.speed?.toFloat(),
                    bearing = location.bearing?.toFloat(),
                )
            )
        }

        override fun onError(message: String) {
            errors.tryEmit(message)
        }
    }

    private fun newLocationUpdates() = MutableSharedFlow<LocationData>(
        replay = 1,
        extraBufferCapacity = 8,
    )

    private fun newLocationErrors() = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 8,
    )

    suspend fun start(): LocationResult = mutex.withLock {
        if (started) return@withLock LocationResult.Started
        try {
            withContext(locationDispatcher) {
                source.start(observer)
            }
            started = true
            LocationResult.Started
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (permissionDenied: FfiException.LocationPermissionDenied) {
            LocationResult.PermissionDenied
        } catch (error: Throwable) {
            LocationResult.Error(error.message ?: "Could not start location updates")
        }
    }

    fun stop() {
        val wasStarted = started
        started = false
        if (wasStarted) {
            if (SwingUtilities.isEventDispatchThread()) {
                runCatching { source.stop() }
            } else {
                runBlocking(locationDispatcher) {
                    runCatching { source.stop() }
                }
            }
        }
        updates = newLocationUpdates()
        errors = newLocationErrors()
    }

    fun updates(): Flow<LocationData> = updates

    fun errors(): Flow<String> = errors
}

actual class LiveLocationProvider actual constructor() : LocationSource {
    private val runtime = DesktopLocationRuntime()
    private val foreground = MutableStateFlow(true)

    actual override val isSupported: Boolean = true
    actual override val canSend: Boolean = true
    actual override val isForeground: StateFlow<Boolean> = foreground

    actual override suspend fun getCurrentLocation(): LocationResult {
        when (val start = runtime.start()) {
            LocationResult.Started -> Unit
            is LocationResult.Error -> return start
            LocationResult.NotSupported -> return LocationResult.NotSupported
            LocationResult.PermissionDenied -> return LocationResult.PermissionDenied
            is LocationResult.Success -> return LocationResult.Success(start.location)
        }

        val result = withTimeoutOrNull<Result<LocationData>>(20_000) {
            merge(
                runtime.updates().map { Result.success(it) },
                runtime.errors().map { Result.failure(IllegalStateException(it)) },
            ).first()
        }
        runtime.stop()
        return result?.fold(
            onSuccess = { LocationResult.Success(it) },
            onFailure = { LocationResult.Error(it.message ?: "Could not get location") },
        ) ?: LocationResult.Error("Timed out waiting for a location fix")
    }

    actual override suspend fun startLocationUpdates(): LocationResult =
        runtime.start()

    actual override fun locationUpdates(): Flow<LocationData> = runtime.updates()

    actual override fun locationErrors(): Flow<String> = runtime.errors()

    actual override fun stopLocationUpdates() = runtime.stop()

    actual override fun hasLocationPermission(): Boolean = false

    actual override suspend fun requestLocationPermission(): Boolean =
        getCurrentLocation() is LocationResult.Success
}
