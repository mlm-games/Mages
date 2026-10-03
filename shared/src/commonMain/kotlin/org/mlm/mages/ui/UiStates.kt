package org.mlm.mages.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import kotlinx.serialization.Serializable
import mages.shared.generated.resources.*
import org.mlm.mages.AttachmentKind
import org.mlm.mages.LinkPreview
import org.mlm.mages.MessageEvent
import org.mlm.mages.ReplyPreview
import org.mlm.mages.ReplyPreviewKind
import org.mlm.mages.RoomSummary
import org.mlm.mages.captionOr
import org.mlm.mages.matrix.DeviceSummary
import org.mlm.mages.matrix.EventType
import org.mlm.mages.matrix.HomeserverLoginDetails
import org.mlm.mages.matrix.ImagePackImageEntry
import org.mlm.mages.matrix.ImagePackSummary
import org.mlm.mages.matrix.LiveLocationShare
import org.mlm.mages.matrix.SpaceParentInfo
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.OwnProfile
import org.mlm.mages.matrix.ProfileField
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.matrix.RoomPowerLevels
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.PasswordLoginKind
import org.mlm.mages.matrix.RoomPredecessorInfo
import org.mlm.mages.matrix.RoomUpgradeInfo
import org.mlm.mages.matrix.SasPhase
import org.mlm.mages.matrix.SearchHit
import org.mlm.mages.matrix.SeenByEntry
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.settings.RoomSwipeAction
import org.mlm.mages.ui.components.AttachmentData
import org.mlm.mages.ui.util.nowMs
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource
import mages.shared.generated.resources.Res


data class LoginUiState(
    val homeserver: String = "matrix.org",
    val user: String = "",
    val pass: String = "",
    val isBusy: Boolean = false,
    val ssoInProgress: Boolean = false,
    val oauthInProgress: Boolean = false,
    val error: String? = null,
    val loginDetails: HomeserverLoginDetails? = null,
    val showPasswordLogin: Boolean = false,
    val isCheckingServer: Boolean = false,
    val passwordLoginKind: PasswordLoginKind = PasswordLoginKind.Username,
    val phoneCountry: String = "",
    val needsLocalNetworkPermission: Boolean = false,
) {
    val effectiveHomeserver: String
        get() = loginDetails?.homeserverUrl ?: homeserver
}

data class RoomsUiState(
    val rooms: List<RoomSummary> = emptyList(),
    val roomSearchQuery: String = "",
    val unread: Map<String, Int> = emptyMap(),
    val offlineBanner: StringResource? = null,
    val syncBanner: String? = null,
    val unreadOnly: Boolean = false,
    val typeFilter: RoomTypeFilter = RoomTypeFilter.All,
    val swipeRightAction: RoomSwipeAction = RoomSwipeAction.Nothing,
    val swipeLeftAction: RoomSwipeAction = RoomSwipeAction.Nothing,
    val isLoading: Boolean = false,
    val error: String? = null,
    val favourites: Set<String> = emptySet(),
    val lowPriority: Set<String> = emptySet(),

    val allItems: List<RoomListItemUi> = emptyList(),
    val favouriteItems: List<RoomListItemUi> = emptyList(),
    val normalItems: List<RoomListItemUi> = emptyList(),
    val lowPriorityItems: List<RoomListItemUi> = emptyList(),
    val inviteItems: List<RoomListItemUi> = emptyList(),

    val declineInviteRoomId: String? = null,
    val declineInviteRoomName: String = "",
    val declineInviteInviterName: String? = null,
    val isDecliningInvite: Boolean = false,

    val roomAvatarPath: Map<String, String> = emptyMap(),
    val parentSpaces: Map<String, List<SpaceParentInfo>> = emptyMap(),
    val parentSpaceAvatarPath: Map<String, String> = emptyMap(),
    val parentSpacesResolvedKey: String = "",

    val unreadChatCount: Int = 0,
    val unreadGroupsCount: Int = 0,
    val unreadDmsCount: Int = 0,
    val spacesUnreadCount: Int = 0,
)

enum class RoomTypeFilter {
    All,
    Groups,
    Dms,
    Invites
}

enum class ActionPresentationUi {
    Hidden,
    Disabled,
    Enabled,
}

data class ActionAvailabilityUi(
    val presentation: ActionPresentationUi = ActionPresentationUi.Hidden,
    val reason: String? = null,
) {
    val isEnabled: Boolean
        get() = presentation == ActionPresentationUi.Enabled

    companion object {
        val Enabled = ActionAvailabilityUi(
            presentation = ActionPresentationUi.Enabled,
            reason = null,
        )
    }
}

fun MessageEvent.mediaCaption(): String? {
    val info = attachment ?: return null
    val fileName = info.fileName?.trim()
    return body
        .takeIf { it.isNotBlank() && (fileName == null || it.trim() != fileName) }
}

fun MessageEvent.hasCaption(): Boolean = mediaCaption() != null

fun MessageEvent.isForwardable(): Boolean = when (eventType) {
    EventType.Message, EventType.Sticker, EventType.Location -> true
    else -> false
}

@Composable
fun MessageEvent.displayPreview(): String {
    if (isRedacted) return stringResource(Res.string.message_deleted)
    if (utd != null) return stringResource(Res.string.unable_to_decrypt_this_message)
    mediaCaption()?.let { return it }
    if (eventType == EventType.CallInvite) return stringResource(Res.string.call)
    if (eventType == EventType.CallNotification) {
        return when {
            body.contains("video", ignoreCase = true) -> stringResource(Res.string.video_call)
            body.contains("audio", ignoreCase = true) || body.contains("voice", ignoreCase = true) -> stringResource(Res.string.voice_call)
            else -> stringResource(Res.string.call)
        }
    }
    if (sticker != null) return stringResource(Res.string.picker_sticker)
    pollData?.let { return it.question }
    val attachment = attachment ?: return body
    return when (attachment.kind) {
        AttachmentKind.Image -> stringResource(Res.string.image)
        AttachmentKind.Video -> stringResource(Res.string.video)
        AttachmentKind.Audio -> if (attachment.isVoice == true) {
            stringResource(Res.string.voice_message)
        } else attachment.fileName?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.audio)
        AttachmentKind.File -> attachment.fileName?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.file)
    }
}

@Composable
fun MessageEvent.toReplyPreview(): ReplyPreview {
    if (isRedacted) {
        return ReplyPreview(
            kind = ReplyPreviewKind.Redacted,
            text = stringResource(Res.string.message_deleted),
        )
    }
    if (utd != null) {
        return ReplyPreview(
            kind = ReplyPreviewKind.Encrypted,
            text = stringResource(Res.string.unable_to_decrypt_this_message),
        )
    }
    if (sticker != null) {
        return ReplyPreview(
            kind = ReplyPreviewKind.Sticker,
            sticker = sticker,
        )
    }
    pollData?.let { poll ->
        return ReplyPreview(
            kind = ReplyPreviewKind.Poll,
            text = poll.question,
        )
    }
    val attachment = attachment
    if (attachment != null) {
        val kind = when (attachment.kind) {
            AttachmentKind.Image -> ReplyPreviewKind.Image
            AttachmentKind.Video -> ReplyPreviewKind.Video
            AttachmentKind.Audio -> if (attachment.isVoice == true) ReplyPreviewKind.Voice else ReplyPreviewKind.Audio
            AttachmentKind.File -> ReplyPreviewKind.File
        }
        val label = when (kind) {
            ReplyPreviewKind.Image -> stringResource(Res.string.image)
            ReplyPreviewKind.Video -> stringResource(Res.string.video)
            ReplyPreviewKind.Audio -> stringResource(Res.string.audio)
            else -> stringResource(Res.string.file)
        }
        val text = when (kind) {
            ReplyPreviewKind.Voice -> stringResource(Res.string.voice_message)
            // A filename is only useful as the identifier for a file attachment;
            // for media it is the caption-less body, not something worth showing.
            ReplyPreviewKind.Image, ReplyPreviewKind.Video, ReplyPreviewKind.Audio ->
                attachment.captionOr(body) ?: label
            else -> body.trim().ifBlank {
                attachment.fileName?.takeIf { it.isNotBlank() } ?: label
            }
        }
        return ReplyPreview(kind = kind, text = text, attachment = attachment)
    }
    return ReplyPreview(
        kind = when {
            eventType == org.mlm.mages.matrix.EventType.CallNotification &&
                body.contains("video", ignoreCase = true) -> ReplyPreviewKind.VideoCall
            eventType == org.mlm.mages.matrix.EventType.CallNotification &&
                (body.contains("audio", ignoreCase = true) || body.contains("voice", ignoreCase = true)) -> ReplyPreviewKind.VoiceCall
            eventType == org.mlm.mages.matrix.EventType.CallInvite ||
                eventType == org.mlm.mages.matrix.EventType.CallNotification -> ReplyPreviewKind.Call
            eventType == org.mlm.mages.matrix.EventType.Location -> ReplyPreviewKind.Location
            eventType == org.mlm.mages.matrix.EventType.LiveLocation -> ReplyPreviewKind.LiveLocation
            else -> ReplyPreviewKind.Text
        },
        text = when {
            eventType == org.mlm.mages.matrix.EventType.CallNotification &&
                body.contains("video", ignoreCase = true) -> stringResource(Res.string.video_call)
            eventType == org.mlm.mages.matrix.EventType.CallNotification &&
                (body.contains("audio", ignoreCase = true) || body.contains("voice", ignoreCase = true)) -> stringResource(Res.string.voice_call)
            eventType == org.mlm.mages.matrix.EventType.CallInvite ||
                eventType == org.mlm.mages.matrix.EventType.CallNotification -> stringResource(Res.string.call)
            eventType == org.mlm.mages.matrix.EventType.Location -> stringResource(Res.string.shared_location)
            eventType == org.mlm.mages.matrix.EventType.LiveLocation -> stringResource(Res.string.shared_live_location)
            else -> body.takeIf { it.isNotBlank() }
        },
    )
}

fun MessageEvent.isDisplayableAsPinnedEvent(): Boolean {
    if (isRedacted) return false
    if (eventType == EventType.Unknown) return false
    if (eventType == EventType.Unsupported) return false
    if (body.isBlank() && attachment == null && sticker == null && pollData == null) return false
    return true
}

data class PinnedMessageUi(
    val eventId: String,
    val event: MessageEvent? = null,
) {
    val isResolved: Boolean get() = event != null
    val senderLabel: String? get() = event?.senderDisplayName ?: event?.sender
    val timestampMs: Long? get() = event?.timestampMs
}

@Composable
fun PinnedMessageUi.previewText(): String =
    event?.displayPreview()?.ifBlank { stringResource(Res.string.pinned_message) }
        ?: stringResource(Res.string.pinned_message)

data class MessageActionStateUi(
    val edit: ActionAvailabilityUi = ActionAvailabilityUi(),
    val delete: ActionAvailabilityUi = ActionAvailabilityUi(),
    val pin: ActionAvailabilityUi = ActionAvailabilityUi(),
    val unpin: ActionAvailabilityUi = ActionAvailabilityUi(),
    val react: ActionAvailabilityUi = ActionAvailabilityUi(),
)

data class RoomUiState(
    val roomId: String,
    val roomName: String,
    val myUserId: String? = null,
    val allEvents: List<MessageEvent> = emptyList(),
    val events: List<MessageEvent> = emptyList(),
    val input: String = "",
    val replyingTo: MessageEvent? = null,
    val editing: MessageEvent? = null,
    val editingPoll: MessageEvent? = null,
    val typingNames: List<String> = emptyList(),
    val isPaginatingBack: Boolean = false,
    val hasTimelineSnapshot: Boolean = false,
    val hitStart: Boolean = false,
    val jumpHitStart: Boolean = false,
    val isOffline: Boolean = false,
    /**
     * True between the initial cached timeline Reset and the first live
     * Append/Update (or timeout).
     */
    val isCatchingUp: Boolean = false,
    val attachments: List<AttachmentData> = emptyList(),
    val isUploadingAttachment: Boolean = false,
    val uploadingFileName: String? = null,
    val attachmentProgress: Float = 0f,
    val attachmentUploadStage: AttachmentUploadStage? = null,
    val error: String? = null,

    val lastReadTs: Long? = null,
    val hasLoadedLastRead: Boolean = false,
    val isDm: Boolean = false,
    val identityKnown: Boolean = false,
    val lastIncomingFromOthersTs: Long? = null,
    val lastOutgoingRead: Boolean = false,

    val thumbByEvent: Map<String, String> = emptyMap(),
    val emotePathByMxc: Map<String, String> = emptyMap(),
    val replyThumbByEvent: Map<String, String> = emptyMap(),
    val avatarByUserId: Map<String, String> = emptyMap(),
    val roomMembers: List<MemberSummary> = emptyList(),
    val threadCount: Map<String, Int> = emptyMap(),
    val audioFileByEvent: Map<String, String> = emptyMap(),
    val waveformByEvent: Map<String, List<Float>> = emptyMap(),

    /** Preview for the link in an event, keyed by event id; null is a remembered miss. */
    val linkPreviewByEvent: Map<String, LinkPreview?> = emptyMap(),
    val linkPreviewImageByEvent: Map<String, String> = emptyMap(),

    val liveLocationShares: Map<String, LiveLocationShare> = emptyMap(),
    val liveLocationSubToken: ULong? = null,

    val notificationMode: RoomNotificationMode = RoomNotificationMode.AllMessages,
    val isLoadingNotificationMode: Boolean = false,

    val successor: RoomUpgradeInfo? = null,
    val predecessor: RoomPredecessorInfo? = null,

    val showAttachmentPicker: Boolean = false,
    val showStickerPicker: Boolean = false,
    val isRoomEncrypted: Boolean = false,
    val isLoadingImagePacks: Boolean = false,
    val imagePacks: List<ImagePackSummary> = emptyList(),
    val discoveredPacks: List<ImagePackSummary>? = null,
    val isDiscoveringPacks: Boolean = false,
    val packIdsBeingUpdated: Set<String> = emptySet(),
    val reactionImagePathByMxc: Map<String, String> = emptyMap(),
    val showPollCreator: Boolean = false,
    val showLiveLocation: Boolean = false,
    val showShareLocation: Boolean = false,
    val isSendingShareLocation: Boolean = false,
    val shareLocationInitialLat: Double? = null,
    val shareLocationInitialLon: Double? = null,
    val showLiveLocationMap: Boolean = false,
    val isLiveLocationLoading: Boolean = false,
    val liveLocationError: String? = null,
    val showStaticLocationViewer: Boolean = false,
    val staticLocationViewerLat: Double = 0.0,
    val staticLocationViewerLon: Double = 0.0,
    val showNotificationSettings: Boolean = false,

    val showForwardPicker: Boolean = false,
    val forwardingEvent: MessageEvent? = null,
    val forwardableRooms: List<ForwardableRoom> = emptyList(),
    val isLoadingForwardRooms: Boolean = false,
    val forwardSearchQuery: String = "",
    val roomAvatarUrl: String?,

    val showRoomSearch: Boolean = false,
    val roomSearchQuery: String = "",
    val roomSearchResults: List<SearchHit> = emptyList(),
    val roomSearchNextOffset: Int? = null,
    val isRoomSearching: Boolean = false,
    val hasRoomSearched: Boolean = false,
    val hasActiveCallForRoom: Boolean = false,

    // Room action availability states
    val voiceCallAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val videoCallAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val sendMessageAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val sendReactionAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val editNameAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val editTopicAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val inviteAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val manageSettingsAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val redactOthersAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val pinAction: ActionAvailabilityUi = ActionAvailabilityUi(),

    // Per-message action availability states (for selected message)
    val selectedMessageActions: MessageActionStateUi? = null,
    val selectedMessageActionEventId: String? = null,

    val isSelectionMode: Boolean = false,
    val selectedEventIds: Set<String> = emptySet(),

    val myPowerLevel: Long = 0L,

    val pinnedEventIds: List<String> = emptyList(),
    val showPinnedMessagesSheet: Boolean = false,
    val pinnedResolvedEvents: Map<String, MessageEvent> = emptyMap(),
    val seekingEventId: String? = null,

    // Report dialog
    val showReportDialog: Boolean = false,
    val reportingEvent: MessageEvent? = null,

    val seenByEntries: List<SeenByEntry> = emptyList(),
    val showMessageInfo: Boolean = false,
    val messageInfoEvent: MessageEvent? = null,
    val isLoadingMessageInfo: Boolean = false,
    val messageInfoError: String? = null,
    val messageInfoEntries: List<SeenByEntry> = emptyList(),
    val messageInfoReadersTruncated: Boolean = false,
    val showReadReceiptsSheet: Boolean = false,
    val readReceiptsForEvent: List<SeenByEntry> = emptyList(),
    val highlightedEventId: String? = null,
    val selectedMemberForAction: MemberSummary? = null,
    val selectedMemberDmAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val selectedMemberKickAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val selectedMemberBanAction: ActionAvailabilityUi = ActionAvailabilityUi(),
    val selectedMemberUnbanAction: ActionAvailabilityUi = ActionAvailabilityUi(),

    // Voice recording
    val isRecordingVoice: Boolean = false,
    val voiceRecordingPath: String? = null,
    val voiceRecordingDurationMs: Long = 0L,
    val voiceRecordingWaveform: List<Float> = emptyList(),
    val showVoicePreview: Boolean = false,
) {
    val pinnedMessages: List<PinnedMessageUi> by derivedStateOf {
        if (pinnedEventIds.isEmpty()) return@derivedStateOf emptyList()
        val eventsById = allEvents.associateBy { it.eventId }
        pinnedEventIds.mapNotNull { pinnedId ->
            val event = eventsById[pinnedId] ?: pinnedResolvedEvents[pinnedId]
            if (event != null && !event.isDisplayableAsPinnedEvent()) return@mapNotNull null
            PinnedMessageUi(eventId = pinnedId, event = event)
        }
    }
}

@Serializable
enum class AttachmentUploadStage {
    Preparing,
    Uploading,
    Sending,
}



data class ForwardableRoom(
    val roomId: String,
    val name: String,
    val avatarUrl: String?,
    val isDm: Boolean,
    val lastActivity: Long,
)

data class VerificationRequestUi(
    val flowId: String,
    val fromUser: String,
    val fromDevice: String,
    val timestamp: Long = nowMs()
)

data class SecurityUiState(
    val devices: List<DeviceSummary> = emptyList(),
    val isLoadingDevices: Boolean = false,
    val error: String? = null,
    val accountManagementUrl: String? = null,

    // Tabs
    val selectedTab: Int = 0,

    // Recovery
    val recoveryState: MatrixPort.RecoveryState = MatrixPort.RecoveryState.Disabled,
    val backupState: MatrixPort.BackupState = MatrixPort.BackupState.Unknown,
    val backupExistsOnServer: Boolean? = null,
    val isKeyStorageEnabled: Boolean? = null,
    val isTogglingKeyStorage: Boolean = false,
    val isEnablingRecovery: Boolean = false,
    val recoveryProgress: String? = null,
    val generatedRecoveryKey: String? = null,
    val recoveryKeyInput: String = "",
    val isSubmittingRecoveryKey: Boolean = false,
    val recoverySubmitSuccess: Boolean = false,

    // Verification
    val pendingVerifications: List<VerificationRequestUi> = emptyList(),
    val sasFlowId: String? = null,
    val sasPhase: SasPhase? = null,
    val sasEmojis: List<String> = emptyList(),
    val sasOtherUser: String? = null,
    val sasOtherDevice: String? = null,
    val sasError: String? = null,
    val sasIncoming: Boolean = false,
    val sasContinuePressed: Boolean = false,

    // Misc
    val ignoredUsers: List<String> = emptyList(),
    val enableShareHistoryOnInvite: Boolean = true,

    // Profile
    val ownProfile: OwnProfile? = null,
    val isLoadingProfile: Boolean = false,
    val isSavingProfile: Boolean = false,
    val ownAvatarPath: String? = null,
    val canSetProfileFields: Boolean = false,
    val profileFields: List<ProfileField> = emptyList(),
    val isSavingProfileField: Boolean = false,
)

sealed interface AvatarEdit {
    data object None : AvatarEdit
    data class Replace(val path: String, val mime: String) : AvatarEdit
    data object Remove : AvatarEdit
}

data class SpacesUiState(
    val spaces: List<SpaceInfo> = emptyList(),
    val filteredSpaces: List<SpaceInfo> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val avatarPathByRoomId: Map<String, String> = emptyMap(),
    val unreadSpaceIds: Set<String> = emptySet(),

    // Create space
    val showCreateSpace: Boolean = false,
    val createName: String = "",
    val createTopic: String = "",
    val createIsPublic: Boolean = false,
    val createInvitees: List<String> = emptyList(),
    val isCreating: Boolean = false,
)

data class SpaceDetailUiState(
    val spaceId: String,
    val spaceName: String,
    val space: SpaceInfo? = null,
    val hierarchy: List<SpaceChildInfo> = emptyList(),
    val subspaces: List<SpaceChildInfo> = emptyList(),
    val rooms: List<SpaceChildInfo> = emptyList(),
    val nextBatch: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val avatarPathByRoomId: Map<String, String> = emptyMap(),
    val spaceAvatarPath: String? = null,
)

data class SpaceSettingsUiState(
    val spaceId: String,
    val space: SpaceInfo? = null,
    val children: List<SpaceChildInfo> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val avatarPathByRoomId: Map<String, String> = emptyMap(),
    val spaceAvatarPath: String? = null,

    // Permissions (from roomInfoSnapshot on the space)
    val canManageSettings: Boolean = false,
    val canEditDetails: Boolean = false,

    // People & roles
    val members: List<MemberSummary> = emptyList(),
    val bannedMembers: List<MemberSummary> = emptyList(),
    val powerLevels: RoomPowerLevels? = null,
    val myUserId: String? = null,
    val myPowerLevel: Long = 0L,

    // Security
    val joinRule: RoomJoinRule? = null,
    val joinRuleAllowedSpaceIds: List<String> = emptyList(),
    val selectableSpaces: List<SpaceInfo> = emptyList(),

    // Dialogs
    val showLeaveConfirm: Boolean = false,

    // Edit details
    val showEditDetails: Boolean = false,
    val editName: String = "",
    val editTopic: String = "",
    val editAlias: String = "",

    // People / roles / security sheets
    val showPeople: Boolean = false,
    val showRoles: Boolean = false,
    val showJoinRulePicker: Boolean = false,
    val pendingJoinRule: RoomJoinRule? = null,
    val selectedMember: MemberSummary? = null,

    // Leave with children
    val showLeaveWithChildren: Boolean = false,
    val joinedChildren: List<SpaceChildInfo> = emptyList(),
    val selectedChildIds: Set<String> = emptySet(),
)

data class SpaceActionsUiState(
    val spaceId: String,
    val canManageChildren: Boolean = false,
    val spaceChildReason: String? = null,
    val canInvite: Boolean = false,
    val inviteReason: String? = null,
    val isSaving: Boolean = false,

    val joinedRooms: List<RoomSummary> = emptyList(),
    val excludedChildIds: Set<String> = emptySet(),

    // Create room in space
    val showCreateRoom: Boolean = false,
    val newRoomName: String = "",
    val newRoomTopic: String = "",
    val newRoomIsPublic: Boolean = false,

    // Add existing room
    val showAddRoom: Boolean = false,

    // Invite user
    val showInviteUser: Boolean = false,
    val inviteUserId: String = "",
) {
    val addableRooms: List<RoomSummary>
        get() = joinedRooms.filter { it.id !in excludedChildIds && it.id != spaceId }

    val hasAnyAction: Boolean
        get() = canManageChildren || canInvite
}

data class ThreadUiState(
    val roomId: String = "",
    val rootEventId: String = "",
    val roomName: String = "",

    val rootMessage: MessageEvent? = null,
    val replies: List<MessageEvent> = emptyList(),

    val nextBatch: String? = null,
    val hasInitialLoad: Boolean = false,

    val isLoading: Boolean = false,
    val error: String? = null,

    val input: String = "",
    val replyingTo: MessageEvent? = null,

    val editingEvent: MessageEvent? = null,
    val editInput: String = "",
    val avatarByUserId: Map<String, String> = emptyMap(),
    val replyThumbByEvent: Map<String, String> = emptyMap(),
    val thumbByEvent: Map<String, String> = emptyMap(),
    val roomMembers: List<MemberSummary> = emptyList(),
    val focusedEventId: String? = null,
    val focusedEventMissing: Boolean = false,
    val imagePacks: List<ImagePackSummary> = emptyList(),
    val emotePathByMxc: Map<String, String> = emptyMap(),
    val reactionImagePathByMxc: Map<String, String> = emptyMap(),
    /** See [RoomUiState.linkPreviewByEvent]. */
    val linkPreviewByEvent: Map<String, LinkPreview?> = emptyMap(),
    val linkPreviewImageByEvent: Map<String, String> = emptyMap(),
    val isRoomEncrypted: Boolean = false,
) {
    val messageCount: Int get() = (if (rootMessage != null) 1 else 0) + replies.size

    val allMessages: List<MessageEvent> get() = listOfNotNull(rootMessage) + replies
}

enum class LastMessageType {
    Text,
    Image,
    Video,
    Audio,
    File,
    Sticker,
    Location,
    Poll,
    Call,
    Encrypted,
    Redacted,
    Membership,
    Unknown
}

/**
 * UI model, contains only what the UI needs (preview text, unread, etc).
 */
data class RoomListItemUi(
    val roomId: String,
    val name: String,
    val avatarUrl: String? = null,
    val isDm: Boolean = false,
    val isEncrypted: Boolean = false,

    val unreadCount: Int = 0,
    val hasUnreadMessages: Boolean = false,
    val isMarkedUnread: Boolean = false,
    val isFavourite: Boolean = false,
    val isLowPriority: Boolean = false,
    val isInvited: Boolean = false,

    val lastMessageBody: String? = null,
    val lastMessageLabel: StringResource? = null,
    val lastMessageSender: String? = null,
    val lastMessageType: LastMessageType = LastMessageType.Text,
    val lastMessageTs: Long? = null,

    val isSharingLocation: Boolean = false,

    val parentSpaces: List<SpaceBadgeUi> = emptyList(),
)

data class SpaceBadgeUi(
    val spaceId: String,
    val name: String? = null,
    val avatarUrl: String? = null,
)

data class SearchUiState(
    val query: String = "",
    val error: String? = null,

    // For scoped search
    val scopedRoomId: String? = null,
    val scopedRoomName: String? = null
)

/**
 * One pack as the editor sees it. A pack with an empty [stateKey] is one this
 * session has staged but not yet written, and carries its images in
 * [pendingImages] until the first save allocates a key.
 */
data class PackEditorEntry(
    val stateKey: String = "",
    val displayName: String = "",
    val usage: List<String> = emptyList(),
    val images: List<ImagePackImageEntry> = emptyList(),
    /** Staged additions, uploaded on save rather than held as bytes. */
    val pendingImages: List<PendingPackImage> = emptyList(),
    val isEnabledGlobally: Boolean = false
) {
    val isNew: Boolean get() = stateKey.isEmpty()
    val imageCount: Int get() = images.size + pendingImages.size
}

/** An image the user picked but has not uploaded yet. */
data class PendingPackImage(
    val localId: String,
    val path: String,
    val mime: String,
    val shortcode: String = "",
    /** Set when the shortcode the user typed is not one the spec accepts. */
    val shortcodeError: StringResource? = null,
    val previewPath: String? = null
)

data class ImagePackEditorUiState(
    val isLoading: Boolean = true,
    /** False when the user's power level is below the bar for room state. */
    val canEdit: Boolean = false,
    val packs: List<PackEditorEntry> = emptyList(),
    val stateKeysBeingSaved: Set<String> = emptySet(),
    val isUploading: Boolean = false,
    /** 0f..1f across the pending images of the save in flight. */
    val uploadProgress: Float = 0f,
    val hasUnsavedChanges: Boolean = false
)
