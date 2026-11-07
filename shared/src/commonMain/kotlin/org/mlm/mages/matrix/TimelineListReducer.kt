package org.mlm.mages.matrix

/**
 * Applies timeline diffs to a list keyed by stable itemId (TimelineItem.unique_id()).
 *
 * Ordering comes from the SDK, which owns the timeline vector:
 *  - Append (and PushBack) -> end
 *  - Prepend (PushFront) -> start
 *  - Insert -> index
 *  - Reset -> exact order given
 *
 * Pass useDiffIndex = false for a list filtered out of the timeline, where the SDK's
 * positional index no longer applies. Never order confirmed events by timestamp: until
 * the server echoes a local echo back, its timestamp is the device clock, so the value
 * changes under it.
 */
object TimelineListReducer {

    data class Result<T>(
        val list: List<T>,
        val delta: List<T> = emptyList(),
        val cleared: Boolean = false,
        val reset: Boolean = false,
    )

    fun <T> apply(
        current: List<T>,
        diff: TimelineDiff<T>,
        itemIdOf: (T) -> String,
        stableIdOf: (T) -> String,
        timeOf: (T) -> Long,
        tieOf: (T) -> String,
        useDiffIndex: Boolean = true,
    ): Result<T> {
        return when (diff) {

            is TimelineDiff.Reset -> {
                val delta = ArrayList<T>(diff.items.size)
                if (diff.items.isNotEmpty()) {
                    val byItem = LinkedHashMap<String, T>(diff.items.size)
                    for (item in diff.items) {
                        byItem[itemIdOf(item)] = item
                        delta.add(item)
                    }
                    Result(list = byItem.values.toList(), delta = delta, reset = true)
                } else {
                    Result(list = current, delta = delta, reset = true)
                }
            }

            is TimelineDiff.Clear ->
                Result(list = emptyList(), cleared = true)

            is TimelineDiff.Append -> {
                val list = current.toMutableList()
                val delta = ArrayList<T>(diff.items.size)
                for (item in diff.items) {
                    if (existingIndex(list, item, itemIdOf, stableIdOf) < 0) list.add(item)
                    delta.add(item)
                }
                Result(list = list, delta = delta)
            }

            is TimelineDiff.Prepend -> {
                val list = current.toMutableList()
                val item = diff.item
                if (existingIndex(list, item, itemIdOf, stableIdOf) < 0) list.add(0, item)
                Result(list = list, delta = listOf(item))
            }

            is TimelineDiff.UpdateByItemId -> {
                val list = current.toMutableList()
                val item = diff.item
                upsert(list, item, itemIdOf, stableIdOf, timeOf, tieOf)
                Result(list = list, delta = listOf(item))
            }

            is TimelineDiff.Insert -> {
                val list = current.toMutableList()
                val item = diff.item
                val existing = existingIndex(list, item, itemIdOf, stableIdOf)
                when {
                    existing < 0 && useDiffIndex ->
                        list.add(diff.index.coerceIn(0, list.size), item)
                    existing < 0 ->
                        list.insertSorted(item, timeOf, tieOf)
                    useDiffIndex -> {
                        val moved = list.removeAt(existing)
                        list.add(diff.index.coerceIn(0, list.size), moved)
                    }
                    else -> Unit
                }
                Result(list = list, delta = listOf(item))
            }

            is TimelineDiff.RemoveByItemId -> {
                val removed = current.filter { itemIdOf(it) == diff.itemId }
                val list = current.filter { itemIdOf(it) != diff.itemId }
                Result(list = list, delta = removed)
            }
        }
    }

    private fun <T> upsert(
        list: MutableList<T>,
        incoming: T,
        itemIdOf: (T) -> String,
        stableIdOf: (T) -> String,
        timeOf: (T) -> Long,
        tieOf: (T) -> String,
    ) {
        if (existingIndex(list, incoming, itemIdOf, stableIdOf) >= 0) return
        list.insertSorted(incoming, timeOf, tieOf)
    }

    private fun <T> existingIndex(
        list: MutableList<T>,
        incoming: T,
        itemIdOf: (T) -> String,
        stableIdOf: (T) -> String,
    ): Int {
        val incomingItemId = itemIdOf(incoming)
        val incomingStable = stableIdOf(incoming)

        val idxByItem = list.indexOfFirst { itemIdOf(it) == incomingItemId }
        val idxByStable = list.indexOfFirst { stableIdOf(it) == incomingStable }
        if (idxByItem < 0 && idxByStable < 0) return -1

        if (idxByItem >= 0 && idxByStable >= 0 && idxByItem != idxByStable) {
            // Keep whichever position is earlier to minimize UI jumps; remove the other.
            val keep = minOf(idxByItem, idxByStable)
            val drop = maxOf(idxByItem, idxByStable)
            list[keep] = incoming
            list.removeAt(drop)
            return keep
        }
        val idx = if (idxByItem >= 0) idxByItem else idxByStable
        list[idx] = incoming
        return idx
    }
}

private fun <T> compareByKey(
    a: T,
    b: T,
    time: (T) -> Long,
    tie: (T) -> String,
): Int {
    val ta = time(a)
    val tb = time(b)
    return when {
        ta < tb -> -1
        ta > tb -> 1
        else -> tie(a).compareTo(tie(b))
    }
}

fun <T> MutableList<T>.insertSorted(
    item: T,
    time: (T) -> Long,
    tie: (T) -> String,
) {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        val c = compareByKey(this[mid], item, time, tie)
        if (c <= 0) lo = mid + 1 else hi = mid
    }
    add(lo, item)
}
