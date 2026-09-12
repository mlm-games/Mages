package org.mlm.mages.calls

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.NotificationKind
import org.mlm.mages.matrix.RenderedNotification
import org.mlm.mages.nav.DeepLinkAction
import kotlin.time.Clock

data class IncomingCall(
    val roomId: String,
    val eventId: String,
    val callerName: String,
    val roomName: String,
    val expiresAtMs: Long,
    val tsMs: Long,
) {
    companion object
}

const val INCOMING_CALL_TIMEOUT_MS = 60_000L

/**
 * Grace delay before dismissing a ringing invite whose room stops looking
 * active. RTC membership state can flap on inconsistent sync responses, so an
 * active → inactive transition only dismisses if the room is still inactive
 * after this delay.
 */
const val CALL_END_GRACE_MS = 5_000L

fun NotificationKind.isRingingCall(): Boolean = when (this) {
    NotificationKind.CallRing,
    NotificationKind.CallInvite,
    NotificationKind.CallNotify -> true
    else -> false
}

fun RenderedNotification.isExpired(nowMs: Long = Clock.System.now().toEpochMilliseconds()): Boolean =
    expiresAtMs != null && nowMs > expiresAtMs

fun formatCallElapsed(elapsedMs: Long): String {
    val totalSec = (elapsedMs.coerceAtLeast(0L) / 1000L)
    val secs = (totalSec % 60).toString().padStart(2, '0')
    return "${totalSec / 60}:$secs"
}

class IncomingCallTracker {
    private val _invites = MutableStateFlow<List<IncomingCall>>(emptyList())
    val invites: StateFlow<List<IncomingCall>> = _invites.asStateFlow()

    private val _dismissed = MutableSharedFlow<IncomingCall>(extraBufferCapacity = 16)
    val dismissed: SharedFlow<IncomingCall> = _dismissed.asSharedFlow()

    fun report(call: IncomingCall) {
        prune(call.tsMs)
        _invites.value = (_invites.value.filterNot {
            it.roomId == call.roomId && it.eventId == call.eventId
        } + call)
            .sortedByDescending { it.tsMs }
            .take(5)
    }

    /** Returns the removed invite, if any (emitted on [dismissed] for OS cleanup). */
    fun dismiss(roomId: String, eventId: String? = null): IncomingCall? {
        val removed = _invites.value.filter {
            it.roomId == roomId && (eventId == null || it.eventId == eventId)
        }
        if (removed.isEmpty()) return null
        _invites.value = _invites.value - removed.toSet()
        removed.forEach { _dismissed.tryEmit(it) }
        return removed.first()
    }

    fun clearForRoom(roomId: String) {
        dismiss(roomId)
    }

    /** Drops expired invites; returns them so platforms can retract OS notifs. */
    fun prune(nowMs: Long = Clock.System.now().toEpochMilliseconds()): List<IncomingCall> {
        val expired = _invites.value.filter { it.expiresAtMs <= nowMs }
        if (expired.isEmpty()) return emptyList()
        _invites.value = _invites.value - expired.toSet()
        expired.forEach { _dismissed.tryEmit(it) }
        return expired
    }
}

fun IncomingCall.Companion.ringing(n: RenderedNotification): IncomingCall = IncomingCall(
    roomId = n.roomId,
    eventId = n.eventId,
    callerName = n.sender,
    roomName = n.roomName,
    expiresAtMs = n.expiresAtMs ?: (n.tsMs + INCOMING_CALL_TIMEOUT_MS),
    tsMs = n.tsMs
)

fun answerIncomingCall(
    tracker: IncomingCallTracker,
    roomId: String,
    eventId: String,
    onJoin: (DeepLinkAction) -> Unit,
) {
    tracker.dismiss(roomId, eventId)
    onJoin(DeepLinkAction(roomId = roomId, eventId = eventId, joinCall = true))
}

fun answerIncomingCall(
    tracker: IncomingCallTracker,
    call: IncomingCall,
    onJoin: (DeepLinkAction) -> Unit,
) = answerIncomingCall(tracker, call.roomId, call.eventId, onJoin)

/** Shared Decline path: hang up server-side, then drop the invite. */
suspend fun declineIncomingCall(
    port: MatrixPort?,
    tracker: IncomingCallTracker,
    roomId: String,
    eventId: String,
) {
    runCatching { port?.declineCall(roomId, eventId) }
    tracker.dismiss(roomId, eventId)
}

suspend fun declineIncomingCall(
    port: MatrixPort?,
    tracker: IncomingCallTracker,
    call: IncomingCall,
) = declineIncomingCall(port, tracker, call.roomId, call.eventId)
