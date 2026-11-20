package org.mlm.mages.push

import kotlinx.coroutines.delay
import org.mlm.mages.matrix.MatrixPort
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

data class ThreadReadTarget(val roomId: String, val threadId: String, val latestTsMs: Long?)

data class ClearedNotifications(
    val rooms: Set<String> = emptySet(),
    val threads: Set<Pair<String, String>> = emptySet(),
)

object NotificationReadState {
    private const val POLL_INTERVAL_MS = 500L
    private val WAIT_BUDGET = 12.seconds

    suspend fun isEventRead(port: MatrixPort, roomId: String, eventTsMs: Long): Boolean {
        val readTs = readMark(port, roomId) ?: return false
        return readTs >= eventTsMs
    }

    suspend fun isThreadEventRead(
        port: MatrixPort,
        roomId: String,
        threadId: String,
        eventTsMs: Long,
    ): Boolean {
        val readTs = threadReadMark(port, roomId, threadId) ?: return false
        return readTs >= eventTsMs
    }

    suspend fun awaitCleared(
        port: MatrixPort,
        rooms: List<String>,
        threads: List<ThreadReadTarget>,
        latestTsByRoom: Map<String, Long>,
        threadCountsRead: suspend (String, String) -> Boolean,
    ): ClearedNotifications {
        val clearedRooms = mutableSetOf<String>()
        val clearedThreads = mutableSetOf<Pair<String, String>>()
        val decidedRooms = mutableSetOf<String>()
        val decidedThreads = mutableSetOf<Pair<String, String>>()
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            for (roomId in rooms) {
                if (roomId in decidedRooms) continue
                val stats = runCatching { port.roomUnreadStats(roomId) }.getOrNull() ?: continue
                val read = roomIsRead(
                    serverNotifications = stats.serverNotifications,
                    serverMentions = stats.serverMentions,
                    notifications = stats.notifications,
                    mentions = stats.mentions,
                    latestTsMs = latestTsByRoom[roomId],
                    readTs = { readMark(port, roomId) },
                )
                if (read) {
                    decidedRooms += roomId
                    clearedRooms += roomId
                }
            }

            for (thread in threads) {
                val key = thread.roomId to thread.threadId
                if (key in decidedThreads) continue
                val readTs = if (thread.latestTsMs != null) {
                    threadReadMark(port, thread.roomId, thread.threadId)
                } else null
                val read = threadIsRead(thread.latestTsMs, readTs) {
                    threadCountsRead(thread.roomId, thread.threadId)
                }
                if (read) {
                    decidedThreads += key
                    clearedThreads += key
                }
            }

            val allDecided = decidedRooms.size == rooms.size && decidedThreads.size == threads.size
            if (allDecided || started.elapsedNow() >= WAIT_BUDGET) {
                return ClearedNotifications(clearedRooms, clearedThreads)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun readMark(port: MatrixPort, roomId: String): Long? =
        runCatching { port.ownLastRead(roomId).second }.getOrNull()

    private suspend fun threadReadMark(
        port: MatrixPort,
        roomId: String,
        threadId: String,
    ): Long? = runCatching { port.ownThreadLastRead(roomId, threadId).second }.getOrNull()
}
