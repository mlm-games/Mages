package org.mlm.mages.settings

import kotlinx.serialization.json.Json

/**
 * Last event timestamp read per thread.
 *
 * Room receipts do not cover thread replies, so notification loops need this to stop
 * re-offering a thread notification after it was read in a previous session.
 */
object ThreadReadMarks {
    private const val MAX_ENTRIES = 512
    private const val SEPARATOR = '\u001f'

    private val json = Json { ignoreUnknownKeys = true }

    fun key(roomId: String, threadId: String): String = "$roomId$SEPARATOR$threadId"

    fun latestTs(raw: String, roomId: String, threadId: String): Long =
        decode(raw)[key(roomId, threadId)] ?: 0L

    fun marked(raw: String, roomId: String, threadId: String, timestampMs: Long): String {
        if (roomId.isBlank() || threadId.isBlank() || timestampMs <= 0L) return raw
        val key = key(roomId, threadId)
        val current = decode(raw)
        val existing = current[key] ?: 0L
        if (timestampMs <= existing) return raw
        val next = current + (key to timestampMs)
        val trimmed = if (next.size <= MAX_ENTRIES) {
            next
        } else {
            next.entries
                .sortedByDescending { it.value }
                .take(MAX_ENTRIES)
                .associate { it.key to it.value }
        }
        return json.encodeToString(trimmed)
    }

    private fun decode(raw: String): Map<String, Long> {
        if (raw.isBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, Long>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
