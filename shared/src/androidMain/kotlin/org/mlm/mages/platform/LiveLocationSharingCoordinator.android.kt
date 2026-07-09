package org.mlm.mages.platform

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MatrixPort
import mages.FfiException
import java.util.Collections

actual object LiveLocationSharingCoordinator {

    private var recovered = false
    private val activeShares: MutableMap<String, Long> = Collections.synchronizedMap(mutableMapOf())
    private val timeoutJobs = Collections.synchronizedMap(mutableMapOf<String, Job>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var lastDispatchMs = 0L
    private val THROTTLE_MS = 3000L
    private val PREFIX = "share_"

    private data class PendingLocation(
        val geoUri: String,
        val tsMs: Long,
    )

    private val pendingLocations = Collections.synchronizedMap(mutableMapOf<String, PendingLocation>())
    private val retryJobs = Collections.synchronizedMap(mutableMapOf<String, Job>())

    private val matrixPort: MatrixPort? by lazy {
        runCatching { KoinPlatform.getKoin().get<MatrixService>().portOrNull }.getOrNull()
    }

    private val prefs: SharedPreferences by lazy {
        val ctx: Context = KoinPlatform.getKoin().get()
        ctx.getSharedPreferences("live_location", Context.MODE_PRIVATE)
    }

    actual val isSharing: Boolean
        get() = activeShares.isNotEmpty()

    actual fun isSharing(roomId: String): Boolean = activeShares.containsKey(roomId)

    actual fun recover() {
        if (recovered) return
        val now = currentTimeMillis()
        val entries = prefs.all.filterKeys { it.startsWith(PREFIX) }
        for ((key, value) in entries) {
            val roomId = key.removePrefix(PREFIX)
            val expiresAt = (value as? Long) ?: continue
            if (expiresAt > now) {
                activeShares[roomId] = expiresAt
                scheduleTimeout(roomId, expiresAt)
            } else {
                prefs.edit().remove(key).apply()
            }
        }
        if (activeShares.isNotEmpty()) {
            onChanged?.invoke(true, activeShares.size)
        }
        recovered = true
    }

    actual suspend fun startShare(roomId: String, durationMinutes: Int): Result<String> {
        val port = matrixPort ?: return Result.failure(IllegalStateException("Matrix not ready"))
        val durationMs = durationMinutes * 60 * 1000L
        return port.startLiveLocationShare(roomId, durationMs)
    }

    actual fun confirmShare(roomId: String, durationMinutes: Int) {
        val expiresAt = currentTimeMillis() + durationMinutes * 60 * 1000L
        activeShares[roomId] = expiresAt
        prefs.edit().putLong(PREFIX + roomId, expiresAt).apply()
        val wasEmpty = activeShares.size == 1
        scheduleTimeout(roomId, expiresAt)
        onChanged?.invoke(true, activeShares.size)
        if (wasEmpty) onFirstStarted?.invoke()
    }

    actual suspend fun stopShare(roomId: String): Result<Unit> {
        val port = matrixPort
        val result = if (port != null && activeShares.containsKey(roomId)) {
            port.stopLiveLocationShare(roomId)
        } else {
            Result.success(Unit)
        }
        activeShares.remove(roomId)
        timeoutJobs.remove(roomId)?.cancel()
        pendingLocations.remove(roomId)
        retryJobs.remove(roomId)?.cancel()
        prefs.edit().remove(PREFIX + roomId).apply()
        val count = activeShares.size
        if (count == 0) {
            onAllStopped?.invoke()
            onChanged?.invoke(false, 0)
        } else {
            onChanged?.invoke(true, count)
        }
        return result
    }

    actual fun dispatchLocation(lat: Double, lon: Double, accuracy: Float?) {
        if (activeShares.isEmpty()) return
        val now = currentTimeMillis()
        if (now - lastDispatchMs < THROTTLE_MS) return
        lastDispatchMs = now
        val port = matrixPort ?: return
        onLocationDispatched?.invoke(lat, lon)
        val geoUri = "geo:$lat,$lon"
        val rooms = synchronized(activeShares) { activeShares.keys.toList() }
        for (roomId in rooms) {
            pendingLocations[roomId] = PendingLocation(geoUri, now)
            ensureRetryLoop(roomId, port)
        }
    }

    private fun ensureRetryLoop(roomId: String, port: MatrixPort) {
        synchronized(retryJobs) {
            if (retryJobs[roomId]?.isActive == true) return

            val job = scope.launch {
                var delayMs = 500L

                while (true) {
                    val location = synchronized(pendingLocations) { pendingLocations[roomId] } ?: return@launch

                    val result = port.sendLiveLocation(roomId, location.geoUri)
                    if (result.isSuccess) {
                        synchronized(pendingLocations) {
                            if (pendingLocations[roomId] == location) {
                                pendingLocations.remove(roomId)
                            }
                        }
                        return@launch
                    }

                    val e = result.exceptionOrNull()
                    if (e is FfiException.NotLive) {
                        stopShare(roomId)
                        return@launch
                    }

                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(10_000L)
                }
            }

            retryJobs[roomId] = job
        }
    }

    private fun scheduleTimeout(roomId: String, expiresAt: Long) {
        timeoutJobs.remove(roomId)?.cancel()
        val delayMs = expiresAt - currentTimeMillis()
        if (delayMs <= 0) return
        timeoutJobs[roomId] = scope.launch {
            delay(delayMs)
            stopShare(roomId)
        }
    }

    private fun currentTimeMillis(): Long = System.currentTimeMillis()

    var onChanged: ((Boolean, Int) -> Unit)? = null
    var onFirstStarted: (() -> Unit)? = null
    var onAllStopped: (() -> Unit)? = null
    actual var onLocationDispatched: ((Double, Double) -> Unit)? = null
}
