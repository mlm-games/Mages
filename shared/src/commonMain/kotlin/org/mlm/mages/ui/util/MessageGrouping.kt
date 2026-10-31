package org.mlm.mages.ui.util

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.mlm.mages.MessageEvent
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val GROUP_WINDOW_MS = 5 * 60 * 1000L

@OptIn(ExperimentalTime::class)
fun isSameMessageGroup(first: MessageEvent, second: MessageEvent): Boolean {
    val delta = second.timestampMs - first.timestampMs
    if (first.sender != second.sender || delta < 0 || delta >= GROUP_WINDOW_MS) return false
    val zone = TimeZone.currentSystemDefault()
    return Instant.fromEpochMilliseconds(first.timestampMs).toLocalDateTime(zone).date ==
        Instant.fromEpochMilliseconds(second.timestampMs).toLocalDateTime(zone).date
}
