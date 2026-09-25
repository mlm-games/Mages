package org.mlm.mages.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.mlm.mages.matrix.MatrixPort
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class LocationSharingCoordinatorCore(
    private val scope: CoroutineScope,
    private val source: LocationSource,
    private val matrixPort: () -> MatrixPort?,
) {
    private val activeShares = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val beaconEventIds = MutableStateFlow<Map<String, String>>(emptyMap())
    private val pendingLocations = MutableStateFlow<Map<String, PendingLocation>>(emptyMap())
    private val retryJobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val timeoutJobs = MutableStateFlow<Map<String, Job>>(emptyMap())

    private var sourceStarted = false
    private var sourceJob: Job? = null
    private var bufferedLocation: LocationData? = null
    private var lastDispatchMs = 0L

    var onChanged: ((Boolean, Int) -> Unit)? = null
    var onFirstStarted: (() -> Unit)? = null
    var onAllStopped: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onLocationDispatched: ((Double, Double) -> Unit)? = null

    val isSharing: Boolean
        get() = activeShares.value.isNotEmpty()

    fun isSharing(roomId: String): Boolean = activeShares.value.containsKey(roomId)

    fun beaconEventId(roomId: String): String? = beaconEventIds.value[roomId]

    fun recover() = Unit

    suspend fun startShare(roomId: String, durationMinutes: Int): Result<String> {
        if (!source.isSupported || !source.canSend) {
            return Result.failure(IllegalStateException("Live location is not supported on this platform"))
        }

        if (!sourceStarted) {
            bufferedLocation = null
            lastDispatchMs = 0L
            startSourceJob()
            sourceStarted = true
            when (val startResult = source.startLocationUpdates()) {
                LocationResult.Started,
                is LocationResult.Success -> Unit
                is LocationResult.Error -> {
                    stopSource()
                    return Result.failure(IllegalStateException(startResult.message))
                }
                LocationResult.NotSupported -> {
                    stopSource()
                    return Result.failure(IllegalStateException("Live location is not supported on this platform"))
                }
                LocationResult.PermissionDenied -> {
                    stopSource()
                    return Result.failure(IllegalStateException("Location permission denied"))
                }
            }
            if (!sourceStarted) {
                stopSource()
                return Result.failure(IllegalStateException("Location updates stopped"))
            }
        }

        val port = matrixPort()
        if (port == null) {
            if (activeShares.value.isEmpty()) stopSource()
            return Result.failure(IllegalStateException("Matrix not ready"))
        }
        val durationMs = durationMinutes * 60 * 1000L
        val result = port.startLiveLocationShare(roomId, durationMs)
        if (result.isFailure && activeShares.value.isEmpty()) {
            stopSource()
        }
        return result
    }

    fun confirmShare(roomId: String, eventId: String, durationMinutes: Int) {
        val wasEmpty = activeShares.value.isEmpty()
        val expiresAt = nowMs() + durationMinutes * 60 * 1000L
        activeShares.update { it + (roomId to expiresAt) }
        beaconEventIds.update { it + (roomId to eventId) }
        scheduleTimeout(roomId, expiresAt)
        onChanged?.invoke(true, activeShares.value.size)
        if (wasEmpty) {
            bufferedLocation?.let { location ->
                bufferedLocation = null
                dispatchNow(location.latitude, location.longitude, location.accuracy)
            }
            onFirstStarted?.invoke()
        }
    }

    suspend fun stopShare(roomId: String): Result<Unit> {
        val port = matrixPort()
        val result = if (port != null && isSharing(roomId)) {
            port.stopLiveLocationShare(roomId)
        } else {
            Result.success(Unit)
        }

        activeShares.update { it - roomId }
        beaconEventIds.update { it - roomId }
        timeoutJobs.value[roomId]?.cancel()
        timeoutJobs.update { it - roomId }
        pendingLocations.update { it - roomId }
        retryJobs.value[roomId]?.cancel()
        retryJobs.update { it - roomId }

        val count = activeShares.value.size
        if (count == 0) {
            stopSource()
            bufferedLocation = null
            onAllStopped?.invoke()
            onChanged?.invoke(false, 0)
        } else {
            onChanged?.invoke(true, count)
        }
        return result
    }

    fun dispatchLocation(latitude: Double, longitude: Double, accuracy: Float?) {
        scope.launch { dispatchNow(latitude, longitude, accuracy) }
    }

    private fun startSourceJob() {
        sourceJob?.cancel()
        sourceJob = scope.launch {
            try {
                coroutineScope {
                    val updatesJob = launch {
                        source.locationUpdates().collect { location ->
                            dispatchNow(location.latitude, location.longitude, location.accuracy)
                        }
                    }
                    val errorsJob = launch {
                        source.locationErrors().collect { message ->
                            handleSourceError(message)
                        }
                    }
                    joinAll(updatesJob, errorsJob)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                handleSourceError(error.message ?: "Location updates stopped")
            }
        }
    }

    private fun handleSourceError(message: String) {
        sourceStarted = false
        runCatching { source.stopLocationUpdates() }
        sourceJob?.cancel()
        sourceJob = null
        onError?.invoke(message)
        val roomIds = activeShares.value.keys.toList()
        if (roomIds.isNotEmpty()) {
            scope.launch {
                roomIds.forEach { stopShare(it) }
            }
        }
    }

    private fun stopSource() {
        sourceJob?.cancel()
        sourceJob = null
        sourceStarted = false
        runCatching { source.stopLocationUpdates() }
    }

    private fun dispatchNow(latitude: Double, longitude: Double, accuracy: Float?) {
        val location = LocationData(latitude, longitude, accuracy)
        if (activeShares.value.isEmpty()) {
            bufferedLocation = location
            return
        }

        val now = nowMs()
        if (now - lastDispatchMs < THROTTLE_MS) return
        lastDispatchMs = now
        onLocationDispatched?.invoke(latitude, longitude)

        val port = matrixPort() ?: return
        val pending = PendingLocation("geo:$latitude,$longitude", now)
        activeShares.value.keys.forEach { roomId ->
            pendingLocations.update { it + (roomId to pending) }
            ensureRetryLoop(roomId, port)
        }
    }

    private fun ensureRetryLoop(roomId: String, port: MatrixPort) {
        if (retryJobs.value[roomId]?.isActive == true) return
        val job = scope.launch {
            var delayMs = 500L
            while (isActive) {
                val location = pendingLocations.value[roomId] ?: return@launch
                val result = port.sendLiveLocation(roomId, location.geoUri)
                if (result.isSuccess) {
                    pendingLocations.update { current ->
                        if (current[roomId] == location) current - roomId else current
                    }
                    return@launch
                }
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(10_000L)
            }
        }
        retryJobs.update { it + (roomId to job) }
    }

    private fun scheduleTimeout(roomId: String, expiresAt: Long) {
        timeoutJobs.value[roomId]?.cancel()
        timeoutJobs.update { it - roomId }
        val delayMs = expiresAt - nowMs()
        if (delayMs <= 0) return
        val job = scope.launch {
            delay(delayMs)
            stopShare(roomId)
        }
        timeoutJobs.update { it + (roomId to job) }
    }

    private data class PendingLocation(
        val geoUri: String,
        val timestampMs: Long,
    )

    private companion object {
        const val THROTTLE_MS = 3000L

        @OptIn(ExperimentalTime::class)
        fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
    }
}
