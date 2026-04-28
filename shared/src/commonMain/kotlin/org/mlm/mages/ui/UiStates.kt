package org.mlm.mages.ui

import kotlinx.serialization.Serializable
import org.mlm.mages.MessageEvent
import org.mlm.mages.RoomSummary
import org.mlm.mages.matrix.DeviceSummary
import org.mlm.mages.matrix.HomeserverLoginDetails
import org.mlm.mages.matrix.LiveLocationShare
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.Presence
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.matrix.PasswordLoginKind
import org.mlm.mages.matrix.RoomPredecessorInfo
import org.mlm.mages.matrix.RoomUpgradeInfo
import org.mlm.mages.matrix.SasPhase
import org.mlm.mages.matrix.SearchHit
import org.mlm.mages.matrix.SeenByEntry
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.ui.components.AttachmentData
import org.mlm.mages.ui.util.nowMs


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
    val phoneCountry: String = ""
)

data class RoomsUiState(
    val rooms: List<RoomSummary> = emptyList(),
    val roomSearchQuery: String = "",
    val unread: Map<String, Int> = emptyMap(),
    val offlineBanner: String? = null,
    val syncBanner: String? = null,
    val unreadOnly: Boolean = false,
    val typeFilter: RoomTypeFilter = RoomTypeFilter.All,
    val isLoading: Boolean = false,
    val error: String? = null,
    val favourites: Set<String> = emptySet(),
    val lowPriority: Set<String> = emptySet(),

    val allItems: List<RoomListItemUi> = emptyList(),
    val favouriteItems: List<RoomListItemUi> = emptyList(),
    val normalItems: List<RoomListItemUi> = emptyList(),
    val lowPriorityItems: List<RoomListItemUi> = emptyList(),
    val inviteItems: List<RoomListItemUi> = emptyList(),

    val roomAvatarPath: Map<String, String> = emptyMap()
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

data class PinnedMessageUi(
    val eventId: String,
    val event: MessageEvent? = null,
) {
    val isResolved: Boolean get() = event != null
    val senderLabel: String? get() = event?.senderDisplayName ?: event?.sender
    val previewText: String get() = event?.body?.ifBlank { "Pinned message" } ?: "Pinned message"
    val timestampMs: Long? get() = event?.timestampMs
}

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
    val typingNames: List<String> = emptyList(),
    val isPaginatingBack: Boolean = false,
    val hasTimelineSnapshot: Boolean = false,
    val hitStart: Boolean = false,
    val isOffline: Boolean = false,
    val attachments: List<AttachmentData> = emptyList(),
    val isUploadingAttachment: Boolean = false,
    val uploadingFileName: String? = null,
    val attachmentProgress: Float = 0f,
    val attachmentUploadStage: AttachmentUploadStage? = null,
    val error: String? = null,

    val lastReadTs: Long? = null,
    val hasLoadedLastRead: Boolean = false,
    val isDm: Boolean = false,
    val lastIncomingFromOthersTs: Long? = null,
    val lastOutgoingRead: Boolean = false,

    val thumbByEvent: Map<String, String> = emptyMap(),
    val avatarByUserId: Map<String, String> = emptyMap(),
    val roomMembers: List<MemberSummary> = emptyList(),
    val threadCount: Map<String, Int> = emptyMap(),
    val audioFileByEvent: Map<String, String> = emptyMap(),
    val waveformByEvent: Map<String, List<Float>> = emptyMap(),

    val liveLocationShares: Map<String, LiveLocationShare> = emptyMap(),
    val liveLocationSubToken: ULong? = null,

    val notificationMode: RoomNotificationMode = RoomNotificationMode.AllMessages,
    val isLoadingNotificationMode: Boolean = false,

    val successor: RoomUpgradeInfo? = null,
    val predecessor: RoomPredecessorInfo? = null,

    val showAttachmentPicker: Boolean = false,
    val showPollCreator: Boolean = false,
    val showLiveLocation: Boolean = false,
    val showLiveLocationMap: Boolean = false,
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

    val isSelectionMode: Boolean = false,
    val selectedEventIds: Set<String> = emptySet(),

    val myPowerLevel: Long = 0L,

    val pinnedEventIds: List<String> = emptyList(),
    val showPinnedMessagesSheet: Boolean = false,

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
    val pinnedMessages: List<PinnedMessageUi>
        get() {
            if (pinnedEventIds.isEmpty()) return emptyList()
            val eventsById = allEvents.associateBy { it.eventId }
            return pinnedEventIds.map { pinnedId ->
                PinnedMessageUi(
                    eventId = pinnedId,
                    event = eventsById[pinnedId],
                )
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

data class PresenceUiState(
    val currentPresence: Presence = Presence.Online,
    val statusMessage: String = "",
    val isSaving: Boolean = false,
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

    // Privacy
    val ignoredUsers: List<String> = emptyList(),

    // Presence
    val presence: PresenceUiState = PresenceUiState(),
)

data class SpacesUiState(
    val spaces: List<SpaceInfo> = emptyList(),
    val filteredSpaces: List<SpaceInfo> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val avatarPathByRoomId: Map<String, String> = emptyMap(),

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
    val availableRooms: List<RoomSummary> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val avatarPathByRoomId: Map<String, String> = emptyMap(),
    val spaceAvatarPath: String? = null,

    // Dialogs
    val showAddRoom: Boolean = false,
    val showInviteUser: Boolean = false,
    val inviteUserId: String = "",
    val showLeaveConfirm: Boolean = false,
)

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
    val roomMembers: List<MemberSummary> = emptyList()
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
    val isFavourite: Boolean = false,
    val isLowPriority: Boolean = false,
    val isInvited: Boolean = false,

    val lastMessageBody: String? = null,
    val lastMessageSender: String? = null,
    val lastMessageType: LastMessageType = LastMessageType.Text,
    val lastMessageTs: Long? = null,
)

data class SearchUiState(
    val query: String = "",
    val error: String? = null,

    // For scoped search
    val scopedRoomId: String? = null,
    val scopedRoomName: String? = null
)
