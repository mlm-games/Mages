package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.ui.SpaceSettingsUiState

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

    fun addChild(roomId: String, suggested: Boolean = false) {
        launch(
            onError = { t ->
                updateState { copy(isSaving = false) }
                launch {_events.send(Event.ShowError(t.message ?: "Failed to add room"))}
            }
        ) {
            updateState { copy(isSaving = true) }

            val ok = service.spaceAddChild(
                spaceId = currentState.spaceId,
                childRoomId = roomId,
                order = null,
                suggested = suggested
            )

            if (ok) {
                updateState { copy(isSaving = false, showAddRoom = false) }
                loadChildren()
                loadAvailableRooms()
                _events.send(Event.ShowSuccess("Room added to space"))
            } else {
                updateState { copy(isSaving = false) }
                _events.send(Event.ShowError("Failed to add room"))
            }
        }
    }

    fun removeChild(childRoomId: String) {
        launch(
            onError = { t ->
                updateState { copy(isSaving = false) }
                launch { _events.send(Event.ShowError(t.message ?: "Failed to remove room")) }
            }
        ) {
            updateState { copy(isSaving = true) }

            val ok = service.spaceRemoveChild(currentState.spaceId, childRoomId)

            if (ok) {
                updateState { copy(isSaving = false) }
                loadChildren()
                loadAvailableRooms()
                _events.send(Event.ShowSuccess("Room removed from space"))
            } else {
                updateState { copy(isSaving = false) }
                _events.send(Event.ShowError("Failed to remove room"))
            }
        }
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

        launch(
            onError = { t ->
                updateState { copy(isSaving = false) }
                launch { _events.send(Event.ShowError(t.message ?: "Failed to invite user")) }
            }
        ) {
            updateState { copy(isSaving = true) }

            val ok = service.spaceInviteUser(currentState.spaceId, userId)

            if (ok) {
                updateState { copy(isSaving = false, showInviteUser = false, inviteUserId = "") }
                _events.send(Event.ShowSuccess("Invitation sent"))
            } else {
                updateState { copy(isSaving = false) }
                _events.send(Event.ShowError("Failed to invite user"))
            }
        }
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
        launch(
            onError = { t ->
                updateState { copy(isSaving = false, showLeaveConfirm = false) }
                launch { _events.send(Event.ShowError(t.message ?: "Failed to leave space")) }
            }
        ) {
            updateState { copy(isSaving = true, showLeaveConfirm = false) }
            val ok = service.port.leaveRoom(currentState.spaceId)
            updateState { copy(isSaving = false) }
            if (ok) {
                _events.send(Event.LeaveSuccess)
            } else {
                _events.send(Event.ShowError("Failed to leave space"))
            }
        }
    }

    //  Private Methods 

    private fun loadSpaceInfo() {
        launch {
            val spaces = runSafe { service.mySpaces() } ?: emptyList()
            val space = spaces.find { it.roomId == currentState.spaceId }
            updateState { copy(space = space) }
            val mxc = space?.avatarUrl
            if (!mxc.isNullOrBlank() && mxc.startsWith("mxc://")) {
                val path = runSafe { service.avatars.resolve(mxc, px = 96, crop = true) }
                if (path != null) updateState { copy(spaceAvatarPath = path) }
            }
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
                // Filter out the space itself
                val children = page.children.filter { it.roomId != currentState.spaceId }

                // Fetch room profiles for names
                children.filter { !it.isSpace && it.name.isNullOrBlank() }.forEach { child ->
                    launch {
                        val profile = runSafe { service.port.roomProfile(child.roomId) }
                        if (profile != null && profile.name.isNotBlank()) {
                            updateState {
                                val updatedChildren = children.map { existing ->
                                    if (existing.roomId == child.roomId && existing.name.isNullOrBlank()) {
                                        existing.copy(name = profile.name)
                                    } else {
                                        existing
                                    }
                                }
                                copy(children = updatedChildren)
                            }
                        }
                    }
                }

                // Prefetch avatars
                children.forEach { child ->
                    child.avatarUrl?.let { url ->
                        if (url.startsWith("mxc://")) {
                            launch {
                                val path = service.avatars.resolve(url, px = 64, crop = true)
                                if (path != null) {
                                    updateState {
                                        copy(avatarPathByRoomId = avatarPathByRoomId + (child.roomId to path))
                                    }
                                }
                            }
                        }
                    }
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