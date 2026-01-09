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
        idOf: (T) -> String,
        timeOf: (T) -> Long,
        tieOf: (T) -> String,
    ): Result<T> {
        return when (diff) {
            is TimelineDiff.Reset -> {
                // Dedup by id but preserve Reset order
                val seen = HashSet<String>(diff.items.size)
                val deduped = ArrayList<T>(diff.items.size)
                val delta = ArrayList<T>(diff.items.size)
                for (it in diff.items) {
                    val id = idOf(it)
                    if (seen.add(id)) {
                        deduped.add(it)
                        delta.add(it)
                    }
                }
                Result(list = deduped, delta = delta, reset = true)
            }

            is TimelineDiff.Clear -> Result(list = emptyList(), cleared = true)

            is TimelineDiff.Append -> {
                val list = current.toMutableList()
                val delta = ArrayList<T>(diff.items.size)

                for (item in diff.items) {
                    val id = idOf(item)
                    val idx = list.indexOfFirst { idOf(it) == id }
                    if (idx >= 0) {
                        list[idx] = item
                    } else {
                        list.insertSorted(item, timeOf, tieOf)
                    }
                    delta.add(item)
                }
                Result(list = list, delta = delta)
            }

            is TimelineDiff.Prepend -> {
                val item = diff.item
                val list = current.toMutableList()
                val id = idOf(item)
                val idx = list.indexOfFirst { idOf(it) == id }
                if (idx >= 0) list[idx] = item else list.insertSorted(item, timeOf, tieOf)
                Result(list = list, delta = listOf(item))
            }

            is TimelineDiff.UpdateByItemId -> {
                val idx = current.indexOfFirst { idOf(it) == diff.itemId }
                if (idx < 0) {
                    // If we didn't have it, treat as upsert (append) to avoid losing updates
                    Result(list = current + diff.item, delta = listOf(diff.item))
                } else {
                    val list = current.toMutableList().apply { this[idx] = diff.item }
                    Result(list = list, delta = listOf(diff.item))
                }
            }

            is TimelineDiff.UpsertByItemId -> {
                val list = current.toMutableList()
                val idx = list.indexOfFirst { idOf(it) == diff.itemId }
                if (idx >= 0) {
                    list[idx] = diff.item
                } else {
                    list.insertSorted(diff.item, timeOf, tieOf)
                }
                Result(list = list, delta = listOf(diff.item))
            }

            is TimelineDiff.RemoveByItemId -> {
                val list = current.filter { idOf(it) != diff.itemId }
                Result(list = list)
            }
        }
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