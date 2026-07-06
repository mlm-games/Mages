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
import java.util.Collections

actual object LiveLocationSharingCoordinator {

    private var recovered = false
    private val activeShares: MutableMap<String, Long> = Collections.synchronizedMap(mutableMapOf())
    private val timeoutJobs = Collections.synchronizedMap(mutableMapOf<String, Job>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var lastDispatchMs = 0L
    private val THROTTLE_MS = 3000L
    private val PREFIX = "share_"

    private val matrixPort: MatrixPort? by lazy {
        runCatching { KoinPlatform.getKoin().get<MatrixService>().portOrNull }.getOrNull()
    }

    private val prefs: SharedPreferences by lazy {
        val ctx: Context = KoinPlatform.getKoin().get()
        ctx.getSharedPreferences("live_location", Context.MODE_PRIVATE)
    }

    actual val isSharing: Boolean
        get() = activeShares.isNotEmpty()

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

    actual suspend fun startShare(roomId: String, durationMinutes: Int): Result<Unit> {
        val port = matrixPort ?: return Result.failure(IllegalStateException("Matrix not ready"))
        val durationMs = durationMinutes * 60 * 1000L
        port.startLiveLocationShare(roomId, durationMs).onFailure { return Result.failure(it) }
        val expiresAt = currentTimeMillis() + durationMs
        val wasEmpty = activeShares.isEmpty()
        activeShares[roomId] = expiresAt
        prefs.edit().putLong(PREFIX + roomId, expiresAt).apply()
        scheduleTimeout(roomId, expiresAt)
        onChanged?.invoke(true, activeShares.size)
        if (wasEmpty) onFirstStarted?.invoke()
        return Result.success(Unit)
    }

    actual suspend fun stopShare(roomId: String): Result<Unit> {
        val port = matrixPort
        if (port != null && activeShares.containsKey(roomId)) {
            port.stopLiveLocationShare(roomId)
        }
        activeShares.remove(roomId)
        timeoutJobs.remove(roomId)?.cancel()
        prefs.edit().remove(PREFIX + roomId).apply()
        val count = activeShares.size
        if (count == 0) {
            onAllStopped?.invoke()
            onChanged?.invoke(false, 0)
        } else {
            onChanged?.invoke(true, count)
        }
        return Result.success(Unit)
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
        scope.launch {
            for (roomId in rooms) {
                port.sendLiveLocation(roomId, geoUri)
            }
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
