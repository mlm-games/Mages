package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.ui.SpaceSettingsUiState

private fun List<SpaceChildInfo>.withoutSpace(spaceId: String): List<SpaceChildInfo> =
    filter { it.roomId != spaceId }

class SpaceSettingsViewModel(
    private val service: MatrixService,
    spaceId: String
) : BaseViewModel<SpaceSettingsUiState>(
    SpaceSettingsUiState(spaceId = spaceId, isLoading = true)
) {

    // One-time events
    sealed class Event {
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
        object LeaveSuccess : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        loadSpaceInfo()
        loadChildren()
        loadAvailableRooms()
    }

    //  Public Actions 

    fun refresh() {
        loadSpaceInfo()
        loadChildren()
        loadAvailableRooms()
    }

    // Add room dialog
    fun showAddRoomDialog() {
        updateState { copy(showAddRoom = true) }
    }

    fun hideAddRoomDialog() {
        updateState { copy(showAddRoom = false) }
    }

    private fun runSavingBooleanAction(
        successMessage: String,
        errorMessage: String,
        onErrorMessage: String = errorMessage,
        onSuccess: (() -> Unit)? = null,
        block: suspend () -> Boolean,
    ) {
        launch(
            onError = { t ->
                updateState { copy(isSaving = false) }
                launch { _events.send(Event.ShowError(t.message ?: onErrorMessage)) }
            }
        ) {
            updateState { copy(isSaving = true) }
            val ok = block()

            if (ok) {
                updateState { copy(isSaving = false) }
                onSuccess?.invoke()
                _events.send(Event.ShowSuccess(successMessage))
            } else {
                updateState { copy(isSaving = false) }
                _events.send(Event.ShowError(errorMessage))
            }
        }
    }

    private fun runSavingResultAction(
        errorMessage: String,
        onErrorMessage: String = errorMessage,
        onSuccess: (suspend () -> Unit)? = null,
        block: suspend () -> Result<Unit>?,
    ) {
        launch(
            onError = { t ->
                updateState { copy(isSaving = false) }
                launch { _events.send(Event.ShowError(t.message ?: onErrorMessage)) }
            }
        ) {
            updateState { copy(isSaving = true) }
            val result = block()
            updateState { copy(isSaving = false) }

            if (result?.isSuccess == true) {
                onSuccess?.invoke()
            } else {
                _events.send(Event.ShowError(result.toUserMessage(errorMessage)))
            }
        }
    }

    fun addChild(roomId: String, suggested: Boolean = false) {
        runSavingBooleanAction(
            successMessage = "Room added to space",
            errorMessage = "Failed to add room",
            onSuccess = {
                updateState { copy(showAddRoom = false) }
                loadChildren()
                loadAvailableRooms()
            }
        ) {
            service.spaceAddChild(
                spaceId = currentState.spaceId,
                childRoomId = roomId,
                order = null,
                suggested = suggested
            )
        }
    }

    fun removeChild(childRoomId: String) {
        runSavingBooleanAction(
            successMessage = "Room removed from space",
            errorMessage = "Failed to remove room",
            onSuccess = {
                loadChildren()
                loadAvailableRooms()
            }
        ) { service.spaceRemoveChild(currentState.spaceId, childRoomId) }
    }

    // Invite user dialog
    fun showInviteDialog() {
        updateState { copy(showInviteUser = true, inviteUserId = "") }
    }

    fun hideInviteDialog() {
        updateState { copy(showInviteUser = false, inviteUserId = "") }
    }

    fun setInviteUserId(userId: String) {
        updateState { copy(inviteUserId = userId) }
    }

    fun inviteUser() {
        val userId = currentState.inviteUserId.trim()
        if (userId.isBlank() || !userId.startsWith("@") || ":" !in userId) {
            launch { _events.send(Event.ShowError("Invalid user ID")) }
            return
        }

        runSavingBooleanAction(
            successMessage = "Invitation sent",
            errorMessage = "Failed to invite user",
            onSuccess = {
                updateState { copy(showInviteUser = false, inviteUserId = "") }
            }
        ) { service.spaceInviteUser(currentState.spaceId, userId) }
    }

    fun clearError() {
        updateState { copy(error = null) }
    }

    fun showLeaveConfirm() {
        updateState { copy(showLeaveConfirm = true) }
    }

    fun hideLeaveConfirm() {
        updateState { copy(showLeaveConfirm = false) }
    }

    fun leaveSpace() {
        runSavingResultAction(
            errorMessage = "Failed to leave space",
            onSuccess = { _events.send(Event.LeaveSuccess) }
        ) {
            updateState { copy(showLeaveConfirm = false) }
            service.port.leaveRoom(currentState.spaceId)
        }
    }

    //  Private Methods 

    private fun loadSpaceInfo() {
        launch {
            val spaces = runSafe { service.mySpaces() } ?: emptyList()
            val space = spaces.find { it.roomId == currentState.spaceId }
            updateState { copy(space = space) }
            resolveAvatar(service, space?.avatarUrl, 96) { path -> copy(spaceAvatarPath = path) }
        }
    }

    private fun loadChildren() {
        launch(
            onError = { t ->
                updateState { copy(isLoading = false, error = t.message ?: "Failed to load children") }
            }
        ) {
            updateState { copy(isLoading = true, error = null) }

            val page = service.spaceHierarchy(
                spaceId = currentState.spaceId,
                from = null,
                limit = 100,
                maxDepth = 1,
                suggestedOnly = false
            )

            if (page != null) {
                val children = page.children.withoutSpace(currentState.spaceId)

                hydrateMissingSpaceChildNames(service, children) { roomId, name ->
                    val updatedChildren = this.children.map { existing ->
                        if (existing.roomId == roomId && existing.name.isNullOrBlank()) {
                            existing.copy(name = name)
                        } else {
                            existing
                        }
                    }
                    copy(children = updatedChildren)
                }

                resolveSpaceChildAvatars(service, children) { roomId, path ->
                    copy(avatarPathByRoomId = avatarPathByRoomId + (roomId to path))
                }

                updateState { copy(children = children, isLoading = false) }
            } else {
                updateState { copy(isLoading = false, error = "Failed to load children") }
            }
        }
    }

    private fun loadAvailableRooms() {
        launch {
            val rooms = runSafe { service.portOrNull?.listRooms() } ?: emptyList()
            // Filter out rooms that are already children and the space itself
            val childIds = currentState.children.map { it.roomId }.toSet() + currentState.spaceId
            val available = rooms.filter { it.id !in childIds }
            updateState { copy(availableRooms = available) }
        }
    }
}
