package org.mlm.mages.platform

internal actual class RoomLock {
    actual fun <T> withLock(block: () -> T): T = synchronized(this, block)
}
