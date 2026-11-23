package org.mlm.mages.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class PushHistoryEntry(
    val id: Long,
    val timestampMs: Long,
    val platform: String,
    val raw: String,
    val summary: String,
)

object PushHistoryStore {
    private const val MAX_ENTRIES = 50
    private const val MAX_RAW_LENGTH = 4000

    private val _entries = MutableStateFlow<List<PushHistoryEntry>>(emptyList())
    val entries: StateFlow<List<PushHistoryEntry>> = _entries.asStateFlow()

    @OptIn(ExperimentalTime::class)
    fun record(platform: String, raw: String) {
        val summary = extractMatrixPushPayload(raw)
            .joinToString(", ") { push ->
                when (push) {
                    is ParsedMatrixPush.Event -> "event ${push.eventId} in ${push.roomId}"
                    is ParsedMatrixPush.CountsUpdate ->
                        "counts room=${push.roomId} unread=${push.unread} mentions=${push.mentions}"
                }
            }
            .ifBlank { "unparsed payload" }

        _entries.update { existing ->
            val entry = PushHistoryEntry(
                id = (existing.firstOrNull()?.id ?: 0L) + 1L,
                timestampMs = Clock.System.now().toEpochMilliseconds(),
                platform = platform,
                raw = raw.take(MAX_RAW_LENGTH),
                summary = summary,
            )
            (listOf(entry) + existing).take(MAX_ENTRIES)
        }
    }
}
