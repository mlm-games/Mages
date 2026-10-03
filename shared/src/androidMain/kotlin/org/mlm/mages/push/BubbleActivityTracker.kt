package org.mlm.mages.push

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object BubbleActivityTracker {
    private val openRooms = ConcurrentHashMap<String, AtomicInteger>()

    fun onBubbleOpened(roomId: String) {
        openRooms.computeIfAbsent(roomId) { AtomicInteger() }.incrementAndGet()
    }

    fun onBubbleClosed(roomId: String) {
        val count = openRooms[roomId] ?: return
        if (count.decrementAndGet() <= 0) {
            openRooms.remove(roomId, count)
        }
    }

    fun isBubbleOpen(roomId: String): Boolean = (openRooms[roomId]?.get() ?: 0) > 0
}
