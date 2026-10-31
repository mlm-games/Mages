package org.mlm.mages.ui.util

import org.mlm.mages.MessageEvent

private const val GROUP_WINDOW_MS = 5 * 60 * 1000L

fun isSameMessageGroup(first: MessageEvent, second: MessageEvent): Boolean =
    first.sender == second.sender &&
        formatDate(first.timestampMs) == formatDate(second.timestampMs) &&
        second.timestampMs - first.timestampMs < GROUP_WINDOW_MS
