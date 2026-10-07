package org.mlm.mages.matrix

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mages.shared.generated.resources.*
import org.mlm.mages.AttachmentInfo
import org.mlm.mages.AttachmentKind
import org.mlm.mages.EncFile
import org.mlm.mages.LinkPreview
import org.mlm.mages.MessageEvent
import org.mlm.mages.RoomSummary
import org.mlm.mages.StickerInfo
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.Res

@Serializable
data class DownloadResult (
    var path: String,
    var bytes: ULong
)
@Serializable
data class DeviceSummary(
    val deviceId: String,
    val displayName: String,
    val ed25519: String,
    val isOwn: Boolean,
    var verified: Boolean
)

@Serializable
data class SeenByEntry (
    var userId: String,
    var displayName: String? = null,
    var avatarUrl: String? = null,
    var tsMs: ULong? = null
)

@Serializable
data class SearchHit (
    var roomId: String,
    var eventId: String,
    var sender: String,
    var body: String,
    var timestampMs: ULong
)

@Serializable
data class SearchPage (
    var hits: List<SearchHit>,
    var nextOffset: UInt? = null
)

sealed class TimelineDiff<out T> {
    @Serializable
    data class Reset<T>(val items: List<T>) : TimelineDiff<T>()
    class Clear<T> : TimelineDiff<T>()

    @Serializable
    data class Append<T>(val items: List<T>) : TimelineDiff<T>()

    @Serializable
    data class UpdateByItemId<T>(val itemId: String, val item: T) : TimelineDiff<T>()
    @Serializable
    data class RemoveByItemId<T>(val itemId: String) : TimelineDiff<T>()
    @Serializable
    data class UpsertByItemId<T>(val itemId: String, val item: T) : TimelineDiff<T>()
    @Serializable
    data class Prepend<T>(val item: T) : TimelineDiff<T>()
}
@Serializable
enum class SasPhase { Created, Requested, Ready, Accepted, Started, Emojis, Confirmed, Cancelled, Failed, Done }

@Serializable
enum class SendState { Enqueued, Sending, Sent, Retrying, Failed }

@Serializable
enum class ActionPresentation { Hidden, Disabled, Enabled }

@Serializable
data class ActionAvailability(
    val presentation: ActionPresentation,
    val reason: String? = null,
) {
    val isEnabled: Boolean
        get() = presentation == ActionPresentation.Enabled
}

@Serializable
data class RoomActionState(
    val roomId: String,
    val voiceCall: ActionAvailability,
    val videoCall: ActionAvailability,
    val sendMessage: ActionAvailability,
    val sendReaction: ActionAvailability,
    val editName: ActionAvailability,
    val editTopic: ActionAvailability,
    val invite: ActionAvailability,
    val manageSettings: ActionAvailability,
    val spaceChild: ActionAvailability,
    val redactOthers: ActionAvailability,
    val pin: ActionAvailability,
)

@Serializable
data class MemberActionState(
    val roomId: String,
    val userId: String,
    val directMessage: ActionAvailability,
    val kick: ActionAvailability,
    val ban: ActionAvailability,
    val unban: ActionAvailability,
)

@Serializable
data class MessageActionState(
    val roomId: String,
    val eventId: String,
    val edit: ActionAvailability,
    val delete: ActionAvailability,
    val pin: ActionAvailability,
    val unpin: ActionAvailability,
    val react: ActionAvailability,
)

@Serializable
enum class EventType {
    Message,
    MembershipChange,
    ProfileChange,
    RoomName,
    RoomTopic,
    RoomAvatar,
    RoomEncryption,
    RoomPinnedEvents,
    RoomPowerLevels,
    RoomCanonicalAlias,
    OtherState,
    CallInvite,
    CallNotification,
    Poll,
    Sticker,
    LiveLocation,
    Location,
    Unsupported,
    Unknown,
}

@Serializable
data class SendUpdate(
    val roomId: String,
    val txnId: String,
    val attempts: Int,
    val state: SendState,
    val eventId: String? = null,
    val error: String? = null
)

@Serializable
enum class RoomNotificationMode {
    AllMessages,
    MentionsAndKeywordsOnly,
    Mute
}

@Serializable
enum class PushRuleKind {
    Override,
    Underride,
    Sender,
    Room,
    Content
}

@Composable
fun RoomNotificationMode.displayName(): String = when (this) {
    RoomNotificationMode.AllMessages -> stringResource(Res.string.all_messages)
    RoomNotificationMode.MentionsAndKeywordsOnly -> stringResource(Res.string.mentions_only)
    RoomNotificationMode.Mute -> stringResource(Res.string.muted)
}

@Serializable
enum class Presence {
    Online,
    Offline,
    Unavailable
}

@Serializable
data class PresenceInfo(
    val presence: Presence,
    val statusMsg: String? = null
)

@Serializable
enum class MediaPreviewMode {
    On,
    Private,
    Off
}

@Serializable
enum class RoomDirectoryVisibility {
    Public,
    Private
}

@Serializable
data class RoomUpgradeInfo(
    val roomId: String,
    val reason: String? = null
)

@Serializable
data class RoomPredecessorInfo(
    val roomId: String,
)

@Serializable
data class LiveLocationShare(
    val userId: String,
    val geoUri: String,
    val tsMs: Long,
    val isLive: Boolean,
    val beaconInfoEventId: String = "",
    val endTimestampMs: Long = 0L,
)

@Serializable
data class BeaconInfoUpdate(
    val roomId: String,
    val eventId: String,
    val live: Boolean
)

@Serializable
sealed class VerifEvent {
    @Serializable @SerialName("Requested")
    data class Requested(val flow_id: String) : VerifEvent()
    @Serializable @SerialName("Ready") data object Ready : VerifEvent()
    @Serializable @SerialName("SasStarted") data object SasStarted : VerifEvent()
    @Serializable @SerialName("KeysExchanged")
    data class KeysExchanged(
        val emojis: List<EmojiEntry>,
        val other_user: String,
        val other_device: String,
    ) : VerifEvent()
    @Serializable @SerialName("Confirmed") data object Confirmed : VerifEvent()
    @Serializable @SerialName("Done") data object Done : VerifEvent()
    @Serializable @SerialName("Cancelled")
    data class Cancelled(val reason: String) : VerifEvent()
    @Serializable @SerialName("Error")
    data class Error(val message: String) : VerifEvent()
}

@Serializable
data class EmojiEntry(val symbol: String, val description: String)

interface VerificationService {
    fun startDeviceVerification(deviceId: String): Flow<VerifEvent>
    fun startUserVerification(userId: String): Flow<VerifEvent>
    fun acceptAndObserveVerification(flowId: String, otherUserId: String): Flow<VerifEvent>
    suspend fun confirmSas(flowId: String, otherUserId: String? = null): Boolean
    suspend fun cancelVerification(flowId: String, otherUserId: String? = null): Boolean
}

interface ReceiptsObserver { fun onChanged() }

@Serializable
data class CallInvite(
    val roomId: String,
    val sender: String,
    val callId: String,
    val isVideo: Boolean,
    val tsMs: Long
)

@Serializable
data class RoomCallState(
    val hasActiveCall: Boolean = false,
    val activeParticipants: List<String> = emptyList()
)

@Serializable
data class RoomInfoSnapshot(
    val roomId: String,
    val profile: RoomProfile,
    val powerLevels: RoomPowerLevels,
    val actionState: RoomActionState,
    val callState: RoomCallState,
    val membership: RoomListMembership,
    val joinRule: RoomJoinRule? = null,
    val historyVisibility: RoomHistoryVisibility? = null,
    val pinnedEventIds: List<String>? = null
)

@Serializable
enum class NotificationKind {
    Message,
    Reaction,
    CallRing,
    CallNotify,
    CallInvite,
    Invite,
    StateEvent
}

@Serializable
enum class NotificationContentKind { Text, Media, Sticker, Poll, Location, Reaction, Call, Invite, Unknown }

/** Flat wire shape produced by the Rust classifier. */
@Serializable
data class NotificationContent(
    val kind: NotificationContentKind,
    val body: String = "",
    val formattedBody: String? = null,
    val attachmentKind: AttachmentKind? = null,
    val fileName: String? = null,
    val mxcUri: String? = null,
    val thumbnailMxcUri: String? = null,
    val encrypted: EncFile? = null,
    val thumbnailEncrypted: EncFile? = null,
    val mime: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val isVoice: Boolean? = null,
    val question: String? = null,
    val isEnd: Boolean? = null,
    val geoUri: String? = null,
    val isLive: Boolean? = null,
    val reactionKey: String? = null,
    val isInvite: Boolean? = null,
)

sealed interface ClassifiedNotification {
    data class Text(val body: String, val formattedBody: String?) : ClassifiedNotification
    data class Media(val attachment: AttachmentInfo, val body: String) : ClassifiedNotification
    data class Sticker(val sticker: StickerInfo) : ClassifiedNotification
    data class Poll(val question: String, val isEnd: Boolean) : ClassifiedNotification
    data class Location(val geoUri: String, val isLive: Boolean) : ClassifiedNotification
    data class Reaction(val key: String) : ClassifiedNotification
    data class Call(val invite: Boolean) : ClassifiedNotification
    data object Invite : ClassifiedNotification
    data object Unknown : ClassifiedNotification
}

fun NotificationContent.classify(): ClassifiedNotification = when (kind) {
    NotificationContentKind.Text ->
        ClassifiedNotification.Text(body, formattedBody)
    NotificationContentKind.Media -> ClassifiedNotification.Media(
        attachment = AttachmentInfo(
            kind = attachmentKind ?: AttachmentKind.File,
            mxcUri = mxcUri.orEmpty(),
            thumbnailMxcUri = thumbnailMxcUri,
            encrypted = encrypted,
            thumbnailEncrypted = thumbnailEncrypted,
            fileName = fileName,
            mime = mime,
            sizeBytes = sizeBytes,
            width = width,
            height = height,
            durationMs = durationMs,
            isVoice = isVoice,
        ),
        body = body
    )
    NotificationContentKind.Sticker -> ClassifiedNotification.Sticker(
        StickerInfo(
            mxcUri = mxcUri.orEmpty(),
            thumbnailMxcUri = thumbnailMxcUri,
            encrypted = encrypted,
            thumbnailEncrypted = thumbnailEncrypted,
            mime = mime,
            sizeBytes = sizeBytes,
            width = width,
            height = height,
        )
    )
    NotificationContentKind.Poll ->
        ClassifiedNotification.Poll(question.orEmpty(), isEnd == true)
    NotificationContentKind.Location ->
        ClassifiedNotification.Location(geoUri.orEmpty(), isLive == true)
    NotificationContentKind.Reaction -> ClassifiedNotification.Reaction(reactionKey.orEmpty())
    NotificationContentKind.Call -> ClassifiedNotification.Call(isInvite == true)
    NotificationContentKind.Invite -> ClassifiedNotification.Invite
    NotificationContentKind.Unknown -> ClassifiedNotification.Unknown
}

private fun formatDuration(ms: Long?): String? {
    val total = (ms ?: 0L) / 1000
    if (total <= 0L) return null
    val minutes = total / 60
    val seconds = total % 60
    return if (minutes > 0) "$minutes:${seconds.toString().padStart(2, '0')}"
    else "0:${seconds.toString().padStart(2, '0')}"
}

suspend fun notificationSummary(content: ClassifiedNotification): String = when (content) {
    is ClassifiedNotification.Text -> content.body
    is ClassifiedNotification.Media -> when (content.attachment.kind) {
        AttachmentKind.Image -> getString(Res.string.sent_an_image)
        AttachmentKind.Video -> getString(Res.string.sent_a_video)
        AttachmentKind.Audio -> {
            val duration = formatDuration(content.attachment.durationMs)
            if (content.attachment.isVoice == true && duration != null) {
                getString(Res.string.voice_message_with_duration, duration)
            } else if (content.attachment.isVoice == true) {
                getString(Res.string.voice_message)
            } else {
                getString(Res.string.sent_an_audio_message)
            }
        }
        AttachmentKind.File -> content.attachment.fileName
            ?.takeIf { it.isNotBlank() }
            ?.let { getString(Res.string.sent_named, it) }
            ?: getString(Res.string.sent_a_file)
    }
    is ClassifiedNotification.Sticker -> getString(Res.string.sent_a_sticker)
    is ClassifiedNotification.Poll -> {
        val summary = if (content.isEnd) getString(Res.string.ended_a_poll) else getString(Res.string.started_a_poll)
        if (content.question.isBlank()) summary
        else getString(Res.string.poll_summary_with_question, summary, content.question)
    }
    is ClassifiedNotification.Location -> when {
        content.isLive -> getString(Res.string.started_sharing_their_live_location)
        content.geoUri.isBlank() -> getString(Res.string.shared_a_location)
        else -> getString(Res.string.location_shared_at, content.geoUri)
    }
    // MSC4027 lets the key be an mxc URI, which shows up ugly in notifs.
    is ClassifiedNotification.Reaction ->
        if (content.key.startsWith("mxc://")) getString(Res.string.reacted_with_an_image)
        else getString(Res.string.reacted_with, content.key)
    is ClassifiedNotification.Call ->
        if (content.invite) getString(Res.string.incoming_call) else getString(Res.string.call_update)
    ClassifiedNotification.Invite -> getString(Res.string.room_invite)
    ClassifiedNotification.Unknown -> getString(Res.string.new_event)
}

@Serializable
data class RenderedNotification(
    val roomId: String,
    val eventId: String,
    val roomName: String,
    val sender: String,
    val content: NotificationContent,
    val isNoisy: Boolean,
    val hasMention: Boolean,
    val senderUserId: String,
    val tsMs: Long,
    val isDm: Boolean,
    val kind: NotificationKind,
    val expiresAtMs: Long? = null,
    val senderAvatarUrl: String? = null,
    val roomAvatarUrl: String? = null,
)

@Serializable
data class UnreadStats(val messages: Long, val notifications: Long, val mentions: Long)
@Serializable
data class DirectoryUser(val userId: String, val displayName: String? = null, val avatarUrl: String? = null)
@Serializable
data class PublicRoom(val roomId: String, val name: String? = null, val topic: String? = null, val alias: String? = null, val avatarUrl: String? = null, val memberCount: Long = 0, val worldReadable: Boolean = false, val guestCanJoin: Boolean = false)
@Serializable
data class PublicRoomsPage(val rooms: List<PublicRoom>, val nextBatch: String? = null, val prevBatch: String?)
@Serializable
data class RoomPreview(
    val roomId: String,
    val canonicalAlias: String? = null,
    val name: String? = null,
    val topic: String? = null,
    val avatarUrl: String? = null,
    val memberCount: Long,
    val worldReadable: Boolean? = null,
    val joinRule: RoomJoinRule? = null,
    val membership: RoomPreviewMembership? = null
)
@Serializable
data class RoomProfile(
    val roomId: String,
    val name: String,
    val topic: String? = null,
    val memberCount: Long = 0,
    val isEncrypted: Boolean = false,
    val isDm: Boolean = false,
    val isPublic: Boolean = false,
    val avatarUrl: String? = null,
    val canonicalAlias: String? = null,
    val altAliases: List<String> = emptyList(),
    val roomVersion: String? = null
)

@Serializable
enum class RoomJoinRule {
    Public,
    Invite,
    Knock,
    Restricted,
    KnockRestricted
}

@Serializable
enum class RoomListMembership {
    Joined,
    Invited,
    Left,
    Knocked,
    Banned,
}

@Serializable
enum class RoomPreviewMembership {
    Joined,
    Invited,
    Knocked,
    Left,
    Banned
}

@Serializable
enum class RoomHistoryVisibility {
    Invited,
    Joined,
    Shared,
    WorldReadable
}

@Serializable
enum class PasswordLoginKind {
    Username,
    Email,
    Phone,
}

@Serializable
data class RoomPowerLevels(
    val users: Map<String, Long>,
    val usersDefault: Long,
    val events: Map<String, Long>,
    val eventsDefault: Long,
    val stateDefault: Long,
    val ban: Long,
    val kick: Long,
    val redact: Long,
    val invite: Long,
    val roomName: Long,
    val roomAvatar: Long,
    val roomTopic: Long,
    val roomCanonicalAlias: Long,
    val roomHistoryVisibility: Long,
    val roomJoinRules: Long,
    val roomPowerLevels: Long,
    val spaceChild: Long,
   val beacon: Long,
   val beaconInfo: Long,

)

@Serializable
data class RoomPowerLevelChanges(
    val usersDefault: Long? = null,
    val eventsDefault: Long? = null,
    val stateDefault: Long? = null,
    val ban: Long? = null,
    val kick: Long? = null,
    val redact: Long? = null,
    val invite: Long? = null,
    val roomName: Long? = null,
    val roomAvatar: Long? = null,
    val roomTopic: Long? = null,
    val spaceChild: Long? = null,
    val beacon: Long? = null,
    val beaconInfo: Long? = null,
)

@Serializable
data class LatestRoomEvent(
    val eventId: String,
    val sender: String,
    val body: String? = null,
    val msgtype: String? = null,
    val eventType: String,
    val timestamp: Long,
    val isRedacted: Boolean,
    val isEncrypted: Boolean
)

@Serializable
data class RoomListEntry(
    val roomId: String,
    val name: String,
    val lastTs: ULong,
    val notifications: ULong,
    val messages: ULong,
    val mentions: ULong,
    val markedUnread: Boolean,
    val isFavourite: Boolean = false,
    val isLowPriority: Boolean = false,
    val isInvited: Boolean = false,
    val membership: RoomListMembership = RoomListMembership.Joined,

    val avatarUrl: String? = null,
    val isDm: Boolean = false,
    val isEncrypted: Boolean = false,
    val memberCount: Int = 0,
    val topic: String? = null,
    val latestEvent: LatestRoomEvent? = null,
)

@Serializable
data class MemberSummary(
    val userId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val isMe: Boolean = false,
    val membership: String = ""
)

@Serializable
data class KnockRequestSummary(
    val eventId: String,
    val userId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val reason: String? = null,
    val tsMs: Long? = null,
    val isSeen: Boolean,
)

@Serializable
data class ReactionSummary(
    val key: String,
    val count: Int,
    val mine: Boolean,
    @SerialName("user_ids") val userIds: List<String> = emptyList()
)

@Serializable
data class ThreadPage(
    val rootEventId: String,
    val roomId: String,
    val messages: List<MessageEvent>,
    val nextBatch: String? = null,
    val prevBatch: String? = null
)
@Serializable
data class ThreadSummary(val rootEventId: String, val roomId: String, val count: Long, val latestTsMs: Long?)

@Serializable
data class SpaceInfo(
    val roomId: String,
    val name: String,
    val topic: String? = null,
    val memberCount: Long,
    val isEncrypted: Boolean,
    val isPublic: Boolean,
    val avatarUrl: String? = null,
    val canonicalAlias: String? = null
)

@Serializable
data class SpaceChildInfo(
    val roomId: String,
    val name: String? = null,
    val topic: String? = null,
    val alias: String? = null,
    val avatarUrl: String? = null,
    val isSpace: Boolean,
    val memberCount: Long,
    val worldReadable: Boolean,
    val guestCanJoin: Boolean,
    val suggested: Boolean,
    val membership: RoomListMembership? = null,
    val sectionTag: String? = null
)

@Serializable
data class SpaceParentInfo(
    val spaceId: String,
    val name: String? = null,
    val avatarUrl: String? = null
)

@Serializable
data class SpaceSection(
    val tag: String,
    val name: String,
    val spaceId: String? = null
)

@Serializable
data class SpaceUnread(
    val spaceId: String,
    val unreadMessages: ULong,
    val unreadNotifications: ULong
)

@Serializable
data class RecentEmojiEntry(
    val emoji: String,
    val total: ULong = 0u
)

@Serializable
data class ImagePackImageEntry(
    val shortcode: String,
    val mxcUrl: String,
    val body: String? = null,
    /** The pack's own `info` object, forwarded verbatim when sending. */
    val infoJson: String? = null,
    val thumbnailMxcUri: String? = null,
    val isAnimated: Boolean? = null
)

@Serializable
data class ImagePackSummary(
    val packId: String,
    val sourceRoom: String,
    val sourceRoomName: String? = null,
    val stateKey: String = "",
    val displayName: String? = null,
    val avatarUrl: String? = null,
    /** Empty means the pack serves both stickers and emoticons. */
    val usage: List<String> = emptyList(),
    val attribution: String? = null,
    /** True when enabled globally via `m.image_pack.rooms`. */
    val isGlobal: Boolean = false,
    val images: List<ImagePackImageEntry> = emptyList()
) {
    fun servesStickers(): Boolean = usage.isEmpty() || "sticker" in usage
    fun servesEmoticons(): Boolean = usage.isEmpty() || "emoticon" in usage
}

@Serializable
data class ForwardResult(
    val sent: List<String>,
    val failed: List<String>
)

/** One image of a pack, as the editor stages it before saving. */
@Serializable
data class PackImageDraft(
    val shortcode: String,
    val mxcUrl: String,
    val body: String? = null,
    /** Serialised `ImageInfo`, carried into the pack's `info` verbatim. */
    val infoJson: String? = null,
    /** Local path, for the editor's own preview. Never sent. */
    val previewPath: String? = null
)

/**
 * The complete desired contents of one pack.
 *
 * This is a replacement, not a diff: an image absent from [images] is removed
 * from the pack.
 */
@Serializable
data class PackWrite(
    /** Empty allocates a free state key server-side. */
    val stateKey: String = "",
    val displayName: String = "",
    /** Empty means both stickers and emoticons, per the spec's default. */
    val usage: List<String> = emptyList(),
    val images: List<PackImageDraft> = emptyList()
)

/** An uploaded pack image, ready to be staged. */
@Serializable
data class UploadedPackImage(
    val mxcUrl: String,
    val infoJson: String = "{}"
)

@Serializable
data class OwnProfile(
    val userId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val canChangeDisplayName: Boolean = true,
    val canChangeAvatar: Boolean = true
)

/** One MSC4133 extended profile field, with its value as plain text. */
@Serializable
data class ProfileField(val name: String, val value: String)

/** MSC2666: rooms the local user and another user are both joined to. */
@Serializable
data class MutualRooms(val count: Long, val roomIds: List<String>)

@Serializable
data class SpaceHierarchyPage(
    val children: List<SpaceChildInfo>,
    val nextBatch: String? = null
)

@Serializable
data class PollData(
    val question: String,
    val kind: PollKind, // Disclosed or Undisclosed
    val maxSelections: Long,
    val options: List<PollOption>,
    val votes: Map<String, Int>, // OptionId -> Count
    val mySelections: List<String>, // List of OptionIds selected by me
    val isEnded: Boolean,
    val totalVotes: Long
)

@Serializable
data class PollOption(
    var id: String,
    var text: String,
    var votes: Long,
    var isSelected: Boolean,
    var isWinner: Boolean
)

@Serializable
enum class PollKind {
    Disclosed,
    Undisclosed
}

@Serializable
enum class CallIntent {
    StartCall,
    JoinExisting,
    StartCallVoiceDm,
    JoinExistingVoiceDm,
}

@Serializable
data class CallSession(
    val sessionId: ULong,
    val widgetUrl: String,
    val widgetBaseUrl: String? = null,
    val parentUrl: String? = null,
)

interface CallWidgetObserver {
    fun onToWidget(message: String)
}

@Serializable
data class HomeserverLoginDetails(
    val homeserverUrl: String,
    val supportsOauth: Boolean,
    val supportsSso: Boolean,
    val supportsPassword: Boolean,
)

interface MatrixPort {
    sealed interface OauthLoginResult {
        data object Completed : OauthLoginResult
        data object RedirectStarted : OauthLoginResult
        data class Failed(val message: String? = null) : OauthLoginResult
    }

    @Serializable
    data class SyncStatus(val phase: SyncPhase, val message: String? = null)
    @Serializable
    enum class SyncPhase { Idle, Running, BackingOff, Error }
    @Serializable
    enum class ConnectionState {
        Disconnected,
        Connecting,
        Connected,
        Syncing,
        Reconnecting
    }

    @Serializable
    enum class RecoveryState {
        Disabled,
        Enabled,
        Incomplete,
        Unknown
    }

    @Serializable
    enum class BackupState {
        Unknown,
        Creating,
        Enabling,
        Resuming,
        Enabled,
        Downloading,
        Disabling
    }

    interface SyncObserver { fun onState(status: SyncStatus) }

    suspend fun init(hs: String, accountId: String? = null, proxyUrl: String? = null, enableShareHistoryOnInvite: Boolean = true)
    suspend fun login(user: String, password: String, deviceDisplayName: String?)
    suspend fun loginEmail(email: String, password: String, deviceDisplayName: String?)
    suspend fun loginPhone(country: String, phone: String, password: String, deviceDisplayName: String?)
    suspend fun listRooms(): List<RoomSummary>
    suspend fun recent(roomId: String, limit: Int = 50): List<MessageEvent>
    suspend fun eventDetails(roomId: String, eventId: String): MessageEvent?
    fun timelineDiffs(roomId: String): Flow<TimelineDiff<MessageEvent>>
    suspend fun send(roomId: String, body: String, formattedBody: String? = null): Result<Unit>

    suspend fun sendQueueSetEnabled(enabled: Boolean): Result<Unit>

    suspend fun sendExistingAttachment(
        roomId: String,
        attachment: AttachmentInfo,
        body: String? = null,
        formattedBody: String? = null,
        onProgress: ((Long, Long?) -> Unit)? = null
    ): Result<Unit>

    fun isLoggedIn(): Boolean
    suspend fun isLoggedInSuspend(): Boolean
    fun close()

    suspend fun setTyping(roomId: String, typing: Boolean): Result<Unit>
    fun whoami(): String?
    suspend fun accountManagementUrl(): String?
    fun setupRecovery(observer: RecoveryObserver): Boolean
    suspend fun resetRecoveryKey(): Result<String>
    fun observeRecoveryState(observer: RecoveryStateObserver): ULong
    fun unobserveRecoveryState(subId: ULong)

    fun observeBackupState(observer: BackupStateObserver): ULong
    fun unobserveBackupState(subId: ULong)

    suspend fun backupExistsOnServer(fetch: Boolean = false): Boolean
    suspend fun setKeyBackupEnabled(enabled: Boolean): Boolean

    fun observeSends(): Flow<SendUpdate>

    suspend fun roomTags(roomId: String): Pair<Boolean, Boolean>?
    suspend fun setRoomFavourite(roomId: String, favourite: Boolean): Result<Unit>
    suspend fun setRoomLowPriority(roomId: String, lowPriority: Boolean): Result<Unit>

    suspend fun thumbnailToCache(
        info: AttachmentInfo,
        width: Int,
        height: Int,
        crop: Boolean,
        /** Ask the homeserver for an animated thumbnail (MSC2705). */
        animated: Boolean = true,
        /** Bytes allowed when the whole original has to be fetched instead of a thumbnail. 0 = uncapped. */
        maxBytes: Long = 0,
    ): Result<String>

    interface VerificationInboxObserver {
        fun onRequest(flowId: String, fromUser: String, fromDevice: String)
        fun onError(message: String)
    }

    interface RecoveryObserver {
        fun onProgress(step: String)
        fun onDone(recoveryKey: String)
        fun onError(message: String)
    }

    interface RecoveryStateObserver {
        fun onUpdate(state: RecoveryState)
    }

    interface BackupStateObserver {
        fun onUpdate(state: BackupState)
    }

    suspend fun observeConnection(observer: ConnectionObserver): ULong
    fun stopConnectionObserver(token: ULong)

    suspend fun startVerificationInbox(observer: VerificationInboxObserver): ULong
    fun stopVerificationInbox(token: ULong)
    interface ConnectionObserver {
        fun onConnectionChange(state: ConnectionState)
    }

    suspend fun retryByTxn(roomId: String, txnId: String): Boolean

    suspend fun cancelByTxn(roomId: String, txnId: String): Boolean

    fun stopTypingObserver(token: ULong)

    suspend fun paginateBack(roomId: String, count: Int): Result<Boolean>
    suspend fun paginateForward(roomId: String, count: Int): Result<Boolean>
    suspend fun markRead(roomId: String, sendPublicReceipt: Boolean = false): Result<Unit>
    suspend fun markReadAt(roomId: String, eventId: String, sendPublicReceipt: Boolean = false): Result<Unit>
    suspend fun setMarkUnread(roomId: String, unread: Boolean): Result<Unit>
    /**
     * Toggles a reaction keyed by [key]. When [key] is an mxc URI, [shortcode]
     * is sent as MSC4027's optional textual name alongside it.
     */
    suspend fun react(
        roomId: String,
        eventId: String,
        key: String,
        shortcode: String? = null
    ): Result<Unit>
    suspend fun reply(roomId: String, inReplyToEventId: String, body: String, formattedBody: String? = null): Result<Unit>
    suspend fun edit(roomId: String, targetEventId: String, newBody: String, formattedBody: String? = null): Result<Unit>
    suspend fun editCaption(
        roomId: String,
        targetEventId: String,
        caption: String?,
        formattedCaption: String? = null,
    ): Result<Unit>
    suspend fun editPoll(
        roomId: String,
        pollEventId: String,
        question: String,
        answers: List<String>,
        maxSelections: Int,
    ): Result<Unit>
    suspend fun redact(roomId: String, eventId: String, reason: String? = null): Result<Unit>
    suspend fun getUserPowerLevel(roomId: String, userId: String): Long

    suspend fun getPinnedEvents(roomId: String): List<String>?
    suspend fun setPinnedEvents(roomId: String, eventIds: List<String>): Result<Unit>

    suspend fun observeTyping(roomId: String, onUpdate: (List<String>) -> Unit): ULong

    suspend fun startSupervisedSync(observer: SyncObserver)

    suspend fun listMyDevices(): List<DeviceSummary>

    suspend fun isUserVerified(userId: String): Boolean

    fun enterForeground()
    fun enterBackground()
    fun resumeActiveUi()

    suspend fun logout(): Boolean

    suspend fun sendAttachmentFromPath(
        roomId: String,
        path: String,
        mime: String,
        filename: String? = null,
        caption: String? = null,
        formattedCaption: String? = null,
        replyToEventId: String? = null,
        voiceDurationMs: Long? = null,
        voiceWaveform: List<Float>? = null,
        isVoice: Boolean? = null,
        txnId: String? = null,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Boolean

    suspend fun sendStickerFromPath(
        roomId: String,
        path: String,
        mime: String,
        body: String,
        filename: String? = null,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Boolean

    suspend fun downloadStickerToCache(
        info: StickerInfo,
        filenameHint: String? = null,
    ): Result<String>

    suspend fun downloadAttachmentToCache(
        info: AttachmentInfo,
        filenameHint: String? = null
    ): Result<String>

    suspend fun searchRoom(
        roomId: String,
        query: String,
        limit: Int = 50,
        offset: Int? = null
    ): SearchPage

    suspend fun recoverWithKey(recoveryKey: String): Result<Unit>
    suspend fun observeReceipts(roomId: String, observer: ReceiptsObserver): ULong
    fun stopReceiptsObserver(token: ULong)
    suspend fun dmPeerUserId(roomId: String): String?
    suspend fun isEventReadBy(roomId: String, eventId: String, userId: String): Boolean

    interface CallObserver { fun onInvite(invite: CallInvite) }
    suspend fun startCallInbox(observer: CallObserver): ULong
    fun stopCallInbox(token: ULong)

    interface RoomCallStateObserver { fun onUpdate(state: RoomCallState) }
    suspend fun observeRoomCallState(roomId: String, observer: RoomCallStateObserver): ULong
    fun unobserveRoomCallState(token: ULong)

    interface RoomInfoObserver { fun onUpdate(snapshot: RoomInfoSnapshot) }
    suspend fun roomInfoSnapshot(roomId: String): RoomInfoSnapshot?
    suspend fun observeRoomInfo(roomId: String, observer: RoomInfoObserver): ULong
    fun unobserveRoomInfo(token: ULong)

    interface CallDeclineObserver { fun onDecline(declinerUserId: String) }
    suspend fun observeCallDecline(roomId: String, notificationEventId: String, observer: CallDeclineObserver): ULong
    fun unobserveCallDecline(token: ULong)
    suspend fun registerUnifiedPush(appId: String, pushKey: String, gatewayUrl: String, deviceName: String, lang: String, profileTag: String? = null): Boolean
    suspend fun unregisterUnifiedPush(appId: String, pushKey: String): Boolean

    suspend fun roomUnreadStats(roomId: String): UnreadStats?
    suspend fun ownLastRead(roomId: String): Pair<String?, Long?>
    suspend fun observeOwnReceipt(roomId: String, observer: ReceiptsObserver): ULong
    suspend fun markFullyReadAt(roomId: String, eventId: String, sendPublicReceipt: Boolean = true): Result<Unit>
    suspend fun markRoomSeenLatest(roomId: String, sendPublicReceipt: Boolean): Result<Boolean>

    interface RoomListObserver { fun onReset(items: List<RoomListEntry>); fun onUpdate(item: RoomListEntry) }

    suspend fun observeRoomList(observer: RoomListObserver): ULong
    fun unobserveRoomList(token: ULong)

    suspend fun fetchNotification(roomId: String, eventId: String): RenderedNotification?

    suspend fun fetchNotificationsSince(
        sinceMs: Long,
        maxRooms: Int = 50,
        maxEvents: Int = 20
    ): List<RenderedNotification>

    suspend fun roomListSetUnreadOnly(token: ULong, unreadOnly: Boolean): Boolean
    suspend fun roomListUpdateVisibleRange(token: ULong, range: List<Int>, threshold: Int): Result<Unit>

    suspend fun subscribeToVisibleRooms(roomIds: List<String>): Result<Unit>

    suspend fun loginSsoLoopback(openUrl: (String) -> Boolean, deviceName: String? = null): Result<Unit>

    suspend fun loginOauthLoopback(openUrl: (String) -> Boolean, deviceName: String? = null): Result<Unit>

    suspend fun loginOauth(
        openUrl: (String) -> Boolean,
        deviceName: String? = null
    ): OauthLoginResult {
        val result = loginOauthLoopback(openUrl, deviceName)
        return if (result.isSuccess) {
            OauthLoginResult.Completed
        } else {
            OauthLoginResult.Failed(result.exceptionOrNull()?.message ?: getString(Res.string.oauth_failed_or_was_cancelled))
        }
    }

    suspend fun maybeFinishOauthRedirect(): Boolean = false

    suspend fun resumeOauthIfNeeded(): Boolean = maybeFinishOauthRedirect()

    suspend fun homeserverLoginDetails(): HomeserverLoginDetails

    suspend fun searchUsers(term: String, limit: Int = 20): List<DirectoryUser>
    suspend fun getUserProfile(userId: String): DirectoryUser?
    suspend fun mutualRooms(userId: String): MutualRooms?
    suspend fun publicRooms(server: String? = null, search: String? = null, limit: Int = 50, since: String? = null): PublicRoomsPage
    suspend fun roomPreview(idOrAlias: String, via: List<String> = emptyList()): Result<RoomPreview>
    suspend fun forwardEvent(sourceRoomId: String, eventId: String, targetRoomIds: List<String>): Result<ForwardResult>
    suspend fun joinByIdOrAlias(idOrAlias: String, via: List<String> = emptyList()): Result<Unit>
    suspend fun knock(idOrAlias: String, via: List<String> = emptyList()): Result<Unit>
    suspend fun ensureDm(userId: String): String?
    suspend fun ensureDmIfAllowed(roomId: String, userId: String): String?
    suspend fun resolveRoomId(idOrAlias: String): String?

    suspend fun roomActionState(roomId: String): RoomActionState?
    suspend fun memberActionState(roomId: String, userId: String): MemberActionState?
    suspend fun messageActionState(roomId: String, eventId: String, senderUserId: String): MessageActionState?

    suspend fun listInvited(): List<RoomProfile>
    suspend fun roomInviter(roomId: String): String?
    suspend fun acceptInvite(roomId: String): Result<Unit>
    suspend fun leaveRoom(roomId: String): Result<Unit>
    suspend fun declineCall(roomId: String, notificationEventId: String): Result<Unit>

    suspend fun createRoom(name: String?, topic: String?, invitees: List<String>, isPublic: Boolean, roomAlias: String?): String?
    suspend fun setRoomName(roomId: String, name: String): Result<Unit>
    suspend fun setRoomTopic(roomId: String, topic: String): Result<Unit>

    suspend fun roomProfile(roomId: String): RoomProfile?

    suspend fun roomNotificationMode(roomId: String): RoomNotificationMode?
    suspend fun setRoomNotificationMode(roomId: String, mode: RoomNotificationMode): Result<Unit>

    suspend fun isPushRuleEnabled(kind: PushRuleKind, ruleId: String): Result<Boolean>
    suspend fun setPushRuleEnabled(kind: PushRuleKind, ruleId: String, enabled: Boolean): Result<Unit>

    suspend fun isReactionNotificationsEnabled(): Result<Boolean>
    suspend fun setReactionNotificationsEnabled(enabled: Boolean): Result<Unit>

    suspend fun getDefaultRoomNotificationMode(isEncrypted: Boolean, isOneToOne: Boolean): Result<RoomNotificationMode>
    suspend fun setDefaultRoomNotificationMode(
        isEncrypted: Boolean,
        isOneToOne: Boolean,
        mode: RoomNotificationMode
    ): Result<Unit>

    suspend fun listMembers(roomId: String): List<MemberSummary>
    suspend fun listBannedMembers(roomId: String): List<MemberSummary>
    suspend fun listKnockRequests(roomId: String): List<KnockRequestSummary>

    suspend fun reactions(roomId: String, eventId: String): List<ReactionSummary>
    suspend fun reactionsBatch(
        roomId: String,
        eventIds: List<String>
    ): Map<String, List<ReactionSummary>>

    suspend fun sendThreadText(
        roomId: String,
        rootEventId: String,
        body: String,
        replyToEventId: String? = null,
        latestEventId: String? = null,
        formattedBody: String? = null,
    ): Result<Unit>
    suspend fun threadSummary(roomId: String, rootEventId: String, perPage: Int = 100, maxPages: Int = 10): ThreadSummary

    suspend fun threadReplies(
        roomId: String,
        rootEventId: String,
        from: String? = null,
        limit: Int = 50,
        forward: Boolean = false
    ): ThreadPage

    suspend fun isSpace(roomId: String): Boolean
    suspend fun mySpaces(): List<SpaceInfo>
    suspend fun roomParentSpaces(roomId: String): List<SpaceParentInfo>
    suspend fun spaceUnreadCounts(): List<SpaceUnread>

    suspend fun listImagePacks(roomId: String): List<ImagePackSummary>

    suspend fun listAllImagePacks(refresh: Boolean): List<ImagePackSummary>

    /**
     * Adds or removes one pack from `m.image_pack.rooms`, so its images become
     * available in every room. Disabling the last pack of a room also drops
     * that room's now-empty entry.
     */
    suspend fun setImagePackEnabled(
        roomId: String,
        stateKey: String,
        enabled: Boolean
    ): Result<Unit>

    /**
     * Whether the signed-in user may write `m.room.image_pack` in this room.
     *
     * The spec is silent on permissions, so ordinary state-event auth applies
     * and the bar is `state_default` (50) unless the room lowers it. False on
     * any uncertainty, so the editor opens read-only rather than failing a save.
     */
    suspend fun canEditImagePacks(roomId: String): Boolean

    /**
     * Creates or replaces one of a room's packs, returning the state key it
     * landed under.
     *
     * [write] replaces the pack's contents wholesale rather than merging, which
     * is what makes a removed image actually disappear. An empty
     * [PackWrite.stateKey] allocates a free one.
     */
    suspend fun saveImagePack(roomId: String, write: PackWrite): Result<String>

    /**
     * Empties a pack's `images` map. A state event cannot be deleted, only
     * emptied, and the spec defines an empty pack as its removal.
     */
    suspend fun removeImagePack(roomId: String, stateKey: String): Result<Unit>

    /**
     * Resolves collision-free shortcodes for a batch of images being added to
     * one pack, reserving each result as it is produced. Passing a whole batch
     * in one call is what stops a multi-file drop from resolving every file to
     * the same shortcode.
     */
    suspend fun suggestImageShortcodes(bases: List<String>, taken: List<String>): List<String>

    /**
     * Uploads one image for a pack and describes it for the pack's `info`.
     *
     * Pack media is never encrypted: the spec puts E2EE of packs explicitly
     * out of scope, so this is always a plain upload. On web a picked file is
     * staged as a blob rather than a path on disk, so [path] is whatever the
     * picker produced and the platform resolves it.
     */
    suspend fun uploadPackImage(path: String, mime: String): Result<UploadedPackImage>

    /** `m.recent_emoji`, most recently used first. */
    suspend fun recentEmoji(): List<RecentEmojiEntry>

    /** Records one use of [emoji], reordering and truncating as the spec asks. */
    suspend fun recordEmojiUse(emoji: String): Result<Unit>

    /** Downloads a pack image into the media cache and returns a local path. */
    suspend fun packImageToCache(
        mxcUrl: String,
        width: Int,
        height: Int
    ): Result<String>

    /**
     * Sends a pack image as an `m.sticker`, reusing its mxc URI and `info`
     * verbatim. [threadRootEventId] must be set to thread the sticker: ruma
     * models sticker content as an opaque catch-all, so the thread relation
     * cannot be inferred and has to be attached explicitly.
     */
    suspend fun sendStickerMxc(
        roomId: String,
        mxcUrl: String,
        body: String,
        infoJson: String?,
        threadRootEventId: String?
    ): Boolean
    suspend fun createSpace(
        name: String,
        topic: String?,
        isPublic: Boolean,
        invitees: List<String>
    ): String?
    suspend fun spaceAddChild(
        spaceId: String,
        childRoomId: String,
        order: String?,
        suggested: Boolean?
    ): Result<Unit>
    suspend fun spaceRemoveChild(spaceId: String, childRoomId: String): Result<Unit>
    suspend fun spaceHierarchy(
        spaceId: String,
        from: String?,
        limit: Int,
        maxDepth: Int?,
        suggestedOnly: Boolean
    ): SpaceHierarchyPage?
    suspend fun spaceInviteUser(spaceId: String, userId: String): Result<Unit>

    suspend fun listSections(): List<SpaceSection>
    suspend fun createSection(name: String, spaceId: String): SpaceSection?
    suspend fun renameSection(tag: String, name: String): Result<Unit>
    suspend fun deleteSection(tag: String): Result<Unit>
    suspend fun moveSection(tag: String, index: Int): Result<Unit>
    suspend fun setRoomSection(roomId: String, tag: String?): Result<Unit>

    suspend fun setPresence(presence: Presence, status: String?): Result<Unit>

    suspend fun ownProfile(): OwnProfile?

    suspend fun setDisplayName(name: String?): Result<Unit>

    suspend fun setAvatarFromPath(path: String, mime: String): Result<String>

    /** Uploads arbitrary bytes and returns the resulting `mxc://` URI. */
    suspend fun uploadBytes(bytes: ByteArray, mime: String): Result<String>

    suspend fun removeAvatar(): Result<Unit>

    /** MSC4133: false when the homeserver has no `m.profile_fields` capability. */
    suspend fun canSetProfileFields(): Boolean
    suspend fun ownProfileFields(): List<ProfileField>
    suspend fun setProfileField(name: String, value: String): Result<Unit>
    suspend fun deleteProfileField(name: String): Result<Unit>

    suspend fun applySyncPresence(presence: Presence)
    suspend fun mediaPreviewConfig(): MediaPreviewMode?
    suspend fun setMediaPreviewConfig(previews: MediaPreviewMode): Result<Unit>

    /** Null when the account has never chosen, which must not overwrite the local value. */
    suspend fun inviteBlocked(): Boolean?
    suspend fun setInviteBlocked(blocked: Boolean): Result<Unit>
    suspend fun getPresence(userId: String): Pair<Presence, String?>?

    suspend fun ignoreUser(userId: String): Result<Unit>
    suspend fun unignoreUser(userId: String): Result<Unit>
    suspend fun ignoredUsers(): List<String>

    suspend fun roomDirectoryVisibility(roomId: String): RoomDirectoryVisibility?
    suspend fun setRoomDirectoryVisibility(roomId: String, visibility: RoomDirectoryVisibility): Result<Unit>
    suspend fun publishRoomAlias(roomId: String, alias: String): Result<Unit>
    suspend fun unpublishRoomAlias(roomId: String, alias: String): Result<Unit>
    suspend fun setRoomCanonicalAlias(roomId: String, alias: String?, altAliases: List<String>): Result<Unit>
    suspend fun roomAliases(roomId: String): List<String>

    suspend fun roomJoinRule(roomId: String): RoomJoinRule?
    suspend fun roomJoinRuleAllowList(roomId: String): List<String>
    suspend fun setRoomJoinRule(
        roomId: String,
        rule: RoomJoinRule,
        allowedRoomIds: List<String> = emptyList(),
    ): Result<Unit>
    suspend fun roomHistoryVisibility(roomId: String): RoomHistoryVisibility?
    suspend fun setRoomHistoryVisibility(roomId: String, visibility: RoomHistoryVisibility): Result<Unit>

    suspend fun roomPowerLevels(roomId: String): RoomPowerLevels?
    suspend fun canUserBan(roomId: String, userId: String): Boolean
    suspend fun canUserInvite(roomId: String, userId: String): Boolean
    suspend fun canUserRedactOther(roomId: String, userId: String): Boolean
    suspend fun updatePowerLevelForUser(roomId: String, userId: String, powerLevel: Long): Result<Unit>
    suspend fun applyPowerLevelChanges(roomId: String, changes: RoomPowerLevelChanges): Result<Unit>

    suspend fun reportContent(roomId: String, eventId: String, score: Int?, reason: String?): Result<Unit>
    suspend fun reportRoom(roomId: String, reason: String?): Result<Unit>

    suspend fun banUser(roomId: String, userId: String, reason: String? = null): Result<Unit>
    suspend fun unbanUser(roomId: String, userId: String, reason: String? = null): Result<Unit>
    suspend fun kickUser(roomId: String, userId: String, reason: String? = null): Result<Unit>
    suspend fun acceptKnockRequest(roomId: String, userId: String): Result<Unit>
    suspend fun declineKnockRequest(roomId: String, userId: String, reason: String? = null): Result<Unit>
    suspend fun inviteUser(roomId: String, userId: String): Result<Unit>
    suspend fun enableRoomEncryption(roomId: String): Result<Unit>

    suspend fun roomSuccessor(roomId: String): RoomUpgradeInfo?
    suspend fun roomPredecessor(roomId: String): RoomPredecessorInfo?

    suspend fun startLiveLocationShare(roomId: String, durationMs: Long): Result<String>
    suspend fun stopLiveLocationShare(roomId: String): Result<Unit>
    suspend fun sendLiveLocation(roomId: String, geoUri: String): Result<Unit>
    suspend fun sendStaticLocation(roomId: String, geoUri: String, body: String? = null): Result<Unit>
    suspend fun observeLiveLocation(roomId: String, onShares: (List<LiveLocationShare>) -> Unit): ULong
    fun stopObserveLiveLocation(token: ULong)

    fun subscribeToOwnBeaconInfoUpdates(onUpdate: (BeaconInfoUpdate) -> Unit): ULong
    fun unsubscribeFromOwnBeaconInfoUpdates(token: ULong)

    suspend fun sendPoll(roomId: String, question: String, answers: List<String>, maxSelections: Int = 1): Result<Unit>

    suspend fun seenByForEvent(roomId: String, eventId: String, limit: Int): List<SeenByEntry>

    suspend fun mxcThumbnailToCache(mxcUri: String, width: Int, height: Int, crop: Boolean): String

    /** `null` when the homeserver has no preview worth showing for the URL. */
    suspend fun getLinkPreview(url: String): LinkPreview?

    suspend fun loadRoomListCache(): List<RoomListEntry>

    suspend fun sendPollResponse(roomId: String, pollEventId: String, answers: List<String>): Result<Unit>
    suspend fun sendPollEnd(roomId: String, pollEventId: String): Result<Unit>
    suspend fun startElementCall(
        roomId: String,
        intent: CallIntent,
        elementCallUrl: String? = null,
        parentUrl: String? = null,
        languageTag: String? = null,
        theme: String? = null,
        observer: CallWidgetObserver
    ): CallSession?


    suspend fun callWidgetFromWebview(sessionId: ULong, message: String): Boolean
    fun stopElementCall(sessionId: ULong): Boolean

    suspend fun mediaCacheOverview(): MediaCacheOverview?
    suspend fun clearMediaCache(): Result<Unit>
}

@Serializable
data class MediaCacheOverview(
    val totalBytes: ULong,
)

expect fun createMatrixPort(): MatrixPort

fun MatrixPort.asVerificationService(): VerificationService? = this as? VerificationService
