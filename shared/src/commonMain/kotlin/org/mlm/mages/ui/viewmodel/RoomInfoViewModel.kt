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
import org.mlm.mages.matrix.RoomNotificationMode
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
    val isAdminBusy: Boolean = false,
    val successor: RoomUpgradeInfo? = null,
    val predecessor: RoomPredecessorInfo? = null,

    val notificationMode: RoomNotificationMode? = null,
    val isLoadingNotificationMode: Boolean = false,

    // Power level permissions
    val myPowerLevel: Long = 0L,
    val canEditName: Boolean = false,
    val canEditTopic: Boolean = false,
    val canManageSettings: Boolean = false,
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
            val successor = runSafe { service.port.roomSuccessor(roomId) }
            val predecessor = runSafe { service.port.roomPredecessor(roomId) }

            // Fetch power level and calculate permissions
            val myUserId = service.port.whoami() ?: ""
            val powerLevel = if (myUserId.isNotBlank()) {
                runSafe { service.port.getUserPowerLevel(roomId, myUserId) } ?: 0L
            } else {
                0L
            }
            // Matrix defaults: state_default = 50 for name/topic changes
            val canEditName = powerLevel >= 50
            val canEditTopic = powerLevel >= 50
            // Managing settings (visibility, encryption) typically requires higher power
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
                    successor = successor,
                    predecessor = predecessor,
                    error = if (profile == null) "Failed to load room info" else null,
                    myPowerLevel = powerLevel,
                    canEditName = canEditName,
                    canEditTopic = canEditTopic,
                    canManageSettings = canManageSettings
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
}