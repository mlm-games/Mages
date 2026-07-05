package org.mlm.mages.platform

import java.util.Collections

actual object LiveLocationSharingCoordinator {

    private val activeShares = Collections.synchronizedSet(mutableSetOf<String>())

    actual fun notifyShareActive(roomId: String) {
        val wasEmpty = activeShares.isEmpty()
        activeShares.add(roomId)
        onChanged?.invoke(true, activeShares.size)
        if (wasEmpty) {
            onFirstStarted?.invoke()
        }
    }

    actual fun notifyShareInactive(roomId: String) {
        activeShares.remove(roomId)
        val count = activeShares.size
        onChanged?.invoke(count > 0, count)
        if (count == 0) {
            onAllStopped?.invoke()
        }
    }

    var onChanged: ((isActive: Boolean, count: Int) -> Unit)? = null
    var onFirstStarted: (() -> Unit)? = null
    var onAllStopped: (() -> Unit)? = null
}
