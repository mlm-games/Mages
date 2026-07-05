package org.mlm.mages.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.mlm.mages.matrix.MatrixPort
import kotlin.time.Duration

class LiveLocationSession(
    private val matrixPort: MatrixPort,
    private val locationProvider: LiveLocationProvider,
    private val onLocationSent: ((LocationData) -> Unit)? = null,
    private val onShareStateChanged: ((Boolean, String) -> Unit)? = null,
) {
    private var sendingJob: Job? = null
    private var sessionScope: CoroutineScope? = null

    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

    private var currentRoomId: String? = null

    companion object {
        val UPDATE_INTERVAL = Duration.parse("30s")
    }

    fun isSupported(): Boolean = locationProvider.isSupported

    fun canSend(): Boolean = locationProvider.canSend

    suspend fun startSharing(roomId: String, durationMinutes: Int): Result<Unit> {
        if (!locationProvider.canSend) {
            return Result.failure(IllegalStateException("Location sharing not supported on this platform"))
        }

        val initialLocation = when (val result = locationProvider.getCurrentLocation()) {
            is LocationResult.Success -> result.location
            is LocationResult.PermissionDenied -> {
                return Result.failure(IllegalStateException("Location permission denied"))
            }
            is LocationResult.NotSupported -> {
                return Result.failure(IllegalStateException("Location services are not supported on this device"))
            }
            is LocationResult.Error -> {
                return Result.failure(IllegalStateException(result.message))
            }
        }

        val durationMs = durationMinutes * 60 * 1000L
        val started = matrixPort.startLiveLocationShare(roomId, durationMs)
        if (started.isFailure) {
            return started
        }

        val initialGeoUri = "geo:${initialLocation.latitude},${initialLocation.longitude}"
        matrixPort.sendLiveLocation(roomId, initialGeoUri).getOrElse {
            return Result.failure(it)
        }
        onLocationSent?.invoke(initialLocation)

        currentRoomId = roomId
        _isSharing.value = true

        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        sendingJob = sessionScope?.launch {
            locationProvider.startLocationUpdates()
            sendLocationLoop(roomId)
        }

        onShareStateChanged?.invoke(true, roomId)
        return Result.success(Unit)
    }

    suspend fun stopSharing(): Result<Unit> {
        val roomId = currentRoomId
            ?: return Result.failure(IllegalStateException("No live location share is active"))

        sendingJob?.cancel()
        sendingJob = null
        sessionScope?.cancel()
        sessionScope = null
        locationProvider.stopLocationUpdates()

        val stopped = matrixPort.stopLiveLocationShare(roomId)

        currentRoomId = null
        _isSharing.value = false

        onShareStateChanged?.invoke(false, roomId)
        return stopped
    }

    private suspend fun sendLocationLoop(roomId: String) {
        val scope = sessionScope ?: return
        while (scope.isActive && _isSharing.value) {
            when (val location = locationProvider.getCurrentLocation()) {
                is LocationResult.Success -> {
                    val geoUri = "geo:${location.location.latitude},${location.location.longitude}"
                    if (matrixPort.sendLiveLocation(roomId, geoUri).isSuccess) {
                        onLocationSent?.invoke(location.location)
                    }
                }
                else -> Unit
            }

            delay(UPDATE_INTERVAL)
        }
    }
}
