package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.ActionAvailability
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.KnockRequestSummary
import org.mlm.mages.matrix.RoomDirectoryVisibility
import org.mlm.mages.matrix.RoomHistoryVisibility
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.matrix.RoomPowerLevelChanges
import org.mlm.mages.matrix.RoomPowerLevels
import org.mlm.mages.matrix.RoomPredecessorInfo
import org.mlm.mages.matrix.RoomProfile
import org.mlm.mages.ui.ActionAvailabilityUi
import org.mlm.mages.ui.ActionPresentationUi
import org.mlm.mages.matrix.RoomUpgradeInfo

data class RoomInfoUiState(
    val profile: RoomProfile? = null,
    val members: List<MemberSummary> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val editedName: String = "",
    val editedTopic: String = "",
    val isSaving: Boolean = false,
    val isFavourite: Boolean = false,
    val isLowPriority: Boolean = false,

    val directoryVisibility: RoomDirectoryVisibility? = null,
    val joinRule: RoomJoinRule? = null,
    val historyVisibility: RoomHistoryVisibility? = null,
    val isAdminBusy: Boolean = false,
    val successor: RoomUpgradeInfo? = null,
    val predecessor: RoomPredecessorInfo? = null,

    val notificationMode: RoomNotificationMode? = null,
    val isLoadingNotificationMode: Boolean = false,
    val showNotificationSettings: Boolean = false,

    val myPowerLevel: Long = 0L,
    val powerLevels: RoomPowerLevels? = null,
    val canEditName: Boolean = false,
    val canEditTopic: Boolean = false,
    val canManageSettings: Boolean = false,
    val canBan: Boolean = false,
    val canInvite: Boolean = false,
    val canRedact: Boolean = false,
    val canKick: Boolean = false,
    val knockRequests: List<KnockRequestSummary> = emptyList(),
    val showKnockRequests: Boolean = false,

    val myUserId: String? = null,
    val showMembers: Boolean = false,
    val selectedMemberForAction: MemberSummary? = null,
    val selectedMemberDmAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val selectedMemberKickAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val selectedMemberBanAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val selectedMemberUnbanAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val showInviteDialog: Boolean = false,
)

class RoomInfoViewModel(
    private val service: MatrixService,
    private val roomId: String
) : BaseViewModel<RoomInfoUiState>(RoomInfoUiState()) {

    private fun ActionAvailability.toUi(): ActionAvailabilityUi =
        ActionAvailabilityUi(
            presentation = when (presentation) {
                org.mlm.mages.matrix.ActionPresentation.Hidden -> ActionPresentationUi.Hidden
                org.mlm.mages.matrix.ActionPresentation.Disabled -> ActionPresentationUi.Disabled
                org.mlm.mages.matrix.ActionPresentation.Enabled -> ActionPresentationUi.Enabled
            },
            reason = reason,
        )

    sealed class Event {
        object LeaveSuccess : Event()
        data class OpenRoom(val roomId: String, val name: String) : Event()
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        refresh()
    }

    fun showNotificationSettings() = updateState { copy(showNotificationSettings = true) }

    fun hideNotificationSettings() = updateState { copy(showNotificationSettings = false) }

    fun setNotificationMode(mode: RoomNotificationMode) {
        launch {
            updateState { copy(isLoadingNotificationMode = true) }
            val result = runSafe { service.port.setRoomNotificationMode(roomId, mode) }
            if (result?.isSuccess == true) {
                updateState {
                    copy(
                        notificationMode = mode,
                        showNotificationSettings = false,
                        isLoadingNotificationMode = false
                    )
                }
                _events.send(Event.ShowSuccess("Notification settings updated"))
            } else {
                updateState { copy(isLoadingNotificationMode = false) }
                _events.send(Event.ShowError(result.toUserMessage("Failed to update notifications")))
            }
        }
    }

    fun refresh() {
        launch(onError = {
            updateState { copy(isLoading = false, error = it.message ?: "Failed to load room info") }
        }) {
            updateState { copy(isLoading = true, error = null) }

            val profile = service.port.roomProfile(roomId)
            val members = service.port.listMembers(roomId)
            val tags = service.port.roomTags(roomId)

            val sorted = members.sortedWith(
                compareByDescending<MemberSummary> { it.isMe }
                    .thenBy { it.displayName ?: it.userId }
            )

            val vis = runSafe { service.port.roomDirectoryVisibility(roomId) }
            val joinRule = runSafe { service.port.roomJoinRule(roomId) }
            val historyVis = runSafe { service.port.roomHistoryVisibility(roomId) }
            val successor = runSafe { service.port.roomSuccessor(roomId) }
            val predecessor = runSafe { service.port.roomPredecessor(roomId) }
            updateState { copy(isLoadingNotificationMode = true) }
            val notificationMode = runSafe { service.port.roomNotificationMode(roomId) }

            // Fetch power level and calculate permissions
            val myUserId = service.port.whoami() ?: ""
            val powerLevel = if (myUserId.isNotBlank()) {
                runSafe { service.port.getUserPowerLevel(roomId, myUserId) } ?: 0L
            } else {
                0L
            }
            val powerLevels = runSafe { service.port.roomPowerLevels(roomId) }
            val actionState = runSafe { service.port.roomActionState(roomId) }
            
            val canInvite = actionState?.invite?.isEnabled == true
            val knockRequests = if (canInvite) {
                runSafe { service.port.listKnockRequests(roomId) }.orEmpty()
            } else {
                emptyList()
            }

            updateState {
                copy(
                    profile = profile,
                    members = sorted,
                    editedName = profile?.name ?: "",
                    editedTopic = profile?.topic ?: "",
                    isLoading = false,
                    isFavourite = tags?.first ?: false,
                    isLowPriority = tags?.second ?: false,
                    directoryVisibility = vis,
                    joinRule = joinRule,
                    historyVisibility = historyVis,
                    successor = successor,
                    predecessor = predecessor,
                    error = if (profile == null) "Failed to load room info" else null,
                    myPowerLevel = powerLevel,
                    powerLevels = powerLevels,
                    canEditName = actionState?.editName?.isEnabled == true,
                    canEditTopic = actionState?.editTopic?.isEnabled == true,
                    canManageSettings = actionState?.manageSettings?.isEnabled == true,
                    canBan = powerLevel >= (powerLevels?.ban ?: 50),
                    canInvite = canInvite,
                    canRedact = actionState?.redactOthers?.isEnabled == true,
                    canKick = powerLevel >= (powerLevels?.kick ?: 50),
                    knockRequests = knockRequests,
                    myUserId = myUserId,
                    notificationMode = notificationMode,
                    isLoadingNotificationMode = false
                )
            }

            profile?.avatarUrl?.let { url ->
                launch {
                    val path = service.avatars.resolve(url, px = 160, crop = true) ?: return@launch
                    updateState { copy(profile = this.profile?.copy(avatarUrl = path)) }
                }
            }

            resolveMemberAvatars(sorted)
            resolveKnockRequestAvatars(knockRequests)
        }
    }

    private fun resolveMemberAvatars(members: List<MemberSummary>) {
        members.forEach { member ->
            resolveAvatar(service, member.avatarUrl, 64) { path ->
                copy(
                    members = this.members.map { current ->
                        if (current.userId == member.userId) current.copy(avatarUrl = path) else current
                    }
                )
            }
        }
    }

    private fun resolveKnockRequestAvatars(requests: List<KnockRequestSummary>) {
        requests.forEach { request ->
            resolveAvatar(service, request.avatarUrl, 64) { path ->
                copy(
                    knockRequests = this.knockRequests.map { current ->
                        if (current.eventId == request.eventId) current.copy(avatarUrl = path) else current
                    }
                )
            }
        }
    }

    fun updateName(name: String) {
        updateState { copy(editedName = name) }
    }

    fun updateTopic(topic: String) {
        updateState { copy(editedTopic = topic) }
    }

    private fun runSavingAction(
        successMessage: String,
        errorMessage: String,
        refreshOnSuccess: Boolean = false,
        onSuccess: (suspend () -> Unit)? = null,
        block: suspend () -> Result<Unit>?,
    ) {
        launch {
            updateState { copy(isSaving = true) }
            val result = block()
            updateState { copy(isSaving = false) }

            if (result?.isSuccess == true) {
                if (refreshOnSuccess) refresh()
                onSuccess?.invoke()
                _events.send(Event.ShowSuccess(successMessage))
            } else {
                _events.send(Event.ShowError(result.toUserMessage(errorMessage)))
            }
        }
    }

    private fun runAdminAction(
        successMessage: String,
        errorMessage: String,
        refreshOnSuccess: Boolean = true,
        onSuccess: (suspend () -> Unit)? = null,
        block: suspend () -> Result<Unit>?,
    ) {
        launch {
            updateState { copy(isAdminBusy = true) }
            val result = block()
            updateState { copy(isAdminBusy = false) }

            if (result?.isSuccess == true) {
                if (refreshOnSuccess) refresh()
                onSuccess?.invoke()
                _events.send(Event.ShowSuccess(successMessage))
            } else {
                _events.send(Event.ShowError(result.toUserMessage(errorMessage)))
            }
        }
    }

    private fun runAction(
        successMessage: String,
        errorMessage: String,
        refreshOnSuccess: Boolean = false,
        onSuccess: (suspend () -> Unit)? = null,
        block: suspend () -> Result<Unit>?,
    ) {
        launch {
            val result = block()
            if (result?.isSuccess == true) {
                if (refreshOnSuccess) refresh()
                onSuccess?.invoke()
                _events.send(Event.ShowSuccess(successMessage))
            } else {
                _events.send(Event.ShowError(result.toUserMessage(errorMessage)))
            }
        }
    }

    fun saveName() {
        val name = currentState.editedName.trim()
        if (name.isBlank()) {
            launch { _events.send(Event.ShowError("Room name cannot be empty")) }
            return
        }
        if (!currentState.canEditName) {
            launch { _events.send(Event.ShowError("You don't have permission to change the room name")) }
            return
        }

        runSavingAction(
            successMessage = "Room name updated",
            errorMessage = "Failed to update name",
            refreshOnSuccess = true,
        ) { runSafe { service.port.setRoomName(roomId, name) } }
    }

    fun saveTopic() {
        if (!currentState.canEditTopic) {
            launch { _events.send(Event.ShowError("You don't have permission to change the topic")) }
            return
        }

        runSavingAction(
            successMessage = "Topic updated",
            errorMessage = "Failed to update topic",
            refreshOnSuccess = true,
        ) {
            val topic = currentState.editedTopic.trim()
            runSafe { service.port.setRoomTopic(roomId, topic) }
        }
    }

    fun toggleFavourite() {
        launch {
            val current = currentState.isFavourite
            updateState { copy(isSaving = true) }
            val result = runSafe { service.port.setRoomFavourite(roomId, !current) }
            updateState { copy(isSaving = false) }

            if (result?.isSuccess == true) {
                updateState { copy(isFavourite = !current) }
                if (!current && currentState.isLowPriority) {
                    runSafe { service.port.setRoomLowPriority(roomId, false) }
                    updateState { copy(isLowPriority = false) }
                }
                _events.send(Event.ShowSuccess(if (!current) "Added to favourites" else "Removed from favourites"))
            } else {
                _events.send(Event.ShowError(result.toUserMessage("Failed to update favourite")))
            }
        }
    }

    fun toggleLowPriority() {
        launch {
            val current = currentState.isLowPriority
            updateState { copy(isSaving = true) }
            val result = runSafe { service.port.setRoomLowPriority(roomId, !current) }
            updateState { copy(isSaving = false) }

            if (result?.isSuccess == true) {
                updateState { copy(isLowPriority = !current) }
                if (!current && currentState.isFavourite) {
                    runSafe { service.port.setRoomFavourite(roomId, false) }
                    updateState { copy(isFavourite = false) }
                }
                _events.send(Event.ShowSuccess(if (!current) "Marked as low priority" else "Removed from low priority"))
            } else {
                _events.send(Event.ShowError(result.toUserMessage("Failed to update priority")))
            }
        }
    }

    fun setDirectoryVisibility(v: RoomDirectoryVisibility) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to change visibility")) }
            return
        }

        runAdminAction(
            successMessage = "Visibility updated",
            errorMessage = "Failed to update visibility",
        ) { runSafe { service.port.setRoomDirectoryVisibility(roomId, v) } }
    }

    fun enableEncryption() {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to enable encryption")) }
            return
        }

        runAdminAction(
            successMessage = "Encryption enabled",
            errorMessage = "Failed to enable encryption",
        ) { runSafe { service.port.enableRoomEncryption(roomId) } }
    }

    fun setJoinRule(rule: RoomJoinRule) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to change join rules")) }
            return
        }

        runAdminAction(
            successMessage = "Join rule updated",
            errorMessage = "Failed to update join rule",
        ) { runSafe { service.port.setRoomJoinRule(roomId, rule) } }
    }

    fun setHistoryVisibility(visibility: RoomHistoryVisibility) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to change history visibility")) }
            return
        }

        runAdminAction(
            successMessage = "History visibility updated",
            errorMessage = "Failed to update history visibility",
        ) { runSafe { service.port.setRoomHistoryVisibility(roomId, visibility) } }
    }

    fun updateCanonicalAlias(alias: String?, altAliases: List<String>) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to change room aliases")) }
            return
        }

        runAdminAction(
            successMessage = "Room aliases updated",
            errorMessage = "Failed to update room aliases",
        ) { runSafe { service.port.setRoomCanonicalAlias(roomId, alias, altAliases) } }
    }

    fun updatePowerLevel(userId: String, powerLevel: Long) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to change power levels")) }
            return
        }

        runAdminAction(
            successMessage = "Power level updated",
            errorMessage = "Failed to update power level",
        ) { runSafe { service.port.updatePowerLevelForUser(roomId, userId, powerLevel) } }
    }

    fun applyPowerLevelChanges(changes: RoomPowerLevelChanges) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to change permissions")) }
            return
        }

        runAdminAction(
            successMessage = "Permissions updated",
            errorMessage = "Failed to update permissions",
        ) { runSafe { service.port.applyPowerLevelChanges(roomId, changes) } }
    }

    fun reportContent(eventId: String, score: Int?, reason: String?) {
        runAction(
            successMessage = "Content reported",
            errorMessage = "Failed to report content",
        ) { runSafe { service.port.reportContent(roomId, eventId, score, reason) } }
    }

    fun reportRoom(reason: String?) {
        runAction(
            successMessage = "Room reported",
            errorMessage = "Failed to report room",
        ) { runSafe { service.port.reportRoom(roomId, reason) } }
    }

    fun leave() {
        launch {
            val result = runSafe { service.port.leaveRoom(roomId) }
            if (result?.isSuccess == true) {
                _events.send(Event.LeaveSuccess)
            } else {
                _events.send(Event.ShowError(result.toUserMessage("Failed to leave room")))
            }
        }
    }

    fun clearError() {
        updateState { copy(error = null) }
    }

    fun openRoom(roomId: String) {
        launch {
            val profile = runSafe { service.port.roomProfile(roomId) }
            _events.send(Event.OpenRoom(roomId, profile?.name ?: roomId))
        }
    }

    fun showMembers() = updateState { copy(showMembers = true) }

    fun hideMembers() = updateState { copy(showMembers = false, selectedMemberForAction = null) }

    fun showKnockRequests() = updateState { copy(showKnockRequests = true) }

    fun hideKnockRequests() = updateState { copy(showKnockRequests = false) }

    fun selectMemberForAction(member: MemberSummary) {
        updateState { copy(selectedMemberForAction = member) }
        refreshMemberActionState(member.userId)
    }

    private fun refreshMemberActionState(userId: String) {
        launch {
            val actionState = runSafe { service.port.memberActionState(roomId, userId) }
            if (actionState != null && currentState.selectedMemberForAction?.userId == userId) {
                updateState {
                    copy(
                        selectedMemberDmAction = actionState.directMessage.toUi(),
                        selectedMemberKickAction = actionState.kick.toUi(),
                        selectedMemberBanAction = actionState.ban.toUi(),
                        selectedMemberUnbanAction = actionState.unban.toUi(),
                    )
                }
            }
        }
    }

    fun clearSelectedMember() = updateState { 
        copy(
            selectedMemberForAction = null,
            selectedMemberDmAction = ActionAvailabilityUi(),
            selectedMemberKickAction = ActionAvailabilityUi(),
            selectedMemberBanAction = ActionAvailabilityUi(),
            selectedMemberUnbanAction = ActionAvailabilityUi(),
        ) 
    }

    fun showInviteDialog() = updateState { copy(showInviteDialog = true) }

    fun hideInviteDialog() = updateState { copy(showInviteDialog = false) }

    fun kickUser(userId: String, reason: String? = null) {
        runAction(
            successMessage = "User removed from room",
            errorMessage = "Failed to remove user",
            refreshOnSuccess = true,
            onSuccess = { updateState { copy(selectedMemberForAction = null) } }
        ) { runSafe { service.port.kickUser(roomId, userId, reason) } }
    }

    fun banUser(userId: String, reason: String? = null) {
        runAction(
            successMessage = "User banned",
            errorMessage = "Failed to ban user",
            refreshOnSuccess = true,
            onSuccess = { updateState { copy(selectedMemberForAction = null) } }
        ) { runSafe { service.port.banUser(roomId, userId, reason) } }
    }

    fun unbanUser(userId: String, reason: String? = null) {
        runAction(
            successMessage = "User unbanned",
            errorMessage = "Failed to unban user",
            refreshOnSuccess = true,
            onSuccess = { updateState { copy(selectedMemberForAction = null) } }
        ) { runSafe { service.port.unbanUser(roomId, userId, reason) } }
    }

    fun ignoreUser(userId: String) {
        runAction(
            successMessage = "User ignored",
            errorMessage = "Failed to ignore user",
            onSuccess = { updateState { copy(selectedMemberForAction = null) } }
        ) { runSafe { service.port.ignoreUser(userId) } }
    }

    fun startDmWith(userId: String) {
        launch {
            val dmRoomId = runSafe { service.port.ensureDm(userId) }
            if (dmRoomId != null) {
                updateState { copy(selectedMemberForAction = null, showMembers = false) }
                val profile = runSafe { service.port.roomProfile(dmRoomId) }
                _events.send(Event.OpenRoom(dmRoomId, profile?.name ?: userId))
            } else {
                _events.send(Event.ShowError("Failed to start conversation"))
            }
        }
    }

    fun inviteUser(userId: String) {
        runAction(
            successMessage = "Invitation sent",
            errorMessage = "Failed to send invitation",
            refreshOnSuccess = true,
            onSuccess = { updateState { copy(showInviteDialog = false) } }
        ) { runSafe { service.port.inviteUser(roomId, userId) } }
    }

    fun acceptKnockRequest(userId: String) {
        runAction(
            successMessage = "Knock request accepted",
            errorMessage = "Failed to accept knock request",
            refreshOnSuccess = true,
        ) { runSafe { service.port.acceptKnockRequest(roomId, userId) } }
    }

    fun declineKnockRequest(userId: String, reason: String? = null) {
        runAction(
            successMessage = "Knock request declined",
            errorMessage = "Failed to decline knock request",
            refreshOnSuccess = true,
        ) { runSafe { service.port.declineKnockRequest(roomId, userId, reason) } }
    }
}
