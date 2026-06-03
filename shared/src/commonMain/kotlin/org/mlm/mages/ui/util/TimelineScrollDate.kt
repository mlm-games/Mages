package org.mlm.mages.ui.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.mlm.mages.MessageEvent

@Composable
fun rememberTopVisibleRoomEventTimestamp(
    listState: LazyListState,
    events: List<MessageEvent>,
): Long? {
    val timestamp by remember(listState, events) {
        derivedStateOf {
            if (events.isEmpty()) return@derivedStateOf null

            for (i in listState.layoutInfo.visibleItemsInfo.lastIndex downTo 0) {
                val visibleItem = listState.layoutInfo.visibleItemsInfo[i]
                val dslIndex = visibleItem.index
                if (dslIndex < events.size) {
                    val eventIndex = events.lastIndex - dslIndex
                    return@derivedStateOf events[eventIndex].timestampMs
                }
            }
            null
        }
    }
    return timestamp
}