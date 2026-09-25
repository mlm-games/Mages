package org.mlm.mages.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.mp.KoinPlatform
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MatrixPort

actual object LiveLocationSharingCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val source = LiveLocationProvider()
    private val core = LocationSharingCoordinatorCore(
        scope = scope,
        source = source,
        matrixPort = ::matrixPort,
    )

    private fun matrixPort(): MatrixPort? =
        runCatching { KoinPlatform.getKoin().get<MatrixService>().portOrNull }.getOrNull()

    actual val isSharing: Boolean
        get() = core.isSharing

    actual fun isSharing(roomId: String): Boolean = core.isSharing(roomId)

    actual fun recover() = core.recover()

    actual suspend fun startShare(roomId: String, durationMinutes: Int): Result<String> =
        core.startShare(roomId, durationMinutes)

    actual fun confirmShare(roomId: String, eventId: String, durationMinutes: Int) =
        core.confirmShare(roomId, eventId, durationMinutes)

    actual suspend fun stopShare(roomId: String): Result<Unit> = core.stopShare(roomId)

    actual fun beaconEventId(roomId: String): String? = core.beaconEventId(roomId)

    actual fun dispatchLocation(lat: Double, lon: Double, accuracy: Float?) =
        core.dispatchLocation(lat, lon, accuracy)

    actual var onLocationDispatched: ((Double, Double) -> Unit)?
        get() = core.onLocationDispatched
        set(value) {
            core.onLocationDispatched = value
        }

    actual var onChanged: ((Boolean, Int) -> Unit)?
        get() = core.onChanged
        set(value) {
            core.onChanged = value
        }

    actual var onFirstStarted: (() -> Unit)?
        get() = core.onFirstStarted
        set(value) {
            core.onFirstStarted = value
        }

    actual var onAllStopped: (() -> Unit)?
        get() = core.onAllStopped
        set(value) {
            core.onAllStopped = value
        }

    actual var onError: ((String) -> Unit)?
        get() = core.onError
        set(value) {
            core.onError = value
        }
}
