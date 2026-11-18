package org.mlm.mages.push

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.ThreadUnreadCount
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Cross-device read gate for thread notifications.
 *
 * [org.mlm.mages.settings.ThreadReadMarks] only records reads made on this
 * device, so a thread read elsewhere keeps notifying. The event cache knows the
 * receipt-based counts instead: a thread it knows about with nothing left to
 * count is read. A thread it does not know proves nothing, so it is never
 * treated as read.
 */
object ThreadUnreadGate {
    private const val CACHE_TTL_MS = 5_000L
    private const val MAX_CACHED_ROOMS = 64

    private class Entry(val fetchedAtMs: Long, val byThread: Map<String, ThreadUnreadCount>)

    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, Entry>(16, 0.75f, true)

    suspend fun isRead(service: MatrixService, roomId: String, threadId: String): Boolean {
        if (roomId.isBlank() || threadId.isBlank()) return false
        val counts = counts(service, roomId) ?: return false
        val entry = counts[threadId] ?: return false
        return entry.unreadMessages == 0uL &&
            entry.unreadNotifications == 0uL &&
            entry.unreadMentions == 0uL
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun counts(
        service: MatrixService,
        roomId: String,
    ): Map<String, ThreadUnreadCount>? {
        val now = Clock.System.now().toEpochMilliseconds()
        mutex.withLock {
            cache[roomId]?.let { entry ->
                if (now - entry.fetchedAtMs < CACHE_TTL_MS) return entry.byThread
            }
        }

        val fetched = service.portOrNull?.threadUnreadCounts(roomId)?.getOrNull() ?: return null
        val byThread = fetched.associateBy { it.threadId }

        mutex.withLock {
            cache[roomId] = Entry(now, byThread)
            while (cache.size > MAX_CACHED_ROOMS) {
                cache.remove(cache.keys.first())
            }
        }
        return byThread
    }
}