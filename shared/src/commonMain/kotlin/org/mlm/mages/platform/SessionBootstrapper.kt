package org.mlm.mages.platform

import org.mlm.mages.MatrixService

object SessionBootstrapper {
    suspend fun ensureReadyAndSyncing(service: MatrixService) {
        runCatching { service.initFromDisk() }
        if (!service.isLoggedInSuspend() || service.portOrNull == null) return
        service.startSupervisedSync()
    }

    suspend fun ensureSessionReady(service: MatrixService) {
        runCatching { service.initFromDisk() }
    }
}
