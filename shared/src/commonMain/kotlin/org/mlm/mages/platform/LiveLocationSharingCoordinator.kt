package org.mlm.mages.platform

expect object LiveLocationSharingCoordinator {
    fun notifyShareActive(roomId: String)
    fun notifyShareInactive(roomId: String)
}
