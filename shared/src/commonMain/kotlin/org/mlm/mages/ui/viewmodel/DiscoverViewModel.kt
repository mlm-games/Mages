package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import mages.shared.generated.resources.*
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.DirectoryUser
import org.mlm.mages.matrix.PublicRoom
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.RoomPreview
import org.mlm.mages.matrix.RoomPreviewMembership
import org.mlm.mages.matrix.SpaceInfo
import org.jetbrains.compose.resources.getString
import mages.shared.generated.resources.Res

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
        data class OpenSpace(val spaceId: String, val name: String) : Event()
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
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

            val users = buildList {
                if (userLookup != null) {
                    runSafe { service.port.getUserProfile(userLookup) }?.let(::add)
                }
                addAll(runSafe { service.port.searchUsers(term, 20) } ?: emptyList())
            }.distinctBy { it.userId }

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
                _events.send(Event.ShowError(getString(Res.string.failed_to_start_conversation)))
            }
        }
    }

    fun openRoom(room: PublicRoom) {
        launch {
            updateState { copy(isBusy = true, error = null) }
            val rid = joinRoom(room.alias ?: room.roomId).getOrNull()
            updateState { copy(isBusy = false) }

            if (rid != null) {
                val space = joinedSpace(rid)
                if (space != null) {
                    _events.send(Event.OpenSpace(space.roomId, space.name))
                } else {
                    _events.send(
                        Event.OpenRoom(
                            rid,
                            room.name ?: room.alias ?: room.roomId
                        )
                    )
                }
            } else {
                _events.send(Event.ShowError(getString(Res.string.failed_to_join_room)))
            }
        }
    }

    fun knockRoom(room: PublicRoom) {
        launch {
            updateState { copy(isBusy = true, error = null) }
            val knockSuccess = runSafe { service.port.knock(room.alias ?: room.roomId) }?.isSuccess ?: false
            updateState { copy(isBusy = false) }

            if (knockSuccess) {
                _events.send(Event.ShowSuccess(getString(Res.string.knock_request_sent_waiting_for_approval)))
            } else {
                _events.send(Event.ShowError(getString(Res.string.could_not_knock_on_the_room_try_again)))
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

            val rid = result.getOrNull()
            if (rid != null) {
                val space = joinedSpace(rid)
                if (space != null) {
                    _events.send(Event.OpenSpace(space.roomId, space.name))
                } else {
                    _events.send(Event.OpenRoom(rid, idOrAlias))
                }
            } else {
                _events.send(
                    Event.ShowError(
                        result.exceptionOrNull()?.message
                            ?: getString(Res.string.failed_to_join_named, idOrAlias)
                    )
                )
            }
        }
    }

    private suspend fun joinedSpace(roomId: String): SpaceInfo? =
        runSafe { service.mySpaces() }?.firstOrNull { it.roomId == roomId }

    private suspend fun loadDirectJoinPreview(idOrAlias: String): DirectJoinPreview {
        val result = service.port.roomPreview(idOrAlias, viaServers())
        return result.fold(
            onSuccess = { preview -> preview.toDirectJoinPreview(idOrAlias) },
            onFailure = { error ->
                DirectJoinPreview(
                    target = idOrAlias,
                    title = idOrAlias,
                    subtitle = error.message ?: getString(Res.string.room_not_found),
                    action = DirectJoinAction.None,
                    actionLabel = getString(Res.string.unavailable)
                )
            }
        )
    }

    private suspend fun knockAndResolve(idOrAlias: String): Result<String> {
        val knockSuccess = runSafe { service.port.knock(idOrAlias, viaServers()) }?.isSuccess ?: false
        return if (knockSuccess) {
            Result.failure(IllegalStateException(getString(Res.string.knock_request_sent_waiting_for_approval)))
        } else {
            Result.failure(IllegalStateException(getString(Res.string.failed_to_knock_on_room)))
        }
    }

    private suspend fun viaServers(): List<String> =
        runSafe { service.mySpaces() }
            .orEmpty()
            .mapNotNull { space ->
                space.roomId.substringAfter(':', "").takeIf { it.isNotBlank() }
            }
            .distinct()

    private suspend fun joinRoom(idOrAlias: String): Result<String> {
        if (!looksLikeRoomIdOrAlias(idOrAlias)) {
            return Result.failure(IllegalArgumentException(getString(Res.string.enter_a_full_room_alias_like_room_server_or_room_id_like_id_server)))
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

        val joinResult = service.port.joinByIdOrAlias(idOrAlias, viaServers())
        if (joinResult.isFailure) {
            return Result.failure(joinResult.exceptionOrNull() ?: IllegalStateException(getString(Res.string.failed_to_join_room)))
        }

        // After successful join, resolve the room ID
        return when {
            idOrAlias.startsWith("!") -> Result.success(idOrAlias)
            idOrAlias.startsWith("#") -> {
                // Give the server a moment to process
                delay(100)
                val resolved = runSafe { service.port.resolveRoomId(idOrAlias) }
                if (resolved != null) Result.success(resolved)
                else Result.failure(IllegalStateException(getString(Res.string.joined_room_but_could_not_resolve_its_room_id_yet)))
            }
            else -> Result.failure(IllegalStateException(getString(Res.string.joined_room_but_could_not_determine_room_id)))
        }
    }

    private fun looksLikeRoomIdOrAlias(value: String): Boolean {
        val trimmed = value.trim()
        return (trimmed.startsWith("#") || trimmed.startsWith("!")) && trimmed.contains(":")
    }

    private suspend fun RoomPreview.toDirectJoinPreview(target: String): DirectJoinPreview {
        val title = name ?: canonicalAlias ?: roomId
        val subtitle = when (membership) {
            RoomPreviewMembership.Joined -> getString(Res.string.already_joined)
            RoomPreviewMembership.Invited -> getString(Res.string.you_are_invited_to_this_room)
            RoomPreviewMembership.Knocked -> getString(Res.string.already_requested_access)
            RoomPreviewMembership.Banned -> getString(Res.string.you_are_banned_from_this_room)
            else -> when (joinRule) {
                RoomJoinRule.Public -> getString(Res.string.anyone_can_join)
                RoomJoinRule.Knock, RoomJoinRule.KnockRestricted -> getString(Res.string.knock_required_before_joining)
                RoomJoinRule.Restricted -> getString(Res.string.restricted_room_membership)
                RoomJoinRule.Invite -> getString(Res.string.invite_only)
                null -> getString(Res.string.join_rule_unavailable)
            }
        }

        val action = when (membership) {
            RoomPreviewMembership.Joined,
            RoomPreviewMembership.Invited -> DirectJoinAction.Join
            RoomPreviewMembership.Knocked,
            RoomPreviewMembership.Banned -> DirectJoinAction.None
            else -> when (joinRule) {
                RoomJoinRule.Public,
                RoomJoinRule.Restricted -> DirectJoinAction.Join
                RoomJoinRule.Knock, RoomJoinRule.KnockRestricted -> DirectJoinAction.Knock
                RoomJoinRule.Invite,
                null -> DirectJoinAction.None
            }
        }

        val actionLabel = when (action) {
            DirectJoinAction.Join -> getString(Res.string.join)
            DirectJoinAction.Knock -> getString(Res.string.knock)
            DirectJoinAction.None -> getString(Res.string.unavailable)
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
