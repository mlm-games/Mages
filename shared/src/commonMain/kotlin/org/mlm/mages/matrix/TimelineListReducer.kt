package org.mlm.mages.matrix

/**
 * Applies timeline diffs to a list keyed by stable itemId (TimelineItem.unique_id()).
 *
 * Important: This does not, and should not try to "sort by timestamp" for upserts.
 * Ordering is preserved by:
 *  - Append -> end
 *  - Prepend -> start
 *  - Reset -> exact order given
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
    ): Result<T> {
        return when (diff) {

            is TimelineDiff.Reset -> {
                val byStable = LinkedHashMap<String, T>(diff.items.size)
                val delta = ArrayList<T>(diff.items.size)

                for (it in diff.items) {
                    val sid = stableIdOf(it)
                    byStable[sid] = it
                    delta.add(it)
                }

                Result(
                    list = byStable.values.toList(),
                    delta = byStable.values.toList(),
                    reset = true
                )
            }

            is TimelineDiff.Clear ->
                // should never receive this, but if it does, don’t nuke user-visible history.
                Result(list = current, cleared = false)

            is TimelineDiff.Append -> {
                val list = current.toMutableList()
                val delta = ArrayList<T>(diff.items.size)
                for (item in diff.items) {
                    upsert(list, item, itemIdOf, stableIdOf, timeOf, tieOf)
                    delta.add(item)
                }
                Result(list = list, delta = delta)
            }

            is TimelineDiff.Prepend -> {
                val list = current.toMutableList()
                val item = diff.item
                upsert(list, item, itemIdOf, stableIdOf, timeOf, tieOf)
                Result(list = list, delta = listOf(item))
            }

            is TimelineDiff.UpdateByItemId -> {
                val list = current.toMutableList()
                val item = diff.item
                upsert(list, item, itemIdOf, stableIdOf, timeOf, tieOf)
                Result(list = list, delta = listOf(item))
            }

            is TimelineDiff.UpsertByItemId -> {
                val list = current.toMutableList()
                val item = diff.item
                upsert(list, item, itemIdOf, stableIdOf, timeOf, tieOf)
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
        val incomingItemId = itemIdOf(incoming)
        val incomingStable = stableIdOf(incoming)

        val idxByItem = list.indexOfFirst { itemIdOf(it) == incomingItemId }
        if (idxByItem >= 0) {
            val idxByStable = list.indexOfFirst { stableIdOf(it) == incomingStable }
            if (idxByStable >= 0 && idxByStable != idxByItem) {
                // Keep whichever position is earlier to minimize UI jumps; remove the other.
                val keep = minOf(idxByItem, idxByStable)
                val drop = maxOf(idxByItem, idxByStable)
                list[keep] = incoming
                list.removeAt(drop)
            } else {
                list[idxByItem] = incoming
            }
            return
        }

        val idxByStable = list.indexOfFirst { stableIdOf(it) == incomingStable }
        if (idxByStable >= 0) {
            list[idxByStable] = incoming
            return
        }

        list.insertSorted(incoming, timeOf, tieOf)
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