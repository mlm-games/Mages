package org.mlm.mages.ui.viewmodel

import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.StringResource
import mages.shared.generated.resources.Res
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.isInviteBlocked
import org.mlm.mages.ui.SpaceActionsUiState

class SpaceActionsViewModel(
    private val service: MatrixService,
    spaceId: String
) : BaseViewModel<SpaceActionsUiState>(SpaceActionsUiState(spaceId = spaceId)) {

    sealed class Event {
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
        data class ChildAdded(val roomId: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        loadPermissions()
        loadAvailableRooms()
    }

    //  Public Actions

    fun refresh() {
        loadPermissions()
        loadAvailableRooms()
    }

    // Create room inside this space

    fun showCreateRoom() {
        if (!currentState.canManageChildren) {
            denyChildrenAction()
            return
        }
        updateState {
            copy(showCreateRoom = true, newRoomName = "", newRoomTopic = "", newRoomIsPublic = false)
        }
    }

    fun hideCreateRoom() = updateState { copy(showCreateRoom = false) }
    fun setNewRoomName(value: String) = updateState { copy(newRoomName = value) }
    fun setNewRoomTopic(value: String) = updateState { copy(newRoomTopic = value) }
    fun setNewRoomIsPublic(value: Boolean) = updateState { copy(newRoomIsPublic = value) }

    fun createRoomInSpace() {
        if (!currentState.canManageChildren) {
            denyChildrenAction()
            return
        }
        val name = currentState.newRoomName.trim()
        if (name.isBlank()) {
            launch { _events.send(Event.ShowError(getString(Res.string.give_the_room_a_name))) }
            return
        }
        val topic = currentState.newRoomTopic.trim().ifBlank { null }
        val spaceId = currentState.spaceId
        val isPublic = currentState.newRoomIsPublic
        var createdRoomId: String? = null
        runSavingResultAction(
            errorMessage = Res.string.could_not_create_the_room_try_again,
            onSuccess = {
                updateState { copy(showCreateRoom = false) }
                _events.send(Event.ShowSuccess(getString(Res.string.room_added_to_space)))
                createdRoomId?.let { _events.send(Event.ChildAdded(it)) }
            }
        ) {
            // Space membership is expressed as an m.space.child event on the space, so the
            // room is created standalone and linked to the space afterwards.
            val roomId = service.port.createRoom(
                name = name,
                topic = topic,
                invitees = emptyList(),
                isPublic = isPublic,
                roomAlias = null
            ) ?: return@runSavingResultAction null
            createdRoomId = roomId
            service.spaceAddChild(spaceId, roomId, order = null, suggested = false)
                .recoverCatching {
                    throw IllegalStateException(
                        getString(Res.string.room_was_created_but_could_not_be_added_to_the_space),
                        it
                    )
                }
        }
    }

    // Add an existing room to this space

    fun showAddRoom(excluding: Set<String>) {
        if (!currentState.canManageChildren) {
            denyChildrenAction()
            return
        }
        updateState { copy(showAddRoom = true, excludedChildIds = excluding) }
    }

    fun hideAddRoom() = updateState { copy(showAddRoom = false) }

    fun addChild(roomId: String, suggested: Boolean) {
        if (!currentState.canManageChildren) {
            denyChildrenAction()
            return
        }
        runSavingBooleanAction(
            successMessage = Res.string.room_added_to_space,
            errorMessage = Res.string.failed_to_add_room,
            onSuccess = {
                updateState { copy(showAddRoom = false, excludedChildIds = excludedChildIds + roomId) }
                _events.send(Event.ChildAdded(roomId))
            }
        ) {
            service.spaceAddChild(
                spaceId = currentState.spaceId,
                childRoomId = roomId,
                order = null,
                suggested = suggested
            ).isSuccess
        }
    }

    // Invite a user to this space

    fun showInviteDialog() {
        if (!currentState.canInvite) {
            denyInviteAction()
            return
        }
        updateState { copy(showInviteUser = true, inviteUserId = "") }
    }

    fun hideInviteDialog() = updateState { copy(showInviteUser = false) }

    fun setInviteUserId(userId: String) = updateState { copy(inviteUserId = userId) }

    fun inviteUser() {
        if (!currentState.canInvite) {
            denyInviteAction()
            return
        }
        val userId = currentState.inviteUserId.trim()
        if (userId.isBlank() || !userId.startsWith("@") || ":" !in userId) {
            launch { _events.send(Event.ShowError(getString(Res.string.invalid_user_id))) }
            return
        }

        runSavingResultAction(
            errorMessage = Res.string.failed_to_invite_user,
            errorMessageFor = { if (it.isInviteBlocked()) getString(Res.string.invite_blocked) else null },
            onSuccess = {
                updateState { copy(showInviteUser = false, inviteUserId = "") }
                _events.send(Event.ShowSuccess(getString(Res.string.invitation_sent)))
            },
        ) { runSafe { service.spaceInviteUser(currentState.spaceId, userId) } }
    }

    //  Private Methods

    private fun loadPermissions() {
        launch {
            service.port.subscribeToVisibleRooms(listOf(currentState.spaceId))
                .onFailure { Logger.w("Space ${currentState.spaceId}: subscribe failed: ${it.message}") }
            val actionState = retryUntilPresent { runSafe { service.port.roomInfoSnapshot(currentState.spaceId) } }
                ?.actionState
            if (actionState == null) {
                Logger.w("Space ${currentState.spaceId}: room info snapshot unavailable; action permissions unknown")
            }
            updateState {
                copy(
                    canManageChildren = actionState?.spaceChild?.isEnabled == true,
                    spaceChildReason = actionState?.spaceChild?.reason,
                    canInvite = actionState?.invite?.isEnabled == true,
                    inviteReason = actionState?.invite?.reason
                )
            }
        }
    }

    private fun loadAvailableRooms() {
        launch {
            val rooms = runSafe { service.port.listRooms() } ?: emptyList()
            updateState { copy(joinedRooms = rooms) }
        }
    }

    private fun denyChildrenAction() {
        denyAction(currentState.spaceChildReason)
    }

    private fun denyInviteAction() {
        denyAction(currentState.inviteReason)
    }

    private fun denyAction(reason: String?) {
        launch {
            val fallback = getString(Res.string.you_don_t_have_permission_to_change_this)
            _events.send(Event.ShowError(reason ?: fallback))
        }
    }

    private fun runSavingBooleanAction(
        successMessage: StringResource,
        errorMessage: StringResource,
        onSuccess: (suspend () -> Unit)? = null,
        block: suspend () -> Boolean,
    ) {
        launch(
            onError = { t ->
                updateState { copy(isSaving = false) }
                launch { _events.send(Event.ShowError(t.message ?: getString(errorMessage))) }
            }
        ) {
            updateState { copy(isSaving = true) }
            val ok = block()
            updateState { copy(isSaving = false) }

            if (ok) {
                onSuccess?.invoke()
                _events.send(Event.ShowSuccess(getString(successMessage)))
            } else {
                _events.send(Event.ShowError(getString(errorMessage)))
            }
        }
    }

    private fun runSavingResultAction(
        errorMessage: StringResource,
        errorMessageFor: suspend (Throwable) -> String? = { null },
        onSuccess: (suspend () -> Unit)? = null,
        block: suspend () -> Result<Unit>?,
    ) {
        launch(
            onError = { t ->
                updateState { copy(isSaving = false) }
                val message = errorMessageFor(t) ?: t.failureMessage(getString(errorMessage))
                launch { _events.send(Event.ShowError(message)) }
            }
        ) {
            updateState { copy(isSaving = true) }
            val result = block()
            updateState { copy(isSaving = false) }

            if (result?.isSuccess == true) {
                onSuccess?.invoke()
            } else {
                val specific = result?.exceptionOrNull()?.let { errorMessageFor(it) }
                _events.send(Event.ShowError(specific ?: result.toUserMessage(getString(errorMessage))))
            }
        }
    }
}
