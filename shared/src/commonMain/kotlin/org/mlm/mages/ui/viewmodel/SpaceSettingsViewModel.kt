package org.mlm.mages.ui.viewmodel

import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import mages.shared.generated.resources.*
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.ui.SpaceSettingsUiState
import org.jetbrains.compose.resources.getString
import mages.shared.generated.resources.Res
import org.jetbrains.compose.resources.StringResource

private fun List<SpaceChildInfo>.withoutSpace(spaceId: String): List<SpaceChildInfo> =
    filter { it.roomId != spaceId }

internal const val CHILDREN_RELOAD_ATTEMPTS = 4
internal const val CHILDREN_RELOAD_DELAY_MS = 1_500L

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
        loadPermissions()
        loadMembers()
        loadJoinRule()
    }

    //  Public Actions

    fun refresh() {
        loadSpaceInfo()
        loadChildren()
        loadPermissions()
        loadMembers()
        loadJoinRule()
    }

    private fun runSavingBooleanAction(
        successMessage: StringResource,
        errorMessage: StringResource,
        onErrorMessage: StringResource = errorMessage,
        onSuccess: (() -> Unit)? = null,
        block: suspend () -> Boolean,
    ) {
        launch(
            onError = { t ->
                updateState { copy(isSaving = false) }
                launch { _events.send(Event.ShowError(t.message ?: getString(onErrorMessage))) }
            }
        ) {
            updateState { copy(isSaving = true) }
            val ok = block()

            if (ok) {
                updateState { copy(isSaving = false) }
                onSuccess?.invoke()
                _events.send(Event.ShowSuccess(getString(successMessage)))
            } else {
                updateState { copy(isSaving = false) }
                _events.send(Event.ShowError(getString(errorMessage)))
            }
        }
    }

    private fun runSavingResultAction(
        errorMessage: StringResource,
        onErrorMessage: StringResource = errorMessage,
        onSuccess: (suspend () -> Unit)? = null,
        block: suspend () -> Result<Unit>?,
    ) {
        launch(
            onError = { t ->
                updateState { copy(isSaving = false) }
                launch { _events.send(Event.ShowError(t.message ?: getString(onErrorMessage))) }
            }
        ) {
            updateState { copy(isSaving = true) }
            val result = block()
            updateState { copy(isSaving = false) }

            if (result?.isSuccess == true) {
                onSuccess?.invoke()
            } else {
                _events.send(Event.ShowError(result.toUserMessage(getString(errorMessage))))
            }
        }
    }

    fun removeChild(childRoomId: String) {
        runSavingBooleanAction(
            successMessage = Res.string.room_removed_from_space,
            errorMessage = Res.string.failed_to_remove_room,
            onSuccess = { loadChildren() }
        ) { service.spaceRemoveChild(currentState.spaceId, childRoomId).isSuccess }
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
            errorMessage = Res.string.failed_to_leave_space,
            onSuccess = { _events.send(Event.LeaveSuccess) }
        ) {
            updateState { copy(showLeaveConfirm = false) }
            service.port.leaveRoom(currentState.spaceId)
        }
    }

    // Edit details

    fun showEditDetailsDialog() {
        if (!currentState.canEditDetails) {
            launch { _events.send(Event.ShowError(getString(Res.string.you_don_t_have_permission_to_edit_this_space))) }
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
            successMessage = Res.string.space_details_updated,
            errorMessage = Res.string.could_not_update_the_space_try_again,
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
            errorMessage = Res.string.could_not_remove_this_member_try_again,
            onSuccess = { clearSelectedMember(); loadMembers() }
        ) { service.port.kickUser(currentState.spaceId, userId, reason) }
    }

    fun banMember(userId: String, reason: String?) {
        runSavingResultAction(
            errorMessage = Res.string.could_not_ban_this_member_try_again,
            onSuccess = { clearSelectedMember(); loadMembers() }
        ) { service.port.banUser(currentState.spaceId, userId, reason) }
    }

    fun unbanMember(userId: String, reason: String?) {
        runSavingResultAction(
            errorMessage = Res.string.could_not_unban_this_member_try_again,
            onSuccess = { clearSelectedMember(); loadMembers() }
        ) { service.port.unbanUser(currentState.spaceId, userId, reason) }
    }

    fun ignoreMember(userId: String) {
        runSavingResultAction(
            errorMessage = Res.string.could_not_ignore_this_user_try_again,
            onSuccess = { clearSelectedMember() }
        ) { service.port.ignoreUser(userId) }
    }

    fun openAvatarExternally(member: MemberSummary, onOpen: (String, String?) -> Unit) {
        openAvatarForViewing(
            service = service,
            userId = member.userId,
            fallbackAvatarUrl = member.avatarUrl,
            onOpen = onOpen,
            onError = { _events.send(Event.ShowError(getString(Res.string.download_failed))) },
        )
    }

    fun updateMemberRole(userId: String, powerLevel: Long) {
        runSavingResultAction(
            errorMessage = Res.string.could_not_change_the_role_try_again,
            onSuccess = { loadPermissions() }
        ) {
            service.port.updatePowerLevelForUser(currentState.spaceId, userId, powerLevel)
        }
    }

    // Security (join rule)

    fun requestJoinRule(rule: RoomJoinRule) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError(getString(Res.string.you_don_t_have_permission_to_change_who_can_join))) }
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
            launch { _events.send(Event.ShowError(getString(Res.string.you_don_t_have_permission_to_change_who_can_join))) }
            return
        }
        runSavingResultAction(
            errorMessage = Res.string.could_not_update_who_can_join_try_again,
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
            errorMessage = Res.string.could_not_leave_try_again,
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
            service.port.subscribeToVisibleRooms(listOf(currentState.spaceId))
                .onFailure { Logger.w("Space ${currentState.spaceId}: subscribe failed: ${it.message}") }
            val snapshot = retryUntilPresent { runSafe { service.port.roomInfoSnapshot(currentState.spaceId) } }
            val me = runSafe { service.port.whoami() }
            if (snapshot == null) {
                Logger.w("Space ${currentState.spaceId}: room info snapshot unavailable; permissions unknown")
            }
            val myLevel = me
                ?.let { runSafe { service.port.getUserPowerLevel(currentState.spaceId, it) } }
                ?.takeIf { it >= 0L }
            updateState {
                copy(
                    canManageSettings = snapshot?.actionState?.manageSettings?.isEnabled == true,
                    canEditDetails = snapshot?.actionState?.editName?.isEnabled == true,
                    powerLevels = snapshot?.powerLevels ?: powerLevels,
                    myUserId = me ?: myUserId,
                    myPowerLevel = myLevel
                        ?: snapshot?.powerLevels?.users?.get(me ?: "")
                        ?: myPowerLevel
                )
            }
        }
    }

    private fun loadMembers() {
        launch {
            val members = runSafe { service.port.listMembers(currentState.spaceId) }.orEmpty()
            val banned = runSafe { service.port.listBannedMembers(currentState.spaceId) }.orEmpty()
            updateState { copy(members = members, bannedMembers = banned) }
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
                val failedToLoadChildrenFallback = getString(Res.string.failed_to_load_children)
                updateState { copy(isLoading = false, error = t.message ?: failedToLoadChildrenFallback) }
            }
        ) {
            loadChildrenNow()
        }
    }

    private suspend fun loadChildrenNow(silent: Boolean = false) {
        if (!silent) updateState { copy(isLoading = true, error = null) }

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
        } else if (!silent) {
            val message = result.toUserMessage(getString(Res.string.failed_to_load_children))
            updateState { copy(isLoading = false, error = message) }
        }
    }

    // The hierarchy endpoint only lists a room once the server has aggregated its stats,
    // which lags for rooms that were just created.
    fun reloadChildrenUntilPresent(roomId: String) {
        launch {
            repeat(CHILDREN_RELOAD_ATTEMPTS) { attempt ->
                if (attempt > 0) {
                    delay(CHILDREN_RELOAD_DELAY_MS * attempt)
                }
                loadChildrenNow(silent = true)
                if (currentState.children.any { it.roomId == roomId }) return@launch
            }
        }
    }
}
