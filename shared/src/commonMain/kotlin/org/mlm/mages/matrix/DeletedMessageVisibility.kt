package org.mlm.mages.matrix

import org.mlm.mages.MessageEvent

class DeletedMessageVisibility(
    private val now: () -> Long,
) {
    private val firstSeenMs = mutableMapOf<String, Long>()

    fun visible(events: List<MessageEvent>, retentionMs: Long?): List<MessageEvent> {
        if (retentionMs == null) {
            firstSeenMs.clear()
            return events
        }

        val nowMs = now()
        val visible = ArrayList<MessageEvent>(events.size)

        for (event in events) {
            if (!event.isRedacted) {
                visible += event
                continue
            }

            if (redactedAtOf(event, nowMs) + retentionMs > nowMs) {
                visible += event
            }
        }

        return visible
    }

    fun nextHideAtMs(events: List<MessageEvent>, retentionMs: Long?): Long? {
        if (retentionMs == null) return null

        val nowMs = now()
        var next: Long? = null

        for (event in events) {
            if (!event.isRedacted) continue

            val hideAt = redactedAtOf(event, nowMs) + retentionMs
            if (hideAt > nowMs && (next == null || hideAt < next)) {
                next = hideAt
            }
        }

        return next
    }

    fun clear() = firstSeenMs.clear()

    private fun redactedAtOf(event: MessageEvent, nowMs: Long): Long {
        val serverMs = event.redactedAtMs
        if (serverMs != null) {
            firstSeenMs.remove(event.eventId)
            return serverMs
        }
        return firstSeenMs.getOrPut(event.eventId) { nowMs }
    }
}
