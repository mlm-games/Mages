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
import org.mlm.mages.matrix.isMatrixUserId
import org.mlm.mages.nav.MatrixLink
import org.mlm.mages.nav.parseMatrixLink
import org.jetbrains.compose.resources.getString
import mages.shared.generated.resources.Res

data class DirectJoinPreview(
    val target: String,
    val title: String,
    val subtitle: String,
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
    val avatarPathByUrl: Map<String, String> = emptyMap(),

    val isBusy: Boolean = false,
    val isPaging: Boolean = false,
    val error: String? = null
)

class DiscoverViewModel(
    private val service: MatrixService
) : BaseViewModel<DiscoverUi>(DiscoverUi()) {

    sealed class Event {
        data class OpenRoom(val roomId: String, val name: String) : Event()
        data class OpenJoinPreview(
            val roomIdOrAlias: String,
            val via: List<String>,
            val isSpace: Boolean? = null,
        ) : Event()

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
            resolveAvatars(users, page?.rooms.orEmpty())
        }
    }

    private fun resolveAvatars(users: List<DirectoryUser>, rooms: List<PublicRoom>) {
        val urls = (users.mapNotNull { it.avatarUrl } + rooms.mapNotNull { it.avatarUrl })
            .filter { it.startsWith("mxc://") }
            .distinct()

        urls.forEach { url ->
            if (currentState.avatarPathByUrl.containsKey(url)) return@forEach
            resolveAvatar(service, url, px = 96) { path ->
                copy(avatarPathByUrl = avatarPathByUrl + (url to path))
            }
        }
    }

    private fun normalizeUserLookup(input: String): String? =
        input.trim().takeIf(::isMatrixUserId)

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
            resolveAvatars(emptyList(), page?.rooms.orEmpty())
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

    fun openJoinPreview(room: PublicRoom) {
        launch {
            val target = room.alias ?: room.roomId
            _events.send(Event.OpenJoinPreview(target, roomVia(target, emptyList()), room.isSpace))
        }
    }

    fun openJoinPreview(aliasOrId: String) {
        launch {
            _events.send(
                Event.OpenJoinPreview(
                    aliasOrId,
                    roomVia(aliasOrId, currentState.directJoinVia),
                )
            )
        }
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
                )
            }
        )
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

        return DirectJoinPreview(
            target = target,
            title = title,
            subtitle = subtitle,
        )
    }

    private fun normalizeJoinTarget(input: String): JoinTarget? =
        (parseMatrixLink(input) as? MatrixLink.Room)
            ?.let { JoinTarget(it.target.roomIdOrAlias, it.target.via) }
}

private data class JoinTarget(
    val idOrAlias: String,
    val via: List<String>,
)
