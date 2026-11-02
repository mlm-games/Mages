package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mages.shared.generated.resources.*
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.sortedForDisplay
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.RoomListMembership
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.notifications.SpaceNotificationScope
import org.mlm.mages.notifications.SpaceNotificationScopeStore
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
    private val scopeStore: SpaceNotificationScopeStore,
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
        loadNotificationMode()
    }

    //  Public Actions

    fun refresh() {
        loadSpaceInfo()
        loadChildren()
        loadPermissions()
        loadMembers()
        loadJoinRule()
        loadNotificationMode()
    }

    fun showNotificationSettings() = updateState { copy(showNotificationSettings = true) }

    fun hideNotificationSettings() = updateState { copy(showNotificationSettings = false) }

    fun setNotificationMode(mode: RoomNotificationMode) {
        launch {
            updateState { copy(isLoadingNotificationMode = true) }
            val spaceId = currentState.spaceId
            val accountId = service.activeAccount.value?.id
            val previous = scopeStore.get(accountId, spaceId)
            val previousMode = previous?.mode ?: currentState.notificationMode
            val descendants = descendantRoomIds(spaceId)
            if (descendants == null) {
                updateState { copy(isLoadingNotificationMode = false) }
                _events.send(Event.ShowError(getString(Res.string.failed_to_update_notifications)))
                return@launch
            }

            val spaceUpdated = runSafe {
                service.port.setRoomNotificationMode(spaceId, mode).isSuccess
            } == true
            val applied = applyNotificationMode(descendants, mode, previousMode)
            scopeStore.put(accountId, spaceId, SpaceNotificationScope(mode, applied.rooms))

            updateState {
                copy(
                    notificationMode = if (spaceUpdated) mode else notificationMode,
                    showNotificationSettings = false,
                    isLoadingNotificationMode = false
                )
            }
            when {
                !spaceUpdated -> _events.send(
                    Event.ShowError(getString(Res.string.failed_to_update_notifications))
                )
                applied.failed == 0 -> _events.send(
                    Event.ShowSuccess(getString(Res.string.notification_updated))
                )
                else -> _events.send(
                    Event.ShowError(
                        getString(Res.string.notifications_partially_updated, applied.failed)
                    )
                )
            }
        }
    }

    /**
     * Every room below [spaceId], including nested spaces. A null result means
     * the hierarchy could not be read completely, so the caller must not claim
     * the space was muted.
     */
    private suspend fun descendantRoomIds(spaceId: String): List<String>? {
        val seen = LinkedHashSet<String>()
        val pending = ArrayDeque<String>().apply { add(spaceId) }
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            var from: String? = null
            while (true) {
                val page = service.spaceHierarchy(id, from, 100, 1, false).getOrNull()
                    ?: return null
                for (child in page.children) {
                    if (child.roomId == id || !seen.add(child.roomId)) continue
                    if (child.isSpace) pending.add(child.roomId)
                }
                val next = page.nextBatch
                if (next == null || next == from) break
                from = next
            }
        }
        return seen.toList()
    }

    private data class NotificationApplyResult(val rooms: List<String>, val failed: Int)

    private data class RoomApplyOutcome(val owned: Boolean, val failed: Boolean)

    private suspend fun applyNotificationMode(
        roomIds: List<String>,
        mode: RoomNotificationMode,
        previousMode: RoomNotificationMode?,
    ): NotificationApplyResult = coroutineScope {
        val permits = Semaphore(4)
        val outcomes = roomIds.map { roomId ->
            async {
                permits.withPermit { applyRoomNotificationMode(roomId, mode, previousMode) }
            }
        }.awaitAll()
        NotificationApplyResult(
            rooms = roomIds.filterIndexed { index, _ -> outcomes[index].owned },
            failed = outcomes.count { it.failed },
        )
    }

    private suspend fun applyRoomNotificationMode(
        roomId: String,
        mode: RoomNotificationMode,
        previousMode: RoomNotificationMode?,
    ): RoomApplyOutcome {
        val currentResult = readUserDefinedMode(roomId)
        if (currentResult.isFailure) return RoomApplyOutcome(owned = false, failed = true)
        val current = currentResult.getOrNull()
        val ownedBefore = previousMode != null && current == previousMode
        if (!ownedBefore && current != null) {
            return RoomApplyOutcome(owned = false, failed = false)
        }
        val updated = applyRoomMode(roomId, mode)
        return RoomApplyOutcome(owned = updated || ownedBefore, failed = !updated)
    }

    private suspend fun readUserDefinedMode(roomId: String): Result<RoomNotificationMode?> =
        try {
            Result.success(service.port.userDefinedRoomNotificationMode(roomId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    private var scopeReconcileInFlight = false

    private fun reconcileNotificationScope() {
        if (scopeReconcileInFlight) return
        val spaceId = currentState.spaceId
        val accountId = service.activeAccount.value?.id
        scopeReconcileInFlight = true
        launch {
            try {
                val scope = scopeStore.get(accountId, spaceId)
                val spaceMode = readUserDefinedMode(spaceId).getOrNull() ?: return@launch
                val descendants = descendantRoomIds(spaceId) ?: return@launch
                val known = scope?.rooms?.toSet().orEmpty()
                val scopeMode = scope?.mode ?: spaceMode
                val owned = mutableListOf<String>()
                for (roomId in descendants) {
                    val currentResult = readUserDefinedMode(roomId)
                    if (currentResult.isFailure) {
                        if (roomId in known) owned += roomId
                        continue
                    }
                    val current = currentResult.getOrNull()
                    when {
                        current == null -> if (applyRoomMode(roomId, spaceMode)) owned += roomId
                        current == spaceMode -> owned += roomId
                        current == scopeMode -> if (applyRoomMode(roomId, spaceMode)) {
                            owned += roomId
                        }
                    }
                }
                if (owned != scope?.rooms || spaceMode != scope?.mode) {
                    scopeStore.put(accountId, spaceId, SpaceNotificationScope(spaceMode, owned))
                }
            } finally {
                scopeReconcileInFlight = false
            }
        }
    }

    private suspend fun applyRoomMode(roomId: String, mode: RoomNotificationMode): Boolean =
        runSafe { service.port.setRoomNotificationMode(roomId, mode).isSuccess } == true

    private val saving = SavingActionRunner(
        scope = viewModelScope,
        setSaving = { value -> updateState { copy(isSaving = value) } },
        emitError = { _events.send(Event.ShowError(it)) },
        emitSuccess = { _events.send(Event.ShowSuccess(it)) },
    )

    private fun runSavingBooleanAction(
        successMessage: StringResource,
        errorMessage: StringResource,
        onSuccess: (() -> Unit)? = null,
        block: suspend () -> Boolean,
    ) = saving.runBoolean(successMessage, errorMessage, onSuccess = { onSuccess?.invoke() }, block = block)

    private fun runSavingResultAction(
        errorMessage: StringResource,
        onSuccess: (suspend () -> Unit)? = null,
        block: suspend () -> Result<Unit>?,
    ) = saving.runResult(errorMessage, onSuccess = onSuccess, block = block)

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

    fun setAvatar(path: String, mime: String) {
        if (currentState.isSavingAvatar) return
        if (!currentState.canEditAvatar) {
            launch {
                _events.send(
                    Event.ShowError(
                        getString(Res.string.you_don_t_have_permission_to_change_the_space_avatar)
                    )
                )
            }
            return
        }
        launch(
            onError = { t ->
                updateState { copy(isSavingAvatar = false) }
                launch { _events.send(Event.ShowError(t.message ?: getString(Res.string.failed_to_update_avatar))) }
            }
        ) {
            updateState { copy(isSavingAvatar = true) }
            val result = runSafe { service.port.setRoomAvatarFromPath(currentState.spaceId, path, mime) }
            val mxc = result?.getOrNull()
            if (mxc == null) {
                updateState { copy(isSavingAvatar = false) }
                _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.failed_to_update_avatar))))
                return@launch
            }
            val resolved = runSafe { service.avatars.resolve(mxc, px = 96) }
            updateState {
                copy(
                    isSavingAvatar = false,
                    spaceAvatarPath = resolved,
                    space = space?.copy(avatarUrl = mxc)
                )
            }
            _events.send(Event.ShowSuccess(getString(Res.string.avatar_updated)))
        }
    }

    fun removeAvatar() {
        if (currentState.isSavingAvatar) return
        if (!currentState.canEditAvatar) {
            launch {
                _events.send(
                    Event.ShowError(
                        getString(Res.string.you_don_t_have_permission_to_change_the_space_avatar)
                    )
                )
            }
            return
        }
        launch(
            onError = { t ->
                updateState { copy(isSavingAvatar = false) }
                launch { _events.send(Event.ShowError(t.message ?: getString(Res.string.failed_to_update_avatar))) }
            }
        ) {
            updateState { copy(isSavingAvatar = true) }
            val result = service.port.removeRoomAvatar(currentState.spaceId)
            updateState { copy(isSavingAvatar = false) }
            if (result.isSuccess) {
                updateState {
                    copy(
                        spaceAvatarPath = null,
                        space = space?.copy(avatarUrl = null)
                    )
                }
                _events.send(Event.ShowSuccess(getString(Res.string.avatar_updated)))
            } else {
                _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.failed_to_update_avatar))))
            }
        }
    }

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

    fun openSpaceAvatarExternally(onOpen: (String, String?) -> Unit) {
        openAvatarSourceForViewing(
            service = service,
            source = currentState.space?.avatarUrl,
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
        val joined = currentState.children.filter {
            it.roomId != currentState.spaceId && it.membership == RoomListMembership.Joined
        }
        updateState {
            copy(
                showLeaveWithChildren = true,
                joinedChildren = joined,
                selectedChildIds = emptySet()
            )
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
            for (childId in childIds) {
                service.port.leaveRoom(childId)
            }
            service.port.leaveRoom(currentState.spaceId)
        }
    }

    //  Private Methods

    private fun loadNotificationMode() {
        launch {
            updateState { copy(isLoadingNotificationMode = true) }
            val mode = runSafe { service.port.roomNotificationMode(currentState.spaceId) }
            updateState {
                copy(
                    notificationMode = mode,
                    isLoadingNotificationMode = false
                )
            }
        }
    }

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
                    canEditAvatar = snapshot?.actionState?.editAvatar?.isEnabled == true,
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
            updateState {
                copy(members = members.sortedForDisplay(), bannedMembers = banned.sortedForDisplay())
            }
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
            reconcileNotificationScope()
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
