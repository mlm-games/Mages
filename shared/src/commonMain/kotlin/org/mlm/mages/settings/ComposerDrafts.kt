package org.mlm.mages.settings

import kotlinx.serialization.json.Json

private const val THREAD_PREFIX = "thread:"
private const val THREAD_SEPARATOR = '\u001f'

object ComposerDrafts {
    private val json = Json { ignoreUnknownKeys = true }

    fun roomKey(roomId: String): String = roomId

    fun threadKey(roomId: String, threadId: String): String =
        "$THREAD_PREFIX$roomId$THREAD_SEPARATOR$threadId"

    fun text(raw: String, key: String): String? =
        decode(raw)[key]?.takeIf { it.isNotBlank() }

    fun updated(raw: String, key: String, text: String): String {
        val current = decode(raw)
        val next = if (text.isBlank()) current - key else current - key + (key to text)
        return json.encodeToString(next)
    }

    fun previewByRoom(raw: String): Map<String, String> =
        decode(raw)
            .filterKeys { !it.startsWith(THREAD_PREFIX) }
            .mapValues { (_, text) -> text.replace('\n', ' ').trim() }
            .filterValues { it.isNotBlank() }

    private fun decode(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, String>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
