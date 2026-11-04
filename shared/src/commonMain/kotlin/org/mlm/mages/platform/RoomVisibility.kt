package org.mlm.mages.platform

internal expect class RoomLock() {
    fun <T> withLock(block: () -> T): T
}

internal class RoomVisibilityTracker {
    private val lock = RoomLock()
    private val rooms = HashMap<String, Int>()

    fun set(roomId: String, visible: Boolean) {
        if (roomId.isEmpty()) return
        lock.withLock {
            val next = (rooms[roomId] ?: 0) + if (visible) 1 else -1
            if (next > 0) rooms[roomId] = next else rooms.remove(roomId)
        }
    }

    fun isVisible(roomId: String): Boolean = lock.withLock {
        (rooms[roomId] ?: 0) > 0
    }
}
