package org.mlm.mages.matrix

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BackPaginationStatus {
    data object Idle : BackPaginationStatus
    data object Paginating : BackPaginationStatus
    data object TimelineStartReached : BackPaginationStatus
    data class Error(val cause: Throwable? = null) : BackPaginationStatus
}

class BackPaginationSession(
    private val scope: CoroutineScope,
    private val maxAttempts: Int = 24,
    private val diffSettleMs: Long = 150L,
) {
    private var job: Job? = null

    private val _status = MutableStateFlow<BackPaginationStatus>(BackPaginationStatus.Idle)
    val status: StateFlow<BackPaginationStatus> = _status.asStateFlow()

    val isActive: Boolean get() = job?.isActive == true

    val isTimelineStartReached: Boolean
        get() = _status.value is BackPaginationStatus.TimelineStartReached

    fun startSession(
        paginateOnce: suspend () -> Boolean,
        shouldStop: () -> Boolean = { false },
    ) {
        if (job?.isActive == true) return
        if (_status.value is BackPaginationStatus.TimelineStartReached) return

        job = scope.launch {
            var attempts = 0
            _status.value = BackPaginationStatus.Paginating

            try {
                while (attempts < maxAttempts) {
                    val hitStart = try {
                        paginateOnce()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Throwable) {
                        _status.value = BackPaginationStatus.Error(e)
                        return@launch
                    }

                    if (hitStart) {
                        _status.value = BackPaginationStatus.TimelineStartReached
                        return@launch
                    }

                    delay(diffSettleMs)

                    if (shouldStop()) break

                    attempts++
                }

                _status.value = BackPaginationStatus.Idle
            } catch (ce: CancellationException) {
                _status.value = BackPaginationStatus.Idle
                throw ce
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (_status.value !is BackPaginationStatus.TimelineStartReached) {
            _status.value = BackPaginationStatus.Idle
        }
    }

    fun reset() {
        job?.cancel()
        job = null
        _status.value = BackPaginationStatus.Idle
    }
}

// TODO: Later use this instead of existing, or adapt, the room pagination logic for unread, and other relevant parts too