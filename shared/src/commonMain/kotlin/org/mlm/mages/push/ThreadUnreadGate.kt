package org.mlm.mages.push

import org.mlm.mages.MatrixService

/**
 * Cross-device read gate for thread notifications.
 *
 * [org.mlm.mages.settings.ThreadReadMarks] only records reads made on this
 * device, so a thread read elsewhere keeps notifying. The event cache knows the
 * receipt-based counts instead: a thread it knows about with nothing left to
 * count is read. A thread it does not know proves nothing, so it is never
 * treated as read.
 */
object ThreadUnreadGate {
    suspend fun isRead(service: MatrixService, roomId: String, threadId: String): Boolean {
        if (roomId.isBlank() || threadId.isBlank()) return false
        val counts = service.portOrNull?.threadUnreadCounts(roomId)?.getOrNull() ?: return false
        val entry = counts.firstOrNull { it.threadId == threadId } ?: return false
        return entry.unreadMessages == 0uL &&
            entry.unreadNotifications == 0uL &&
            entry.unreadMentions == 0uL
    }
}