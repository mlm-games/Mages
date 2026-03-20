package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.DirectoryUser
import org.mlm.mages.matrix.PublicRoom
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.RoomPreview
import org.mlm.mages.matrix.RoomPreviewMembership

enum class DirectJoinAction {
    Join,
    Knock,
    None
}

data class DirectJoinPreview(
    val target: String,
    val title: String,
    val subtitle: String,
    val action: DirectJoinAction,
    val actionLabel: String,
)

data class DiscoverUi(
    val query: String = "",
    val users: List<DirectoryUser> = emptyList(),
    val rooms: List<PublicRoom> = emptyList(),
    val nextBatch: String? = null,
    val directJoinCandidate: String? = null,
    val directJoinPreview: DirectJoinPreview? = null,

    val isBusy: Boolean = false,
    val isPaging: Boolean = false,
    val error: String? = null
)

class DiscoverViewModel(
    private val service: MatrixService
) : BaseViewModel<DiscoverUi>(DiscoverUi()) {

    sealed class Event {
        data class OpenRoom(val roomId: String, val name: String) : Event()
        data class ShowError(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var searchJob: Job? = null

    fun setQuery(q: String) {
        updateState { copy(query = q) }
        triggerSearch(q)
    }

    private fun triggerSearch(q: String) {
        searchJob?.cancel()

        searchJob = launch {
            delay(300)
            val term = q.trim()

            if (term.isBlank()) {
                updateState {
                    copy(
                        users = emptyList(),
                        rooms = emptyList(),
                        nextBatch = null,
                        directJoinCandidate = null,
                        directJoinPreview = null,
                        error = null,
                        isBusy = false
                    )
                }
                return@launch
            }

            val directJoinTarget = normalizeJoinTarget(term)
            val userLookup = normalizeUserLookup(term)
            val directJoinPreview = directJoinTarget?.let { loadDirectJoinPreview(it) }

            updateState {
                copy(
                    isBusy = true,
                    error = null,
                    directJoinCandidate = directJoinTarget,
                    directJoinPreview = null
                )
            }

            val users = if (userLookup != null) {
                val profile = runSafe { service.port.getUserProfile(userLookup) }
                if (profile != null) listOf(profile) else emptyList()
            } else {
                emptyList()
            }

            val searchTerm = extractSearchTerm(term)

            val page = runSafe {
                service.port.publicRooms(
                    server = null,
                    search = searchTerm,
                    limit = 50,
                    since = null
                )
            }

            updateState {
                copy(
                    users = users,
                    rooms = page?.rooms ?: emptyList(),
                    nextBatch = page?.nextBatch,
                    directJoinPreview = directJoinPreview,
                    isBusy = false
                )
            }
        }
    }

    private fun normalizeUserLookup(input: String): String? {
        val t = input.trim()
        if (t.isBlank()) return null

        return when {
            t.startsWith("@") && t.contains(":") -> t
            else -> null
        }
    }

    private fun extractSearchTerm(term: String): String {
        return when {
            term.startsWith("#") && term.contains(":") -> {
                term.substringAfter('#').substringBefore(':')
            }
            term.startsWith("#") -> term.substringAfter('#')
            else -> term
        }
    }

    fun loadMoreRooms() {
        val s = state.value
        val term = s.query.trim()
        val since = s.nextBatch ?: return

        searchJob?.cancel()
        searchJob = launch {
            updateState { copy(isPaging = true, error = null) }

            val searchTerm = extractSearchTerm(term)

            val page = runSafe {
                service.port.publicRooms(
                    server = null,
                    search = searchTerm,
                    limit = 50,
                    since = since
                )
            }

            updateState {
                copy(
                    rooms = rooms + (page?.rooms ?: emptyList()),
                    nextBatch = page?.nextBatch,
                    isPaging = false
                )
            }
        }
    }

    fun openUser(u: DirectoryUser) {
        launch {
            updateState { copy(isBusy = true) }
            val rid = runSafe { service.port.ensureDm(u.userId) }
            updateState { copy(isBusy = false) }

            if (rid != null) {
                _events.send(Event.OpenRoom(rid, u.displayName ?: u.userId))
            } else {
                _events.send(Event.ShowError("Failed to start conversation"))
            }
        }
    }

    fun openRoom(room: PublicRoom) {
        launch {
            updateState { copy(isBusy = true, error = null) }
            val rid = joinRoom(room.alias ?: room.roomId).getOrNull()
            updateState { copy(isBusy = false) }

            if (rid != null) {
                _events.send(
                    Event.OpenRoom(
                        rid,
                        room.name ?: room.alias ?: room.roomId
                    )
                )
            } else {
                _events.send(Event.ShowError("Failed to join room"))
            }
        }
    }

    fun knockRoom(room: PublicRoom) {
        launch {
            updateState { copy(isBusy = true, error = null) }
            val knockSuccess = runSafe { service.port.knock(room.alias ?: room.roomId) }?.isSuccess ?: false
            updateState { copy(isBusy = false) }

            if (knockSuccess) {
                _events.send(Event.ShowError("Knock request sent. Waiting for approval."))
            } else {
                _events.send(Event.ShowError("Failed to knock on room"))
            }
        }
    }

    fun joinDirect(idOrAlias: String) {
        launch {
            updateState { copy(isBusy = true, error = null) }
            val preview = currentState.directJoinPreview
            val result = when (preview?.action) {
                DirectJoinAction.Knock -> knockAndResolve(idOrAlias)
                DirectJoinAction.None -> Result.failure(IllegalStateException(preview.subtitle))
                else -> joinRoom(idOrAlias)
            }
            updateState { copy(isBusy = false) }

            result.fold(
                onSuccess = { rid ->
                    _events.send(Event.OpenRoom(rid, idOrAlias))
                },
                onFailure = { error ->
                    _events.send(Event.ShowError(error.message ?: "Failed to join $idOrAlias"))
                }
            )
        }
    }

    private suspend fun loadDirectJoinPreview(idOrAlias: String): DirectJoinPreview {
        val result = service.port.roomPreview(idOrAlias)
        return result.fold(
            onSuccess = { preview -> preview.toDirectJoinPreview(idOrAlias) },
            onFailure = { error ->
                DirectJoinPreview(
                    target = idOrAlias,
                    title = idOrAlias,
                    subtitle = error.message ?: "Room not found",
                    action = DirectJoinAction.None,
                    actionLabel = "Unavailable"
                )
            }
        )
    }

    private suspend fun knockAndResolve(idOrAlias: String): Result<String> {
        val knockSuccess = runSafe { service.port.knock(idOrAlias) }?.isSuccess ?: false
        return if (knockSuccess) {
            Result.failure(IllegalStateException("Knock request sent. Waiting for approval."))
        } else {
            Result.failure(IllegalStateException("Failed to knock on room"))
        }
    }

    private suspend fun joinRoom(idOrAlias: String): Result<String> {
        if (!looksLikeRoomIdOrAlias(idOrAlias)) {
            return Result.failure(IllegalArgumentException("Enter a full room alias like #room:server or room ID like !id:server"))
        }

        if (idOrAlias.startsWith("!")) {
            val rooms = runSafe { service.port.listRooms() } ?: emptyList()
            if (rooms.any { it.id == idOrAlias }) {
                return Result.success(idOrAlias)
            }
        }

        // For aliases, try to resolve first to check if already joined
        if (idOrAlias.startsWith("#")) {
            val resolvedId = runSafe { service.port.resolveRoomId(idOrAlias) }
            if (resolvedId != null && resolvedId.startsWith("!")) {
                val rooms = runSafe { service.port.listRooms() } ?: emptyList()
                if (rooms.any { it.id == resolvedId }) {
                    return Result.success(resolvedId)
                }
            }
        }

        val joinResult = service.port.joinByIdOrAlias(idOrAlias)
        if (joinResult.isFailure) {
            return Result.failure(joinResult.exceptionOrNull() ?: IllegalStateException("Failed to join room"))
        }

        // After successful join, resolve the room ID
        return when {
            idOrAlias.startsWith("!") -> Result.success(idOrAlias)
            idOrAlias.startsWith("#") -> {
                // Give the server a moment to process
                delay(100)
                val resolved = runSafe { service.port.resolveRoomId(idOrAlias) }
                if (resolved != null) Result.success(resolved)
                else Result.failure(IllegalStateException("Joined room, but could not resolve its room ID yet"))
            }
            else -> Result.failure(IllegalStateException("Joined room, but could not determine room ID"))
        }
    }

    private fun looksLikeRoomIdOrAlias(value: String): Boolean {
        val trimmed = value.trim()
        return (trimmed.startsWith("#") || trimmed.startsWith("!")) && trimmed.contains(":")
    }

    private fun RoomPreview.toDirectJoinPreview(target: String): DirectJoinPreview {
        val title = name ?: canonicalAlias ?: roomId
        val subtitle = when (membership) {
            RoomPreviewMembership.Joined -> "Already joined"
            RoomPreviewMembership.Invited -> "You are invited to this room"
            RoomPreviewMembership.Knocked -> "Already requested access"
            RoomPreviewMembership.Banned -> "You are banned from this room"
            else -> when (joinRule) {
                RoomJoinRule.Public -> "Anyone can join"
                RoomJoinRule.Knock, RoomJoinRule.KnockRestricted -> "Knock required before joining"
                RoomJoinRule.Restricted -> "Restricted room membership"
                RoomJoinRule.Invite -> "Invite only"
                null -> "Join rule unavailable"
            }
        }

        val action = when (membership) {
            RoomPreviewMembership.Joined -> DirectJoinAction.Join
            RoomPreviewMembership.Knocked,
            RoomPreviewMembership.Banned,
            RoomPreviewMembership.Invited -> DirectJoinAction.None
            else -> when (joinRule) {
                RoomJoinRule.Public -> DirectJoinAction.Join
                RoomJoinRule.Knock, RoomJoinRule.KnockRestricted -> DirectJoinAction.Knock
                RoomJoinRule.Restricted,
                RoomJoinRule.Invite,
                null -> DirectJoinAction.None
            }
        }

        val actionLabel = when (action) {
            DirectJoinAction.Join -> "Join"
            DirectJoinAction.Knock -> "Knock"
            DirectJoinAction.None -> "Unavailable"
        }

        return DirectJoinPreview(
            target = target,
            title = title,
            subtitle = subtitle,
            action = action,
            actionLabel = actionLabel,
        )
    }

    private fun normalizeJoinTarget(input: String): String? {
        val t = input.trim()
        if (t.isBlank()) return null

        if (t.startsWith("https://matrix.to/#/")) {
            val after = t.removePrefix("https://matrix.to/#/")
                .trim()
                .substringBefore('?')
            return after.takeIf { it.startsWith("#") || it.startsWith("!") }
        }

        // element.io links
        if (t.contains("app.element.io/#/room/")) {
            val after = t.substringAfter("app.element.io/#/room/")
                .trim()
                .substringBefore('?')
            return after.takeIf { it.startsWith("#") || it.startsWith("!") }
        }

        return when {
            t.startsWith("#") && t.contains(":") -> t
            t.startsWith("!") && t.contains(":") -> t
            else -> null
        }
    }
}
