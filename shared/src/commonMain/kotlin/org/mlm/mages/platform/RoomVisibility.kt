package org.mlm.mages.platform

internal class RoomVisibilityTracker {
    private val lock = Any()
    private val rooms = HashMap<String, Int>()

    fun set(roomId: String, visible: Boolean) {
        if (roomId.isEmpty()) return
        synchronized(lock) {
            val next = (rooms[roomId] ?: 0) + if (visible) 1 else -1
            if (next > 0) rooms[roomId] = next else rooms.remove(roomId)
        }
    }

    fun isVisible(roomId: String): Boolean = synchronized(lock) {
        (rooms[roomId] ?: 0) > 0
    }
}
