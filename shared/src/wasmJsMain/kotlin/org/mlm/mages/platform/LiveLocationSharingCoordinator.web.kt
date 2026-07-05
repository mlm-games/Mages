package org.mlm.mages.platform

actual object LiveLocationSharingCoordinator {
    actual fun notifyShareActive(roomId: String) {}
    actual fun notifyShareInactive(roomId: String) {}
}
