package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.RoomDirectoryVisibility
import org.mlm.mages.matrix.RoomHistoryVisibility
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.matrix.RoomPowerLevelChanges
import org.mlm.mages.matrix.RoomPowerLevels
import org.mlm.mages.matrix.RoomPredecessorInfo
import org.mlm.mages.matrix.RoomProfile
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

    val myUserId: String? = null,
    val showMembers: Boolean = false,
    val selectedMemberForAction: MemberSummary? = null,
    val showInviteDialog: Boolean = false,
)

class RoomInfoViewModel(
    private val service: MatrixService,
    private val roomId: String
) : BaseViewModel<RoomInfoUiState>(RoomInfoUiState()) {

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
            val ok = service.port.setRoomNotificationMode(roomId, mode)
            if (ok) {
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
                _events.send(Event.ShowError("Failed to update notifications"))
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
            val canBan = runSafe { service.port.canUserBan(roomId, myUserId) } ?: false
            val canInvite = runSafe { service.port.canUserInvite(roomId, myUserId) } ?: false
            val canRedact = runSafe { service.port.canUserRedactOther(roomId, myUserId) } ?: false
            val canKick = powerLevel >= 50
            val canEditName = powerLevel >= 50
            val canEditTopic = powerLevel >= 50
            val canManageSettings = powerLevel >= 100

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
                    canEditName = canEditName,
                    canEditTopic = canEditTopic,
                    canManageSettings = canManageSettings,
                    canBan = canBan,
                    canInvite = canInvite,
                    canRedact = canRedact,
                    canKick = canKick,
                    myUserId = myUserId,
                    notificationMode = notificationMode,
                    isLoadingNotificationMode = false
                )
            }

            profile?.avatarUrl?.let { url ->
                if (url.startsWith("mxc://")) {
                    launch {
                        val path = service.avatars.resolve(url, px = 160, crop = true) ?: return@launch
                        updateState { copy(profile = this.profile?.copy(avatarUrl = path)) }
                    }
                }
            }

            sorted.forEach { m ->
                val mxc = m.avatarUrl ?: return@forEach
                if (!mxc.startsWith("mxc://")) return@forEach

                launch {
                    val path = service.avatars.resolve(mxc, px = 64, crop = true) ?: return@launch
                    updateState {
                        copy(
                            members = this.members.map { mm ->
                                if (mm.userId == m.userId) mm.copy(avatarUrl = path) else mm
                            }
                        )
                    }
                }
            }
        }
    }

    fun updateName(name: String) {
        updateState { copy(editedName = name) }
    }

    fun updateTopic(topic: String) {
        updateState { copy(editedTopic = topic) }
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

        launch {
            updateState { copy(isSaving = true) }
            val success = runSafe { service.port.setRoomName(roomId, name) } ?: false
            updateState { copy(isSaving = false) }

            if (success) {
                refresh()
                _events.send(Event.ShowSuccess("Room name updated"))
            } else {
                _events.send(Event.ShowError("Failed to update name"))
            }
        }
    }

    fun saveTopic() {
        if (!currentState.canEditTopic) {
            launch { _events.send(Event.ShowError("You don't have permission to change the topic")) }
            return
        }

        launch {
            val topic = currentState.editedTopic.trim()
            updateState { copy(isSaving = true) }
            val success = runSafe { service.port.setRoomTopic(roomId, topic) } ?: false
            updateState { copy(isSaving = false) }

            if (success) {
                refresh()
                _events.send(Event.ShowSuccess("Topic updated"))
            } else {
                _events.send(Event.ShowError("Failed to update topic"))
            }
        }
    }

    fun toggleFavourite() {
        launch {
            val current = currentState.isFavourite
            updateState { copy(isSaving = true) }
            val success = runSafe { service.port.setRoomFavourite(roomId, !current) } ?: false
            updateState { copy(isSaving = false) }

            if (success) {
                updateState { copy(isFavourite = !current) }
                if (!current && currentState.isLowPriority) {
                    runSafe { service.port.setRoomLowPriority(roomId, false) }
                    updateState { copy(isLowPriority = false) }
                }
                _events.send(Event.ShowSuccess(if (!current) "Added to favourites" else "Removed from favourites"))
            } else {
                _events.send(Event.ShowError("Failed to update favourite"))
            }
        }
    }

    fun toggleLowPriority() {
        launch {
            val current = currentState.isLowPriority
            updateState { copy(isSaving = true) }
            val success = runSafe { service.port.setRoomLowPriority(roomId, !current) } ?: false
            updateState { copy(isSaving = false) }

            if (success) {
                updateState { copy(isLowPriority = !current) }
                if (!current && currentState.isFavourite) {
                    runSafe { service.port.setRoomFavourite(roomId, false) }
                    updateState { copy(isFavourite = false) }
                }
                _events.send(Event.ShowSuccess(if (!current) "Marked as low priority" else "Removed from low priority"))
            } else {
                _events.send(Event.ShowError("Failed to update priority"))
            }
        }
    }

    fun setDirectoryVisibility(v: RoomDirectoryVisibility) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to change visibility")) }
            return
        }

        launch {
            updateState { copy(isAdminBusy = true) }
            val ok = runSafe { service.port.setRoomDirectoryVisibility(roomId, v) } ?: false
            updateState { copy(isAdminBusy = false) }
            if (ok) {
                refresh()
                _events.send(Event.ShowSuccess("Visibility updated"))
            } else {
                _events.send(Event.ShowError("Failed to update visibility"))
            }
        }
    }

    fun enableEncryption() {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to enable encryption")) }
            return
        }

        launch {
            updateState { copy(isAdminBusy = true) }
            val ok = runSafe { service.port.enableRoomEncryption(roomId) } ?: false
            updateState { copy(isAdminBusy = false) }
            if (ok) {
                refresh()
                _events.send(Event.ShowSuccess("Encryption enabled"))
            } else {
                _events.send(Event.ShowError("Failed to enable encryption"))
            }
        }
    }

    fun setJoinRule(rule: RoomJoinRule) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to change join rules")) }
            return
        }

        launch {
            updateState { copy(isAdminBusy = true) }
            val ok = runSafe { service.port.setRoomJoinRule(roomId, rule) } ?: false
            updateState { copy(isAdminBusy = false) }
            if (ok) {
                refresh()
                _events.send(Event.ShowSuccess("Join rule updated"))
            } else {
                _events.send(Event.ShowError("Failed to update join rule"))
            }
        }
    }

    fun setHistoryVisibility(visibility: RoomHistoryVisibility) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to change history visibility")) }
            return
        }

        launch {
            updateState { copy(isAdminBusy = true) }
            val ok = runSafe { service.port.setRoomHistoryVisibility(roomId, visibility) } ?: false
            updateState { copy(isAdminBusy = false) }
            if (ok) {
                refresh()
                _events.send(Event.ShowSuccess("History visibility updated"))
            } else {
                _events.send(Event.ShowError("Failed to update history visibility"))
            }
        }
    }

    fun updateCanonicalAlias(alias: String?, altAliases: List<String>) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to change room aliases")) }
            return
        }

        launch {
            updateState { copy(isAdminBusy = true) }
            val ok = runSafe { service.port.setRoomCanonicalAlias(roomId, alias, altAliases) } ?: false
            updateState { copy(isAdminBusy = false) }
            if (ok) {
                refresh()
                _events.send(Event.ShowSuccess("Room aliases updated"))
            } else {
                _events.send(Event.ShowError("Failed to update room aliases"))
            }
        }
    }

    fun updatePowerLevel(userId: String, powerLevel: Long) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to change power levels")) }
            return
        }

        launch {
            updateState { copy(isAdminBusy = true) }
            val ok = runSafe { service.port.updatePowerLevelForUser(roomId, userId, powerLevel) } ?: false
            updateState { copy(isAdminBusy = false) }
            if (ok) {
                refresh()
                _events.send(Event.ShowSuccess("Power level updated"))
            } else {
                _events.send(Event.ShowError("Failed to update power level"))
            }
        }
    }

    fun applyPowerLevelChanges(changes: RoomPowerLevelChanges) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError("You don't have permission to change permissions")) }
            return
        }

        launch {
            updateState { copy(isAdminBusy = true) }
            val ok = runSafe { service.port.applyPowerLevelChanges(roomId, changes) } ?: false
            updateState { copy(isAdminBusy = false) }
            if (ok) {
                refresh()
                _events.send(Event.ShowSuccess("Permissions updated"))
            } else {
                _events.send(Event.ShowError("Failed to update permissions"))
            }
        }
    }

    fun reportContent(eventId: String, score: Int?, reason: String?) {
        launch {
            val ok = runSafe { service.port.reportContent(roomId, eventId, score, reason) } ?: false
            if (ok) {
                _events.send(Event.ShowSuccess("Content reported"))
            } else {
                _events.send(Event.ShowError("Failed to report content"))
            }
        }
    }

    fun reportRoom(reason: String?) {
        launch {
            val ok = runSafe { service.port.reportRoom(roomId, reason) } ?: false
            if (ok) {
                _events.send(Event.ShowSuccess("Room reported"))
            } else {
                _events.send(Event.ShowError("Failed to report room"))
            }
        }
    }

    fun leave() {
        launch {
            val ok = runSafe { service.port.leaveRoom(roomId) } ?: false
            if (ok) {
                _events.send(Event.LeaveSuccess)
            } else {
                _events.send(Event.ShowError("Failed to leave room"))
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

    fun selectMemberForAction(member: MemberSummary) = updateState { copy(selectedMemberForAction = member) }

    fun clearSelectedMember() = updateState { copy(selectedMemberForAction = null) }

    fun showInviteDialog() = updateState { copy(showInviteDialog = true) }

    fun hideInviteDialog() = updateState { copy(showInviteDialog = false) }

    fun kickUser(userId: String, reason: String? = null) {
        launch {
            val ok = runSafe { service.port.kickUser(roomId, userId, reason) } ?: false
            if (ok) {
                updateState { copy(selectedMemberForAction = null) }
                refresh()
                _events.send(Event.ShowSuccess("User removed from room"))
            } else {
                _events.send(Event.ShowError("Failed to remove user"))
            }
        }
    }

    fun banUser(userId: String, reason: String? = null) {
        launch {
            val ok = runSafe { service.port.banUser(roomId, userId, reason) } ?: false
            if (ok) {
                updateState { copy(selectedMemberForAction = null) }
                refresh()
                _events.send(Event.ShowSuccess("User banned"))
            } else {
                _events.send(Event.ShowError("Failed to ban user"))
            }
        }
    }

    fun unbanUser(userId: String, reason: String? = null) {
        launch {
            val ok = runSafe { service.port.unbanUser(roomId, userId, reason) } ?: false
            if (ok) {
                updateState { copy(selectedMemberForAction = null) }
                refresh()
                _events.send(Event.ShowSuccess("User unbanned"))
            } else {
                _events.send(Event.ShowError("Failed to unban user"))
            }
        }
    }

    fun ignoreUser(userId: String) {
        launch {
            val ok = runSafe { service.port.ignoreUser(userId) } ?: false
            if (ok) {
                updateState { copy(selectedMemberForAction = null) }
                _events.send(Event.ShowSuccess("User ignored"))
            } else {
                _events.send(Event.ShowError("Failed to ignore user"))
            }
        }
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
        launch {
            val ok = runSafe { service.port.inviteUser(roomId, userId) } ?: false
            if (ok) {
                updateState { copy(showInviteDialog = false) }
                refresh()
                _events.send(Event.ShowSuccess("Invitation sent"))
            } else {
                _events.send(Event.ShowError("Failed to send invitation"))
            }
        }
    }
}
