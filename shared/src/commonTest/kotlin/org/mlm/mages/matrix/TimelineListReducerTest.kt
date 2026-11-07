package org.mlm.mages.matrix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class Item(
    val itemId: String,
    val stable: String,
    val ts: Long,
)

private fun apply(current: List<Item>, diff: TimelineDiff<Item>): List<Item> =
    TimelineListReducer.apply(
        current = current,
        diff = diff,
        itemIdOf = { it.itemId },
        stableIdOf = { it.stable },
        timeOf = { it.ts },
        tieOf = { it.stable },
    ).list

class TimelineListReducerTest {

    @Test
    fun appendFollowsSdkOrderNotTimestamps() {
        val first = Item(itemId = "txn1", stable = "t:1", ts = 200)
        val second = Item(itemId = "txn2", stable = "t:2", ts = 150)

        val list = apply(listOf(first), TimelineDiff.Append(listOf(second)))

        assertEquals(listOf("txn1", "txn2"), list.map { it.itemId })
    }

    @Test
    fun appendKeepsSdkOrderForEventsSharingAMillisecond() {
        val first = Item(itemId = "a", stable = "t:zzz", ts = 100)
        val second = Item(itemId = "b", stable = "t:aaa", ts = 100)

        val list = apply(emptyList(), TimelineDiff.Append(listOf(first, second)))

        assertEquals(listOf("a", "b"), list.map { it.itemId })
    }

    @Test
    fun insertUsesTheSdkIndexNotTheTimestamp() {
        val older = Item(itemId = "a", stable = "e:a", ts = 100)
        val newer = Item(itemId = "c", stable = "e:c", ts = 300)
        val inserted = Item(itemId = "b", stable = "e:b", ts = 900)

        val list = apply(
            listOf(older, newer),
            TimelineDiff.Insert(index = 1, itemId = "b", item = inserted),
        )

        assertEquals(listOf("a", "b", "c"), list.map { it.itemId })
    }

    @Test
    fun filteredListFallsBackToTimestampOrder() {
        val a = Item(itemId = "a", stable = "e:a", ts = 100)
        val c = Item(itemId = "c", stable = "e:c", ts = 300)
        val b = Item(itemId = "b", stable = "e:b", ts = 200)

        val list = TimelineListReducer.apply(
            current = listOf(a, c),
            diff = TimelineDiff.Insert(index = 2, itemId = "b", item = b),
            itemIdOf = { it.itemId },
            stableIdOf = { it.stable },
            timeOf = { it.ts },
            tieOf = { it.stable },
            useDiffIndex = false,
        ).list

        assertEquals(listOf("a", "b", "c"), list.map { it.itemId })
    }

    @Test
    fun clearEmptiesTheList() {
        val result = TimelineListReducer.apply(
            current = listOf(Item(itemId = "a", stable = "e:a", ts = 100)),
            diff = TimelineDiff.Clear(),
            itemIdOf = { it.itemId },
            stableIdOf = { it.stable },
            timeOf = { it.ts },
            tieOf = { it.stable },
        )

        assertEquals(emptyList(), result.list)
        assertTrue(result.cleared)
    }

    @Test
    fun echoedLocalEchoKeepsItsPosition() {
        val pending = Item(itemId = "txn1", stable = "t:1", ts = 200)
        val next = Item(itemId = "txn2", stable = "t:2", ts = 210)
        val list = apply(listOf(pending), TimelineDiff.Append(listOf(next)))

        val echoed = Item(itemId = "\$1", stable = "t:1", ts = 100)
        val updated = apply(list, TimelineDiff.UpdateByItemId("txn1", echoed))

        assertEquals(listOf("\$1", "txn2"), updated.map { it.itemId })
    }

    @Test
    fun prependGoesToStartNotByTimestamp() {
        val existing = Item(itemId = "a", stable = "e:a", ts = 100)
        val older = Item(itemId = "b", stable = "e:b", ts = 999)

        val list = apply(listOf(existing), TimelineDiff.Prepend(older))

        assertEquals(listOf("b", "a"), list.map { it.itemId })
    }
}
