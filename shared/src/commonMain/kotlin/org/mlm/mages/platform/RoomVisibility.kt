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

internal class ThreadVisibilityTracker {
    private val lock = RoomLock()
    private val threads = HashMap<String, Int>()

    fun set(roomId: String, threadId: String, visible: Boolean) {
        if (roomId.isEmpty() || threadId.isEmpty()) return
        val key = key(roomId, threadId)
        lock.withLock {
            val next = (threads[key] ?: 0) + if (visible) 1 else -1
            if (next > 0) threads[key] = next else threads.remove(key)
        }
    }

    fun isVisible(roomId: String, threadId: String): Boolean = lock.withLock {
        (threads[key(roomId, threadId)] ?: 0) > 0
    }

    private fun key(roomId: String, threadId: String) = "$roomId:$threadId"
}
