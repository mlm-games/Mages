package org.mlm.mages.ui.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MultipleTapToUnlock(private val numberOfTaps: Int = 7) {
    private var remaining = numberOfTaps
    private var resetJob: Job? = null

    fun unlock(scope: CoroutineScope): Boolean {
        remaining--
        resetJob?.cancel()
        return if (remaining > 0) {
            resetJob = scope.launch {
                delay(2_000)
                remaining = numberOfTaps
            }
            false
        } else {
            true
        }
    }
}
