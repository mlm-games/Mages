package org.mlm.mages.ui.viewmodel

import mages.shared.generated.resources.*
import mages.shared.generated.resources.Res
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.ActionPresentation
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.ui.ActionAvailabilityUi
import org.mlm.mages.ui.ActionPresentationUi
import org.mlm.mages.ui.MentionProfileUi
import org.mlm.mages.ui.toUi

interface MemberActionState<S : MemberActionState<S>> {
    val roomMembers: List<MemberSummary>
    val mentionProfilesByUserId: Map<String, MentionProfileUi>
    val selectedMemberForAction: MemberSummary?
    val selectedMemberDmAction: ActionAvailabilityUi
    val selectedMemberKickAction: ActionAvailabilityUi
    val selectedMemberBanAction: ActionAvailabilityUi
    val selectedMemberUnbanAction: ActionAvailabilityUi

    fun withSelectedMember(member: MemberSummary?): S

    fun withMemberActions(
        dm: ActionAvailabilityUi = ActionAvailabilityUi(),
        kick: ActionAvailabilityUi = ActionAvailabilityUi(),
        ban: ActionAvailabilityUi = ActionAvailabilityUi(),
        unban: ActionAvailabilityUi = ActionAvailabilityUi(),
    ): S
}

abstract class MemberActionViewModel<S : MemberActionState<S>>(
    initialState: S,
) : BaseViewModel<S>(initialState) {

    protected abstract val service: MatrixService

    protected abstract val memberRoomId: String

    abstract val myUserId: String?

    protected abstract suspend fun sendError(message: String)

    protected abstract suspend fun sendSuccess(message: String)

    protected abstract suspend fun sendNavigateToRoom(roomId: String, title: String)

    protected open fun memberSelectionBlocked(): Boolean = false

    protected open fun memberFallbackEvents(): List<MessageEvent> = emptyList()

    fun selectMemberForAction(userId: String) {
        if (userId.isBlank() || userId == myUserId) return
        if (memberSelectionBlocked()) return
        val member = knownMember(userId) ?: return
        clearSelectedMember()
        updateState { withSelectedMember(member) }
        launch {
            val checking = getString(Res.string.checking_whether_you_can_start_a_conversation)
            if (currentState.selectedMemberForAction?.userId != userId) return@launch
            updateState {
                withMemberActions(
                    dm = ActionAvailabilityUi(
                        presentation = ActionPresentationUi.Disabled,
                        reason = checking,
                    )
                )
            }
            refreshSelectedMemberActionState(userId)
        }
    }

    fun clearSelectedMember() = updateState {
        withSelectedMember(null).withMemberActions()
    }

    fun startDmWith(userId: String) {
        launch {
            val actionState = runSafe { service.port.memberActionState(memberRoomId, userId) }
            if (actionState == null) {
                sendError(getString(Res.string.failed_to_check_whether_a_conversation_can_be_started))
                return@launch
            }

            if (actionState.directMessage.presentation != ActionPresentation.Enabled) {
                sendError(
                    actionState.directMessage.reason
                        ?: getString(Res.string.you_cannot_start_a_conversation_with_this_user)
                )
                return@launch
            }

            val dmRoomId = runSafe { service.port.ensureDmIfAllowed(memberRoomId, userId) }
            if (dmRoomId == null) {
                sendError(getString(Res.string.failed_to_start_conversation))
                return@launch
            }

            val profile = runSafe { service.port.roomProfile(dmRoomId) }
            sendNavigateToRoom(dmRoomId, profile?.name ?: userId)
        }
    }

    fun kickUser(userId: String, reason: String?) {
        runMemberAction(
            successMessage = Res.string.user_kicked,
            errorMessage = Res.string.failed_to_kick_user,
        ) { service.port.kickUser(memberRoomId, userId, reason) }
    }

    fun banUser(userId: String, reason: String?) {
        runMemberAction(
            successMessage = Res.string.user_banned,
            errorMessage = Res.string.failed_to_ban_user,
        ) { service.port.banUser(memberRoomId, userId, reason) }
    }

    fun unbanUser(userId: String, reason: String?) {
        runMemberAction(
            successMessage = Res.string.user_unbanned,
            errorMessage = Res.string.failed_to_unban_user,
        ) { service.port.unbanUser(memberRoomId, userId, reason) }
    }

    fun ignoreUser(userId: String) {
        runMemberAction(
            successMessage = Res.string.user_ignored,
            errorMessage = Res.string.failed_to_ignore_user,
        ) { service.port.ignoreUser(userId) }
    }

    fun openAvatarExternally(member: MemberSummary, onOpen: (String, String?) -> Unit) {
        openAvatarForViewing(
            service = service,
            userId = member.userId,
            fallbackAvatarUrl = member.avatarUrl,
            onOpen = onOpen,
            onError = { sendError(getString(Res.string.download_failed)) },
        )
    }

    private fun knownMember(userId: String): MemberSummary? {
        currentState.roomMembers.firstOrNull { it.userId == userId }?.let { return it }
        currentState.mentionProfilesByUserId[userId]?.let {
            return MemberSummary(userId = it.userId, displayName = it.displayName)
        }
        val sender = memberFallbackEvents().lastOrNull { it.sender == userId } ?: return null
        val displayName = sender.senderDisplayName?.takeIf { it.isNotBlank() } ?: return null
        return MemberSummary(userId = userId, displayName = displayName)
    }

    private suspend fun refreshSelectedMemberActionState(userId: String) {
        val actionState = runSafe { service.port.memberActionState(memberRoomId, userId) }
        if (currentState.selectedMemberForAction?.userId != userId) return
        updateState {
            withMemberActions(
                dm = actionState?.directMessage?.toUi() ?: ActionAvailabilityUi(),
                kick = actionState?.kick?.toUi() ?: ActionAvailabilityUi(),
                ban = actionState?.ban?.toUi() ?: ActionAvailabilityUi(),
                unban = actionState?.unban?.toUi() ?: ActionAvailabilityUi(),
            )
        }
    }

    private fun runMemberAction(
        successMessage: StringResource,
        errorMessage: StringResource,
        block: suspend () -> Result<Unit>?,
    ) {
        launch {
            val result = runSafe { block() }
            if (result?.isSuccess == true) {
                sendSuccess(getString(successMessage))
            } else {
                sendError(result.toUserMessage(getString(errorMessage)))
            }
        }
    }
}
