package org.mlm.mages.matrix

import org.mlm.mages.MessageEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val MINUTE = 60_000L

private fun message(
    id: String,
    isRedacted: Boolean = false,
    redactedAtMs: Long? = null,
) = MessageEvent(
    itemId = "item-$id",
    eventId = id,
    roomId = "!room:example.org",
    sender = "@alice:example.org",
    body = "",
    timestampMs = 0L,
    isRedacted = isRedacted,
    redactedAtMs = redactedAtMs,
)

class DeletedMessageVisibilityTest {

    @Test
    fun keepsMessagesThatWereNotDeleted() {
        val visibility = DeletedMessageVisibility { 0L }
        val events = listOf(message("\$a"), message("\$b"))

        assertEquals(events, visibility.visible(events, MINUTE))
    }

    @Test
    fun hidesDeletedMessagesOnceTheRetentionPasses() {
        var now = 10 * MINUTE
        val visibility = DeletedMessageVisibility { now }
        val fresh = message("\$fresh", isRedacted = true, redactedAtMs = now)
        val stale = message("\$stale", isRedacted = true, redactedAtMs = now - 2 * MINUTE)

        assertEquals(listOf(fresh), visibility.visible(listOf(fresh, stale), MINUTE))

        now += MINUTE
        assertEquals(emptyList(), visibility.visible(listOf(fresh, stale), MINUTE))
    }

    @Test
    fun fallsBackToTheFirstObservationWithoutAServerTimestamp() {
        var now = 0L
        val visibility = DeletedMessageVisibility { now }
        val event = message("\$pending", isRedacted = true)

        assertEquals(listOf(event), visibility.visible(listOf(event), MINUTE))

        now += 30_000L
        assertEquals(listOf(event), visibility.visible(listOf(event), MINUTE))

        now += 31_000L
        assertEquals(emptyList(), visibility.visible(listOf(event), MINUTE))
    }

    @Test
    fun theServerTimestampReplacesTheFirstObservation() {
        var now = 0L
        val visibility = DeletedMessageVisibility { now }
        val pending = message("\$x", isRedacted = true)

        visibility.visible(listOf(pending), MINUTE)
        now += 10 * MINUTE

        val withServerTime = pending.copy(redactedAtMs = now)
        assertEquals(listOf(withServerTime), visibility.visible(listOf(withServerTime), MINUTE))
    }

    @Test
    fun nextHideOnlyReportsDeadlinesStillInTheFuture() {
        var now = 0L
        val visibility = DeletedMessageVisibility { now }
        val stale = message("\$stale", isRedacted = true, redactedAtMs = -5 * MINUTE)
        val dueSoon = message("\$due", isRedacted = true, redactedAtMs = 0L)

        assertEquals(MINUTE, visibility.nextHideAtMs(listOf(stale, dueSoon), MINUTE))

        now = 2 * MINUTE
        assertNull(visibility.nextHideAtMs(listOf(stale, dueSoon), MINUTE))
    }

    @Test
    fun noRetentionKeepsEverything() {
        val visibility = DeletedMessageVisibility { 0L }
        val events = listOf(message("\$a", isRedacted = true, redactedAtMs = 0L))

        assertEquals(events, visibility.visible(events, null))
        assertNull(visibility.nextHideAtMs(events, null))
    }
}
