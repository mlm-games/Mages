package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.RoomJoinRule
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
        loadPermissions()
        loadMembers()
        loadJoinRule()
    }

    //  Public Actions

    fun refresh() {
        loadSpaceInfo()
        loadChildren()
        loadAvailableRooms()
        loadPermissions()
        loadMembers()
        loadJoinRule()
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
            ).isSuccess
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
        ) { service.spaceRemoveChild(currentState.spaceId, childRoomId).isSuccess }
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
        ) { service.spaceInviteUser(currentState.spaceId, userId).isSuccess }
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

    // Edit details

    fun showEditDetailsDialog() {
        if (!currentState.canEditDetails) {
            launch { _events.send(Event.ShowError("You don't have permission to edit this space")) }
            return
        }
        val space = currentState.space
        updateState {
            copy(
                showEditDetails = true,
                editName = space?.name.orEmpty(),
                editTopic = space?.topic.orEmpty(),
                editAlias = space?.canonicalAlias.orEmpty()
            )
        }
    }

    fun hideEditDetailsDialog() = updateState { copy(showEditDetails = false) }
    fun setEditName(value: String) = updateState { copy(editName = value) }
    fun setEditTopic(value: String) = updateState { copy(editTopic = value) }
    fun setEditAlias(value: String) = updateState { copy(editAlias = value.trim()) }

    fun saveEditDetails() {
        val name = currentState.editName.trim()
        val topic = currentState.editTopic.trim()
        val alias = currentState.editAlias.trim().ifBlank { null }
        runSavingBooleanAction(
            successMessage = "Space details updated",
            errorMessage = "Could not update the space. Try again.",
            onSuccess = {
                updateState { copy(showEditDetails = false) }
                loadSpaceInfo()
            }
        ) {
            val oldName = currentState.space?.name.orEmpty()
            val nameOk = if (name == oldName) true else service.port.setRoomName(currentState.spaceId, name).isSuccess
            val topicOk = service.port.setRoomTopic(currentState.spaceId, topic).isSuccess
            val aliasOk = service.port.setRoomCanonicalAlias(currentState.spaceId, alias, emptyList()).isSuccess
            nameOk && topicOk && aliasOk
        }
    }

    // People & roles

    fun showPeople() = updateState { copy(showPeople = true) }
    fun hidePeople() = updateState { copy(showPeople = false) }
    fun showRoles() = updateState { copy(showRoles = true) }
    fun hideRoles() = updateState { copy(showRoles = false) }

    fun selectMember(member: MemberSummary) = updateState { copy(selectedMember = member) }
    fun clearSelectedMember() = updateState { copy(selectedMember = null) }

    fun kickMember(userId: String, reason: String?) {
        runSavingResultAction(
            errorMessage = "Could not remove this member. Try again.",
            onSuccess = { clearSelectedMember(); loadMembers() }
        ) { service.port.kickUser(currentState.spaceId, userId, reason) }
    }

    fun banMember(userId: String, reason: String?) {
        runSavingResultAction(
            errorMessage = "Could not ban this member. Try again.",
            onSuccess = { clearSelectedMember(); loadMembers() }
        ) { service.port.banUser(currentState.spaceId, userId, reason) }
    }

    fun unbanMember(userId: String, reason: String?) {
        runSavingResultAction(
            errorMessage = "Could not unban this member. Try again.",
            onSuccess = { clearSelectedMember(); loadMembers() }
        ) { service.port.unbanUser(currentState.spaceId, userId, reason) }
    }

    fun ignoreMember(userId: String) {
        runSavingResultAction(
            errorMessage = "Could not ignore this user. Try again.",
            onSuccess = { clearSelectedMember() }
        ) { service.port.ignoreUser(userId) }
    }

    fun updateMemberRole(userId: String, powerLevel: Long) {
        runSavingResultAction(
            errorMessage = "Could not change the role. Try again.",
            onSuccess = { loadPermissions() }
        ) {
            service.port.updatePowerLevelForUser(currentState.spaceId, userId, powerLevel)
        }
    }

    // Security (join rule)

    fun requestJoinRule(rule: RoomJoinRule) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to change who can join")) }
            return
        }
        if (rule == RoomJoinRule.Restricted || rule == RoomJoinRule.KnockRestricted) {
            launch {
                val spaces = runSafe { service.port.mySpaces() }.orEmpty()
                updateState {
                    copy(
                        showJoinRulePicker = true,
                        pendingJoinRule = rule,
                        selectableSpaces = spaces
                    )
                }
            }
            return
        }
        setJoinRule(rule, emptyList())
    }

    fun hideJoinRuleSpacePicker() = updateState {
        copy(showJoinRulePicker = false, pendingJoinRule = null, selectableSpaces = emptyList())
    }

    fun setJoinRule(rule: RoomJoinRule, allowedSpaceIds: List<String>) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to change who can join")) }
            return
        }
        runSavingResultAction(
            errorMessage = "Could not update who can join. Try again.",
            onSuccess = {
                updateState {
                    copy(
                        showJoinRulePicker = false,
                        pendingJoinRule = null,
                        selectableSpaces = emptyList(),
                        joinRule = rule,
                        joinRuleAllowedSpaceIds = allowedSpaceIds
                    )
                }
            }
        ) {
            service.port.setRoomJoinRule(currentState.spaceId, rule, allowedSpaceIds)
        }
    }

    // Create room inside this space

    fun showCreateRoom() = updateState { copy(showCreateRoom = true, newRoomName = "", newRoomTopic = "", newRoomIsPublic = false) }
    fun hideCreateRoom() = updateState { copy(showCreateRoom = false) }
    fun setNewRoomName(value: String) = updateState { copy(newRoomName = value) }
    fun setNewRoomTopic(value: String) = updateState { copy(newRoomTopic = value) }
    fun setNewRoomIsPublic(value: Boolean) = updateState { copy(newRoomIsPublic = value) }

    fun createRoomInSpace() {
        val name = currentState.newRoomName.trim()
        if (name.isBlank()) {
            launch { _events.send(Event.ShowError("Give the room a name")) }
            return
        }
        val topic = currentState.newRoomTopic.trim().ifBlank { null }
        runSavingResultAction(
            errorMessage = "Could not create the room. Try again.",
            onSuccess = {
                updateState { copy(showCreateRoom = false) }
                loadChildren()
            }
        ) {
            val roomId = service.port.createRoom(
                name = name,
                topic = topic,
                invitees = emptyList(),
                isPublic = currentState.newRoomIsPublic,
                roomAlias = null,
                parentSpaceId = currentState.spaceId
            )
            if (roomId == null) null else Result.success(Unit)
        }
    }

    // Leave with children

    fun showLeaveWithChildren() {
        launch {
            val joined = currentState.children.filter { it.roomId != currentState.spaceId }
            updateState {
                copy(
                    showLeaveWithChildren = true,
                    joinedChildren = joined,
                    selectedChildIds = emptySet()
                )
            }
        }
    }

    fun hideLeaveWithChildren() = updateState {
        copy(showLeaveWithChildren = false, selectedChildIds = emptySet())
    }

    fun toggleChildSelection(roomId: String) = updateState {
        copy(
            selectedChildIds = if (roomId in selectedChildIds) selectedChildIds - roomId
            else selectedChildIds + roomId
        )
    }

    fun leaveSpaceWithChildren() {
        val childIds = currentState.selectedChildIds.toList()
        runSavingResultAction(
            errorMessage = "Could not leave. Try again.",
            onSuccess = { _events.send(Event.LeaveSuccess) }
        ) {
            var allOk = true
            for (childId in childIds) {
                if (service.port.leaveRoom(childId).isFailure) allOk = false
            }
            val spaceResult = service.port.leaveRoom(currentState.spaceId)
            if (spaceResult.isFailure) allOk = false
            if (allOk) Result.success(Unit) else null
        }
    }

    //  Private Methods

    private fun loadPermissions() {
        launch {
            val snapshot = runSafe { service.port.roomInfoSnapshot(currentState.spaceId) }
            val me = runSafe { service.port.whoami() }
            updateState {
                copy(
                    canManageSettings = snapshot?.actionState?.manageSettings?.isEnabled == true,
                    canEditDetails = snapshot?.actionState?.editName?.isEnabled == true,
                    canInvite = snapshot?.actionState?.invite?.isEnabled == true,
                    powerLevels = snapshot?.powerLevels ?: powerLevels,
                    myUserId = me ?: myUserId,
                    myPowerLevel = snapshot?.powerLevels?.users?.get(me ?: "") ?: myPowerLevel
                )
            }
        }
    }

    private fun loadMembers() {
        launch {
            val members = runSafe { service.port.listMembers(currentState.spaceId) }.orEmpty()
            updateState { copy(members = members) }
        }
    }

    private fun loadJoinRule() {
        launch {
            val rule = runSafe { service.port.roomJoinRule(currentState.spaceId) }
            val allow = runSafe { service.port.roomJoinRuleAllowList(currentState.spaceId) }.orEmpty()
            updateState {
                copy(joinRule = rule ?: joinRule, joinRuleAllowedSpaceIds = allow)
            }
        }
    }

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

            val result = service.spaceHierarchy(
                spaceId = currentState.spaceId,
                from = null,
                limit = 100,
                maxDepth = 1,
                suggestedOnly = false
            )

            if (result.isSuccess) {
                val page = result.getOrThrow()
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
                updateState { copy(isLoading = false, error = result.toUserMessage("Failed to load children")) }
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
