package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import mages.shared.generated.resources.*
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.DirectoryUser
import org.mlm.mages.matrix.PublicRoom
import org.mlm.mages.matrix.PublicRoomTypeFilter
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.RoomPreview
import org.mlm.mages.matrix.RoomPreviewMembership
import org.mlm.mages.ui.util.decodeUrl
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
    val roomTypeFilter: PublicRoomTypeFilter? = null,
    val nextBatch: String? = null,
    val directJoinCandidate: String? = null,
    val directJoinVia: List<String> = emptyList(),
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

    init {
        triggerSearch("", debounce = false)
    }

    fun setQuery(q: String) {
        updateState { copy(query = q) }
        triggerSearch(q)
    }

    fun setRoomTypeFilter(filter: PublicRoomTypeFilter?) {
        val nextFilter = if (currentState.roomTypeFilter == filter) null else filter
        updateState {
            copy(
                roomTypeFilter = nextFilter,
                users = emptyList(),
                rooms = emptyList(),
                nextBatch = null,
                error = null
            )
        }
        triggerSearch(currentState.query, debounce = false)
    }

    private fun triggerSearch(q: String, debounce: Boolean = true) {
        searchJob?.cancel()

        searchJob = launch {
            if (debounce) delay(300)
            val term = q.trim()
            val roomTypeFilter = currentState.roomTypeFilter

            val directJoinTarget = normalizeJoinTarget(term)
            val userLookup = normalizeUserLookup(term)
            val directJoinPreview = directJoinTarget?.let { loadDirectJoinPreview(it) }

            updateState {
                copy(
                    isBusy = true,
                    error = null,
                    directJoinCandidate = directJoinTarget?.idOrAlias,
                    directJoinVia = directJoinTarget?.via.orEmpty(),
                    directJoinPreview = null
                )
            }

            val users = if (roomTypeFilter == null && term.isNotBlank()) {
                buildList {
                    if (userLookup != null) {
                        runSafe { service.port.getUserProfile(userLookup) }?.let(::add)
                    }
                    addAll(runSafe { service.port.searchUsers(term, 20) } ?: emptyList())
                }.distinctBy { it.userId }
            } else {
                emptyList()
            }

            val searchTerm = extractSearchTerm(term)

            val page = runSafe {
                service.port.publicRooms(
                    server = null,
                    search = searchTerm,
                    limit = 50,
                    since = null,
                    roomTypeFilter = roomTypeFilter
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
                    since = since,
                    roomTypeFilter = s.roomTypeFilter
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
            val target = room.alias ?: room.roomId
            val rid = joinRoom(target).getOrNull()
            updateState { copy(isBusy = false) }

            if (rid != null) {
                val joinedSpaceName = spaceName(rid, room.name ?: target)
                if (joinedSpaceName != null) {
                    _events.send(Event.OpenSpace(rid, joinedSpaceName))
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
            val target = room.alias ?: room.roomId
            val knockSuccess = runSafe { service.port.knock(target, roomVia(target, emptyList())) }?.isSuccess ?: false
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
            val via = currentState.directJoinVia
            val result = when (preview?.action) {
                DirectJoinAction.Knock -> knockAndResolve(idOrAlias, via)
                DirectJoinAction.None -> Result.failure(IllegalStateException(preview.subtitle))
                else -> joinRoom(idOrAlias, via)
            }
            updateState { copy(isBusy = false) }

            val rid = result.getOrNull()
            if (rid != null) {
                val spaceName = spaceName(rid, preview?.title ?: idOrAlias)
                if (spaceName != null) {
                    _events.send(Event.OpenSpace(rid, spaceName))
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

    private suspend fun spaceName(roomId: String, fallback: String): String? {
        runSafe { service.mySpaces() }
            ?.firstOrNull { it.roomId == roomId }
            ?.let { return it.name }
        return fallback.takeIf { runSafe { service.isSpace(roomId) } == true }
    }

    private suspend fun loadDirectJoinPreview(target: JoinTarget): DirectJoinPreview {
        val result = service.port.roomPreview(target.idOrAlias, roomVia(target.idOrAlias, target.via))
        return result.fold(
            onSuccess = { preview -> preview.toDirectJoinPreview(target.idOrAlias) },
            onFailure = { error ->
                DirectJoinPreview(
                    target = target.idOrAlias,
                    title = target.idOrAlias,
                    subtitle = error.message ?: getString(Res.string.room_not_found),
                    action = DirectJoinAction.None,
                    actionLabel = getString(Res.string.unavailable)
                )
            }
        )
    }

    private suspend fun knockAndResolve(idOrAlias: String, via: List<String>): Result<String> {
        val knockSuccess = runSafe {
            service.port.knock(idOrAlias, roomVia(idOrAlias, via))
        }?.isSuccess ?: false
        return if (knockSuccess) {
            Result.failure(IllegalStateException(getString(Res.string.knock_request_sent_waiting_for_approval)))
        } else {
            Result.failure(IllegalStateException(getString(Res.string.failed_to_knock_on_room)))
        }
    }

    private suspend fun roomVia(idOrAlias: String, linkVia: List<String>): List<String> {
        val targetServer = idOrAlias.substringAfter(':', "").takeIf { it.isNotBlank() }
        return (linkVia + listOfNotNull(targetServer) + viaServers()).distinct()
    }

    private suspend fun viaServers(): List<String> =
        runSafe { service.mySpaces() }
            .orEmpty()
            .mapNotNull { space ->
                space.roomId.substringAfter(':', "").takeIf { it.isNotBlank() }
            }
            .distinct()

    private suspend fun joinRoom(idOrAlias: String, linkVia: List<String> = emptyList()): Result<String> {
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

        val joinResult = service.port.joinByIdOrAlias(idOrAlias, roomVia(idOrAlias, linkVia))
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

    private fun normalizeJoinTarget(input: String): JoinTarget? {
        val t = input.trim()
        if (t.isBlank()) return null

        val value = when {
            t.startsWith("https://matrix.to/#/") ->
                t.removePrefix("https://matrix.to/#/")

            t.contains("app.element.io/#/room/") ->
                t.substringAfter("app.element.io/#/room/")

            t.startsWith("matrix:", ignoreCase = true) -> {
                val body = t.substringAfter(':').trimStart('/')
                when {
                    body.startsWith("r/") -> body.removePrefix("r/")
                    body.startsWith("room/") -> body.removePrefix("room/")
                    body.startsWith("roomid/") -> body.removePrefix("roomid/")
                    else -> return null
                }
            }

            else -> t
        }

        val pathAndQuery = value.split('?', limit = 2)
        val id = decodeUrl(pathAndQuery[0].trim().substringBefore('/'))
        val via = pathAndQuery.getOrNull(1)?.let(::parseViaQuery).orEmpty()

        return id.takeIf { (it.startsWith("#") || it.startsWith("!")) && it.contains(":") }
            ?.let { JoinTarget(it, via) }
    }

    private fun parseViaQuery(query: String): List<String> =
        query.split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2 && kv[0] == "via") decodeUrl(kv[1]) else null
        }
}

private data class JoinTarget(
    val idOrAlias: String,
    val via: List<String>,
)
