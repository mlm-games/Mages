package org.mlm.mages.matrix

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Remembers which thread each event belongs to for as long as the account is loaded.
 *
 * Matrix redaction strips `m.relates_to`, and matrix-rust-sdk exposes no relation for a
 * redacted item, so a deleted thread reply reaches the room timeline looking like a room
 * message. Callers record replies they have seen and look them up before trusting a
 * missing thread root.
 */
object ThreadReplyIndex {
    private const val MAX_ENTRIES = 4096
    private const val SEPARATOR = '\u0000'

    private val roots = MutableStateFlow<Map<String, String>>(emptyMap())

    fun record(roomId: String, replies: Map<String, String>) {
        if (roomId.isBlank()) return
        val entries = replies
            .filterKeys { it.isNotBlank() }
            .filter { (eventId, rootEventId) -> rootEventId.isNotBlank() && eventId != rootEventId }
        if (entries.isEmpty()) return
        roots.update { current ->
            val merged = current + entries.mapKeys { (eventId, _) -> key(roomId, eventId) }
            if (merged.size <= MAX_ENTRIES) {
                merged
            } else {
                merged.entries.toList().takeLast(MAX_ENTRIES).associate { it.key to it.value }
            }
        }
    }

    fun rootOf(roomId: String, eventId: String): String? {
        if (roomId.isBlank() || eventId.isBlank()) return null
        return roots.value[key(roomId, eventId)]
    }

    fun clear() {
        roots.value = emptyMap()
    }

    private fun key(roomId: String, eventId: String) = "$roomId$SEPARATOR$eventId"
}
