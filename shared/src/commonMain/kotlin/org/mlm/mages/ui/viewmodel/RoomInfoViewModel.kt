package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import mages.shared.generated.resources.*
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.isInviteBlocked
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.KnockRequestSummary
import org.mlm.mages.matrix.RoomDirectoryVisibility
import org.mlm.mages.matrix.RoomHistoryVisibility
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.matrix.RoomPowerLevelChanges
import org.mlm.mages.matrix.RoomPowerLevels
import org.mlm.mages.matrix.SpaceParentInfo
import org.mlm.mages.matrix.RoomInfoSnapshot
import org.mlm.mages.matrix.MatrixPort.RoomInfoObserver
import org.mlm.mages.matrix.RoomPredecessorInfo
import org.mlm.mages.matrix.RoomProfile
import org.mlm.mages.ui.ActionAvailabilityUi
import org.mlm.mages.ui.toUi
import org.mlm.mages.matrix.RoomUpgradeInfo
import org.mlm.mages.matrix.SpaceInfo
import org.jetbrains.compose.resources.getString
import mages.shared.generated.resources.Res
import org.jetbrains.compose.resources.StringResource

// Verification needs the other side of the conversation, which only a DM has.
private fun dmPartnerOf(
    profile: RoomProfile?,
    members: List<MemberSummary>
): MemberSummary? = if (profile?.isDm != true) null else members.firstOrNull { !it.isMe }

data class RoomInfoUiState(
    val profile: RoomProfile? = null,
    val members: List<MemberSummary> = emptyList(),
    val bannedMembers: List<MemberSummary> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val editedName: String = "",
    val editedTopic: String = "",
    val isSaving: Boolean = false,
    val isFavourite: Boolean = false,
    val isLowPriority: Boolean = false,

    val directoryVisibility: RoomDirectoryVisibility? = null,
    val joinRule: RoomJoinRule? = null,
    val joinRuleAllowedSpaceIds: List<String> = emptyList(),
    val historyVisibility: RoomHistoryVisibility? = null,
    val showJoinRuleSpacePicker: Boolean = false,
    val pendingJoinRule: RoomJoinRule? = null,
    val selectableSpaces: List<SpaceInfo> = emptyList(),
    val parentSpaces: List<SpaceParentInfo> = emptyList(),
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
    val dmPartner: MemberSummary? = null,
    val dmPartnerVerified: Boolean = false,
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

    sealed class Event {
        object LeaveSuccess : Event()
        data class OpenRoom(val roomId: String, val name: String) : Event()
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var roomInfoToken: ULong? = null
    private var lastAvatarMxc: String? = null

    init {
        refresh()
        observeRoomInfo()
    }

    override fun onCleared() {
        roomInfoToken?.let { runCatching { service.port.unobserveRoomInfo(it) } }
        roomInfoToken = null
        super.onCleared()
    }

    /** Live post-commit snapshot: avatar/profile, power levels, permissions. */
    private fun observeRoomInfo() {
        launch {
            roomInfoToken?.let { runCatching { service.port.unobserveRoomInfo(it) } }
            roomInfoToken = null
            roomInfoToken = runSafe {
                service.port.observeRoomInfo(roomId, object : RoomInfoObserver {
                    override fun onUpdate(snapshot: RoomInfoSnapshot) {
                        applyRoomInfoSnapshot(snapshot)
                    }
                })
            }
        }
    }

    private fun applyRoomInfoSnapshot(snapshot: RoomInfoSnapshot) {
        val s = currentState
        val powerLevels = snapshot.powerLevels
        val actionState = snapshot.actionState
        val myPowerLevel = s.myPowerLevel
        updateState {
            copy(
                profile = snapshot.profile,
                powerLevels = powerLevels,
                joinRule = snapshot.joinRule ?: joinRule,
                historyVisibility = snapshot.historyVisibility ?: historyVisibility,
                canEditName = actionState.editName.isEnabled,
                canEditTopic = actionState.editTopic.isEnabled,
                canManageSettings = actionState.manageSettings.isEnabled,
                canBan = myPowerLevel >= powerLevels.ban,
                canInvite = actionState.invite.isEnabled,
                canRedact = actionState.redactOthers.isEnabled,
                canKick = myPowerLevel >= powerLevels.kick,
            )
        }
        if (lastAvatarMxc != snapshot.profile.avatarUrl) {
            lastAvatarMxc = snapshot.profile.avatarUrl
            snapshot.profile.avatarUrl?.let { url ->
                launch {
                    val path = service.avatars.resolve(url, px = 160, crop = true) ?: return@launch
                    updateState { copy(profile = this.profile?.copy(avatarUrl = path)) }
                }
            }
        }
        if (actionState.invite.isEnabled) {
            launch {
                val knockRequests = runSafe { service.port.listKnockRequests(roomId) }.orEmpty()
                updateState { copy(knockRequests = knockRequests) }
                resolveKnockRequestAvatars(knockRequests)
            }
        }
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
                _events.send(Event.ShowSuccess(getString(Res.string.notification_updated)))
            } else {
                updateState { copy(isLoadingNotificationMode = false) }
                _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.failed_to_update_notifications))))
            }
        }
    }

    fun refresh() {
        launch(onError = {
            val failedToLoadRoomInfoFallback = getString(Res.string.failed_to_load_room_info)
            updateState { copy(isLoading = false, error = it.message ?: failedToLoadRoomInfoFallback) }
        }) {
            updateState { copy(isLoading = true, error = null) }

            val profile = service.port.roomProfile(roomId)
            val members = service.port.listMembers(roomId)
            val tags = service.port.roomTags(roomId)

            val sorted = members.sortedWith(
                compareByDescending<MemberSummary> { it.isMe }
                    .thenBy { it.displayName ?: it.userId }
            )

            val banned = runSafe { service.port.listBannedMembers(roomId) }
                .orEmpty()
                .sortedBy { it.displayName ?: it.userId }

            val vis = runSafe { service.port.roomDirectoryVisibility(roomId) }
            val joinRule = runSafe { service.port.roomJoinRule(roomId) }
            val joinRuleAllowedSpaceIds = runSafe { service.port.roomJoinRuleAllowList(roomId) }.orEmpty()
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

            val dmPartner = dmPartnerOf(profile, sorted)
            val dmPartnerVerified = dmPartner != null &&
                (runSafe { service.port.isUserVerified(dmPartner.userId) } ?: false)

            val loadError = if (profile == null) getString(Res.string.failed_to_load_room_info) else null
            updateState {
                copy(
                    profile = profile,
                    members = sorted,
                    bannedMembers = banned,
                    editedName = profile?.name ?: "",
                    editedTopic = profile?.topic ?: "",
                    isLoading = false,
                    isFavourite = tags?.first ?: false,
                    isLowPriority = tags?.second ?: false,
                    directoryVisibility = vis,
                    joinRule = joinRule,
                    joinRuleAllowedSpaceIds = joinRuleAllowedSpaceIds,
                    historyVisibility = historyVis,
                    successor = successor,
                    predecessor = predecessor,
                    error = loadError,
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
                    dmPartner = dmPartner,
                    dmPartnerVerified = dmPartnerVerified,
                    notificationMode = notificationMode,
                    isLoadingNotificationMode = false
                )
            }

            profile?.avatarUrl?.let { url ->
                lastAvatarMxc = url
                launch {
                    val path = service.avatars.resolve(url, px = 160, crop = true) ?: return@launch
                    updateState { copy(profile = this.profile?.copy(avatarUrl = path)) }
                }
            }

            resolveMemberAvatars(sorted)
            resolveKnockRequestAvatars(knockRequests)
            resolveParentSpace()
        }
    }

    fun refreshVerificationState() {
        val partner = currentState.dmPartner ?: return
        launch {
            val verified = runSafe { service.port.isUserVerified(partner.userId) } ?: false
            updateState { copy(dmPartnerVerified = verified) }
        }
    }

    private fun resolveParentSpace() {
        launch {
            val spaces = runSafe { service.roomParentSpaces(roomId) }.orEmpty()
            if (spaces.isEmpty()) return@launch
            updateState { copy(parentSpaces = spaces) }
            spaces.forEach { space ->
                val path = space.avatarUrl?.let { runSafe { service.avatars.resolve(it, px = 64) } }
                if (path == null) return@forEach
                updateState {
                    copy(
                        parentSpaces = parentSpaces.map { current ->
                            if (current.spaceId == space.spaceId) current.copy(avatarUrl = path) else current
                        }
                    )
                }
            }
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
        successMessage: StringResource,
        errorMessage: StringResource,
        refreshOnSuccess: Boolean = false,
        onSuccess: (suspend () -> Unit)? = null,
        block: suspend () -> Result<Unit>?,
    ) {
        launch {
            val successText = getString(successMessage)
            val errorText = getString(errorMessage)
            updateState { copy(isSaving = true) }
            val result = block()
            updateState { copy(isSaving = false) }

            if (result?.isSuccess == true) {
                if (refreshOnSuccess) refresh()
                onSuccess?.invoke()
                _events.send(Event.ShowSuccess(successText))
            } else {
                _events.send(Event.ShowError(result.toUserMessage(errorText)))
            }
        }
    }

    private fun runAdminAction(
        successMessage: StringResource,
        errorMessage: StringResource,
        refreshOnSuccess: Boolean = true,
        onSuccess: (suspend () -> Unit)? = null,
        block: suspend () -> Result<Unit>?,
    ) {
        launch {
            val successText = getString(successMessage)
            val errorText = getString(errorMessage)
            updateState { copy(isAdminBusy = true) }
            val result = block()
            updateState { copy(isAdminBusy = false) }

            if (result?.isSuccess == true) {
                if (refreshOnSuccess) refresh()
                onSuccess?.invoke()
                _events.send(Event.ShowSuccess(successText))
            } else {
                _events.send(Event.ShowError(result.toUserMessage(errorText)))
            }
        }
    }

    private fun runAction(
        successMessage: StringResource,
        errorMessage: StringResource,
        refreshOnSuccess: Boolean = false,
        onSuccess: (suspend () -> Unit)? = null,
        errorMessageFor: suspend (Throwable) -> String? = { null },
        block: suspend () -> Result<Unit>?,
    ) {
        launch {
            val successText = getString(successMessage)
            val errorText = getString(errorMessage)
            val result = block()
            if (result?.isSuccess == true) {
                if (refreshOnSuccess) refresh()
                onSuccess?.invoke()
                _events.send(Event.ShowSuccess(successText))
            } else {
                val specific = result?.exceptionOrNull()?.let { errorMessageFor(it) }
                _events.send(Event.ShowError(specific ?: result.toUserMessage(errorText)))
            }
        }
    }

    fun saveName() {
        val name = currentState.editedName.trim()
        if (name.isBlank()) {
            launch { _events.send(Event.ShowError(getString(Res.string.room_name_cannot_be_empty))) }
            return
        }
        if (!currentState.canEditName) {
            launch { _events.send(Event.ShowError(getString(Res.string.you_don_t_have_permission_to_change_the_room_name))) }
            return
        }

        runSavingAction(
            successMessage = Res.string.room_name_updated,
            errorMessage = Res.string.failed_to_update_name,
            refreshOnSuccess = true,
        ) { runSafe { service.port.setRoomName(roomId, name) } }
    }

    fun saveTopic() {
        if (!currentState.canEditTopic) {
            launch { _events.send(Event.ShowError(getString(Res.string.you_don_t_have_permission_to_change_the_topic))) }
            return
        }

        runSavingAction(
            successMessage = Res.string.topic_updated,
            errorMessage = Res.string.failed_to_update_topic,
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
                _events.send(Event.ShowSuccess(if (!current) getString(Res.string.added_to_favourites) else getString(Res.string.removed_from_favourites)))
            } else {
                _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.failed_to_update_favourite))))
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
                _events.send(Event.ShowSuccess(if (!current) getString(Res.string.marked_as_low_priority) else getString(Res.string.removed_from_low_priority)))
            } else {
                _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.failed_to_update_priority))))
            }
        }
    }

    fun setDirectoryVisibility(v: RoomDirectoryVisibility) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError(getString(Res.string.you_don_t_have_permission_to_change_visibility))) }
            return
        }

        runAdminAction(
            successMessage = Res.string.visibility_updated,
            errorMessage = Res.string.failed_to_update_visibility,
        ) { runSafe { service.port.setRoomDirectoryVisibility(roomId, v) } }
    }

    fun enableEncryption() {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError(getString(Res.string.you_don_t_have_permission_to_enable_encryption))) }
            return
        }

        runAdminAction(
            successMessage = Res.string.encryption_enabled,
            errorMessage = Res.string.failed_to_enable_encryption,
        ) { runSafe { service.port.enableRoomEncryption(roomId) } }
    }

    fun requestJoinRule(rule: RoomJoinRule) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError(getString(Res.string.you_don_t_have_permission_to_change_join_rules))) }
            return
        }

        if (rule == RoomJoinRule.Restricted || rule == RoomJoinRule.KnockRestricted) {
            launch {
                val spaces = runSafe { service.port.mySpaces() }.orEmpty()
                updateState {
                    copy(
                        showJoinRuleSpacePicker = true,
                        pendingJoinRule = rule,
                        selectableSpaces = spaces,
                    )
                }
            }
            return
        }

        setJoinRule(rule, emptyList())
    }

    fun hideJoinRuleSpacePicker() = updateState {
        copy(
            showJoinRuleSpacePicker = false,
            pendingJoinRule = null,
            selectableSpaces = emptyList(),
        )
    }

    fun setJoinRule(rule: RoomJoinRule, allowedSpaceIds: List<String>) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError(getString(Res.string.you_don_t_have_permission_to_change_join_rules))) }
            return
        }

        runAdminAction(
            successMessage = Res.string.join_rule_updated,
            errorMessage = Res.string.failed_to_update_join_rule,
            onSuccess = {
                updateState {
                    copy(
                        showJoinRuleSpacePicker = false,
                        pendingJoinRule = null,
                        selectableSpaces = emptyList(),
                        joinRule = rule,
                        joinRuleAllowedSpaceIds = allowedSpaceIds,
                    )
                }
            },
        ) { runSafe { service.port.setRoomJoinRule(roomId, rule, allowedSpaceIds) } }
    }

    fun setHistoryVisibility(visibility: RoomHistoryVisibility) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError(getString(Res.string.you_don_t_have_permission_to_change_history_visibility))) }
            return
        }

        runAdminAction(
            successMessage = Res.string.history_visibility_updated,
            errorMessage = Res.string.failed_to_update_history_visibility,
        ) { runSafe { service.port.setRoomHistoryVisibility(roomId, visibility) } }
    }

    fun updateCanonicalAlias(alias: String?, altAliases: List<String>) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError(getString(Res.string.you_don_t_have_permission_to_change_room_aliases))) }
            return
        }

        runAdminAction(
            successMessage = Res.string.room_aliases_updated,
            errorMessage = Res.string.failed_to_update_room_aliases,
        ) { runSafe { service.port.setRoomCanonicalAlias(roomId, alias, altAliases) } }
    }

    fun updatePowerLevel(userId: String, powerLevel: Long) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError(getString(Res.string.you_don_t_have_permission_to_change_power_levels))) }
            return
        }

        runAdminAction(
            successMessage = Res.string.power_level_updated,
            errorMessage = Res.string.failed_to_update_power_level,
        ) { runSafe { service.port.updatePowerLevelForUser(roomId, userId, powerLevel) } }
    }

    fun applyPowerLevelChanges(changes: RoomPowerLevelChanges) {
        if (!currentState.canManageSettings) {
            launch { _events.send(Event.ShowError(getString(Res.string.you_don_t_have_permission_to_change_permissions))) }
            return
        }

        runAdminAction(
            successMessage = Res.string.permissions_updated,
            errorMessage = Res.string.failed_to_update_permissions,
        ) { runSafe { service.port.applyPowerLevelChanges(roomId, changes) } }
    }

    fun reportContent(eventId: String, score: Int?, reason: String?) {
        runAction(
            successMessage = Res.string.content_reported,
            errorMessage = Res.string.failed_to_report_content,
        ) { runSafe { service.port.reportContent(roomId, eventId, score, reason) } }
    }

    fun reportRoom(reason: String?) {
        runAction(
            successMessage = Res.string.room_reported,
            errorMessage = Res.string.failed_to_report_room,
        ) { runSafe { service.port.reportRoom(roomId, reason) } }
    }

    fun leave() {
        launch {
            updateState { copy(isSaving = true) }
            val result = runSafe { service.port.leaveRoom(roomId) }
            updateState { copy(isSaving = false) }
            if (result?.isSuccess == true) {
                _events.send(Event.LeaveSuccess)
            } else {
                _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.failed_to_leave_room))))
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
            successMessage = Res.string.user_removed_from_room,
            errorMessage = Res.string.failed_to_remove_user,
            refreshOnSuccess = true,
            onSuccess = { updateState { copy(selectedMemberForAction = null) } }
        ) { runSafe { service.port.kickUser(roomId, userId, reason) } }
    }

    fun banUser(userId: String, reason: String? = null) {
        runAction(
            successMessage = Res.string.user_banned,
            errorMessage = Res.string.failed_to_ban_user,
            refreshOnSuccess = true,
            onSuccess = { updateState { copy(selectedMemberForAction = null) } }
        ) { runSafe { service.port.banUser(roomId, userId, reason) } }
    }

    fun unbanUser(userId: String, reason: String? = null) {
        runAction(
            successMessage = Res.string.user_unbanned,
            errorMessage = Res.string.failed_to_unban_user,
            refreshOnSuccess = true,
            onSuccess = { updateState { copy(selectedMemberForAction = null) } }
        ) { runSafe { service.port.unbanUser(roomId, userId, reason) } }
    }

    fun ignoreUser(userId: String) {
        runAction(
            successMessage = Res.string.user_ignored,
            errorMessage = Res.string.failed_to_ignore_user,
            onSuccess = { updateState { copy(selectedMemberForAction = null) } }
        ) { runSafe { service.port.ignoreUser(userId) } }
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

    fun startDmWith(userId: String) {
        launch {
            val dmRoomId = runSafe { service.port.ensureDm(userId) }
            if (dmRoomId != null) {
                updateState { copy(selectedMemberForAction = null, showMembers = false) }
                val profile = runSafe { service.port.roomProfile(dmRoomId) }
                _events.send(Event.OpenRoom(dmRoomId, profile?.name ?: userId))
            } else {
                _events.send(Event.ShowError(getString(Res.string.failed_to_start_conversation)))
            }
        }
    }

    fun inviteUser(userId: String) {
        runAction(
            successMessage = Res.string.invitation_sent,
            errorMessage = Res.string.failed_to_send_invitation,
            refreshOnSuccess = true,
            onSuccess = { updateState { copy(showInviteDialog = false) } },
            errorMessageFor = { if (it.isInviteBlocked()) getString(Res.string.invite_blocked) else null },
        ) { runSafe { service.port.inviteUser(roomId, userId) } }
    }

    fun acceptKnockRequest(userId: String) {
        runAction(
            successMessage = Res.string.knock_request_accepted,
            errorMessage = Res.string.failed_to_accept_knock_request,
            refreshOnSuccess = true,
            errorMessageFor = { if (it.isInviteBlocked()) getString(Res.string.invite_blocked) else null },
        ) { runSafe { service.port.acceptKnockRequest(roomId, userId) } }
    }

    fun declineKnockRequest(userId: String, reason: String? = null) {
        runAction(
            successMessage = Res.string.knock_request_declined,
            errorMessage = Res.string.failed_to_decline_knock_request,
            refreshOnSuccess = true,
        ) { runSafe { service.port.declineKnockRequest(roomId, userId, reason) } }
    }

}
