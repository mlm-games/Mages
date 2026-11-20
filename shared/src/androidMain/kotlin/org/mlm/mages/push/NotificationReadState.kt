package org.mlm.mages.push

import kotlinx.coroutines.delay
import org.mlm.mages.matrix.MatrixPort
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

object NotificationReadState {
    private const val POLL_INTERVAL_MS = 500L
    private val WAIT_BUDGET = 12.seconds

    suspend fun isEventRead(port: MatrixPort, roomId: String, eventTsMs: Long): Boolean {
        val readTs = readMark(port, roomId) ?: return false
        return readTs >= eventTsMs
    }

    suspend fun awaitCleared(
        port: MatrixPort,
        rooms: List<String>,
        latestTsByRoom: Map<String, Long>,
    ): Set<String> {
        val cleared = mutableSetOf<String>()
        val decided = mutableSetOf<String>()
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            for (roomId in rooms) {
                if (roomId in decided) continue
                val stats = runCatching { port.roomUnreadStats(roomId) }.getOrNull() ?: continue
                val serverCleared = stats.serverNotifications == 0L && stats.serverMentions == 0L
                val localCleared = stats.notifications == 0L && stats.mentions == 0L
                if (!serverCleared && !localCleared) continue

                val latestTs = latestTsByRoom[roomId]
                val read = if (latestTs == null) {
                    serverCleared && localCleared
                } else {
                    val readTs = readMark(port, roomId)
                    readTs != null && readTs >= latestTs
                }
                decided += roomId
                if (read) cleared += roomId
            }
            if (decided.size == rooms.size || started.elapsedNow() >= WAIT_BUDGET) return cleared
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun readMark(port: MatrixPort, roomId: String): Long? =
        runCatching { port.ownLastRead(roomId).second }.getOrNull()
}
