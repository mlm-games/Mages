package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import mages.shared.generated.resources.*
import mages.shared.generated.resources.Res
import org.jetbrains.compose.resources.getString
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.RoomHero
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.RoomPreviewMembership

data class JoinRoomUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val roomId: String? = null,
    val name: String? = null,
    val alias: String? = null,
    val topic: String? = null,
    val memberCount: Long? = null,
    val avatarPath: String? = null,
    val joinRule: RoomJoinRule? = null,
    val membership: RoomPreviewMembership? = null,
    val heroes: List<RoomHero> = emptyList(),
    val heroAvatarPaths: Map<String, String> = emptyMap(),
    val action: Action = Action.Idle,
    val actionError: String? = null,
    val showDeclineDialog: Boolean = false,
    val declineInviterName: String? = null,
    val isDecliningInvite: Boolean = false,
) {
    enum class Action { Idle, Joining, Knocking, Accepting }
}

class JoinRoomViewModel(
    private val service: MatrixService,
    private val roomIdOrAlias: String,
    private val via: List<String>,
    private val isSpaceHint: Boolean? = null,
) : BaseViewModel<JoinRoomUiState>(JoinRoomUiState()) {

    sealed class Event {
        data class OpenJoined(val roomId: String, val name: String, val isSpace: Boolean) : Event()
        data class Declined(val roomId: String) : Event()
        data class ShowMessage(val message: String) : Event()
        data class ShowError(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        updateState { copy(isLoading = true, error = null) }
        launch(onError = { error ->
            val fallback = getString(Res.string.failed_to_load_room_info)
            updateState { copy(isLoading = false, error = error.message ?: fallback) }
        }) {
            val fallback = getString(Res.string.failed_to_load_room_info)
            val result = runSafe { service.port.roomPreview(roomIdOrAlias, via) }
            if (result == null) {
                updateState { copy(isLoading = false, error = fallback) }
                return@launch
            }
            result.fold(
                onSuccess = { preview ->
                    updateState {
                        copy(
                            isLoading = false,
                            roomId = preview.roomId,
                            name = preview.name,
                            alias = preview.canonicalAlias
                                ?: roomIdOrAlias.takeIf { it.startsWith("#") },
                            topic = preview.topic?.takeIf { it.isNotBlank() },
                            memberCount = preview.memberCount,
                            joinRule = preview.joinRule,
                            membership = preview.membership,
                            heroes = preview.heroes,
                            heroAvatarPaths = emptyMap(),
                        )
                    }
                    preview.avatarUrl?.let { url ->
                        resolveAvatar(service, url, px = 512) { path -> copy(avatarPath = path) }
                    }
                    resolveHeroAvatars(preview.heroes)
                },
                onFailure = { error ->
                    // Preview needs the server to answer; fall back to the local
                    // invite list so invites stay actionable when it is wrong.
                    val invited = runSafe { service.port.listInvited() }
                        ?.firstOrNull { invite ->
                            invite.roomId == roomIdOrAlias ||
                                invite.canonicalAlias == roomIdOrAlias ||
                                invite.altAliases.contains(roomIdOrAlias)
                        }
                    if (invited != null) {
                        updateState {
                            copy(
                                isLoading = false,
                                roomId = invited.roomId,
                                name = invited.name,
                                alias = invited.canonicalAlias
                                    ?: roomIdOrAlias.takeIf { it.startsWith("#") },
                                topic = invited.topic?.takeIf { it.isNotBlank() },
                                memberCount = invited.memberCount.takeIf { it > 0 },
                                joinRule = RoomJoinRule.Invite,
                                membership = RoomPreviewMembership.Invited,
                            )
                        }
                        invited.avatarUrl?.let { url ->
                            resolveAvatar(service, url, px = 512) { path -> copy(avatarPath = path) }
                        }
                    } else {
                        updateState { copy(isLoading = false, error = error.message ?: fallback) }
                    }
                }
            )
        }
    }

    fun join() {
        if (currentState.action != JoinRoomUiState.Action.Idle) return
        updateState { copy(action = JoinRoomUiState.Action.Joining, actionError = null) }
        launch {
            val fallback = getString(Res.string.failed_to_join_named, target())
            val result = service.port.joinByIdOrAlias(target(), via)
            if (result.isSuccess) {
                openJoined()
            } else {
                val message = result.exceptionOrNull()?.message ?: fallback
                updateState {
                    copy(action = JoinRoomUiState.Action.Idle, actionError = message)
                }
            }
        }
    }

    fun knock() {
        if (currentState.action != JoinRoomUiState.Action.Idle) return
        updateState { copy(action = JoinRoomUiState.Action.Knocking, actionError = null) }
        launch {
            val fallback = getString(Res.string.could_not_knock_on_the_room_try_again)
            val result = runSafe { service.port.knock(target(), via) }
            if (result?.isSuccess == true) {
                updateState {
                    copy(action = JoinRoomUiState.Action.Idle, membership = RoomPreviewMembership.Knocked)
                }
                _events.send(Event.ShowMessage(getString(Res.string.knock_request_sent_waiting_for_approval)))
            } else {
                val message = result?.exceptionOrNull()?.message ?: fallback
                updateState {
                    copy(action = JoinRoomUiState.Action.Idle, actionError = message)
                }
            }
        }
    }

    fun acceptInvite() {
        if (currentState.action != JoinRoomUiState.Action.Idle) return
        val roomId = currentState.roomId ?: return
        updateState { copy(action = JoinRoomUiState.Action.Accepting, actionError = null) }
        launch {
            val fallback = getString(Res.string.could_not_accept_the_invite_try_again)
            val result = service.port.acceptInvite(roomId)
            if (result.isSuccess) {
                openJoined()
            } else {
                val message = result.exceptionOrNull()?.message ?: fallback
                updateState {
                    copy(action = JoinRoomUiState.Action.Idle, actionError = message)
                }
            }
        }
    }

    fun showDeclineDialog() {
        val roomId = currentState.roomId ?: return
        if (currentState.showDeclineDialog) return
        updateState { copy(showDeclineDialog = true, declineInviterName = null) }
        launch {
            val inviterId = runSafe { service.port.roomInviter(roomId) }
                ?.takeIf { it.isNotBlank() }
            val name = inviterId?.let { id ->
                runSafe { service.port.getUserProfile(id)?.displayName }?.takeIf { it.isNotBlank() } ?: id
            }
            updateState {
                if (showDeclineDialog) copy(declineInviterName = name) else this
            }
        }
    }

    fun hideDeclineDialog() = updateState {
        copy(showDeclineDialog = false, declineInviterName = null)
    }

    fun declineInvite(blockUser: Boolean, reportRoom: Boolean, reportReason: String) {
        if (currentState.isDecliningInvite) return
        val roomId = currentState.roomId ?: return
        updateState { copy(isDecliningInvite = true, actionError = null) }
        launch {
            val inviterId = if (blockUser) {
                runSafe { service.port.roomInviter(roomId) }
            } else {
                null
            }

            val leaveResult = service.port.leaveRoom(roomId)
            if (leaveResult.isFailure) {
                val fallback = getString(Res.string.could_not_decline_the_invite_try_again)
                updateState { copy(isDecliningInvite = false) }
                _events.send(
                    Event.ShowError(leaveResult.exceptionOrNull()?.message ?: fallback)
                )
                return@launch
            }

            if (!inviterId.isNullOrBlank()) {
                runSafe { service.port.ignoreUser(inviterId) }
            }
            if (reportRoom) {
                runSafe { service.port.reportRoom(roomId, reportReason.ifBlank { null }) }
            }

            updateState {
                copy(isDecliningInvite = false, showDeclineDialog = false, declineInviterName = null)
            }
            _events.send(Event.Declined(roomId))
        }
    }

    fun dismissActionError() = updateState { copy(actionError = null) }

    fun open() = launch { openJoined() }

    private fun target(): String = currentState.roomId ?: roomIdOrAlias

    private suspend fun openJoined() {
        val roomId = target()
        val isSpace = isSpaceHint
            ?: retryUntilPresent(attempts = 4, delayMs = 500) {
                runSafe { service.isSpace(roomId) }
            }
            ?: false
        val name = currentState.name?.takeIf { it.isNotBlank() }
            ?: currentState.alias
            ?: roomId
        updateState { copy(action = JoinRoomUiState.Action.Idle) }
        _events.send(Event.OpenJoined(roomId, name, isSpace))
    }

    private fun resolveHeroAvatars(heroes: List<RoomHero>) {
        val urls = heroes.mapNotNull { it.avatarUrl }
            .filter { it.startsWith("mxc://") }
            .distinct()
        urls.forEach { url ->
            launch {
                val path = runSafe { service.avatars.resolve(url, px = 96, crop = true) }
                    ?: return@launch
                updateState { copy(heroAvatarPaths = heroAvatarPaths + (url to path)) }
            }
        }
    }
}
