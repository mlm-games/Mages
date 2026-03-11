package org.mlm.mages.matrix

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import org.mlm.mages.AttachmentInfo
import org.mlm.mages.MessageEvent
import org.mlm.mages.RoomSummary

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
    var displayName: String?,
    var avatarUrl: String?,
    var tsMs: ULong?
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
    var nextOffset: UInt?
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
}

@Serializable
data class SendUpdate(
    val roomId: String,
    val txnId: String,
    val attempts: Int,
    val state: SendState,
    val eventId: String?,
    val error: String?
)

@Serializable
enum class RoomNotificationMode {
    AllMessages,
    MentionsAndKeywordsOnly,
    Mute
}

val RoomNotificationMode.displayName: String
    get() = when (this) {
        RoomNotificationMode.AllMessages -> "All messages"
        RoomNotificationMode.MentionsAndKeywordsOnly -> "Mentions only"
        RoomNotificationMode.Mute -> "Muted"
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
    val statusMsg: String?
)

@Serializable
enum class RoomDirectoryVisibility {
    Public,
    Private
}

@Serializable
data class RoomUpgradeInfo(
    val roomId: String,
    val reason: String?
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
    val isLive: Boolean
)

interface VerificationObserver {
    fun onPhase(flowId: String, phase: SasPhase)
    fun onEmojis(flowId: String, otherUser: String, otherDevice: String, emojis: List<String>)
    fun onError(flowId: String, message: String)
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
enum class NotificationKind {
    Message,
    CallRing,
    CallNotify,
    CallInvite,
    Invite,
    StateEvent
}

@Serializable
data class RenderedNotification(
    val roomId: String,
    val eventId: String,
    val roomName: String,
    val sender: String,
    val body: String,
    val isNoisy: Boolean,
    val hasMention: Boolean,
    val senderUserId: String,
    val tsMs: Long,
    val isDm: Boolean,
    val kind: NotificationKind,
    val expiresAtMs: Long?
)

@Serializable
data class UnreadStats(val messages: Long, val notifications: Long, val mentions: Long)
@Serializable
data class DirectoryUser(val userId: String, val displayName: String?, val avatarUrl: String?)
@Serializable
data class PublicRoom(val roomId: String, val name: String?, val topic: String?, val alias: String?, val avatarUrl: String?, val memberCount: Long, val worldReadable: Boolean, val guestCanJoin: Boolean)
@Serializable
data class PublicRoomsPage(val rooms: List<PublicRoom>, val nextBatch: String?, val prevBatch: String?)
@Serializable
data class RoomPreview(
    val roomId: String,
    val canonicalAlias: String?,
    val name: String?,
    val topic: String?,
    val avatarUrl: String?,
    val memberCount: Long,
    val worldReadable: Boolean?,
    val joinRule: RoomJoinRule?,
    val membership: RoomPreviewMembership?
)
@Serializable
data class RoomProfile(
    val roomId: String,
    val name: String,
    val topic: String?,
    val memberCount: Long,
    val isEncrypted: Boolean,
    val isDm: Boolean,
    val avatarUrl: String?,
    val canonicalAlias: String?,
    val altAliases: List<String>,
    val roomVersion: String?
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
    val spaceChild: Long
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
    val spaceChild: Long? = null
)

@Serializable
data class LatestRoomEvent(
    val eventId: String,
    val sender: String,
    val body: String?,
    val msgtype: String?,
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
    val displayName: String?,
    val avatarUrl: String?,
    val isMe: Boolean,
    val membership: String
)

@Serializable
data class KnockRequestSummary(
    val eventId: String,
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val reason: String?,
    val tsMs: Long?,
    val isSeen: Boolean,
)

@Serializable
data class ReactionSummary(val key: String, val count: Int, val mine: Boolean)

@Serializable
data class ThreadPage(
    val rootEventId: String,
    val roomId: String,
    val messages: List<MessageEvent>,
    val nextBatch: String?,
    val prevBatch: String?
)
@Serializable
data class ThreadSummary(val rootEventId: String, val roomId: String, val count: Long, val latestTsMs: Long?)

@Serializable
data class SpaceInfo(
    val roomId: String,
    val name: String,
    val topic: String?,
    val memberCount: Long,
    val isEncrypted: Boolean,
    val isPublic: Boolean,
    val avatarUrl: String? = null
)

@Serializable
data class SpaceChildInfo(
    val roomId: String,
    val name: String?,
    val topic: String?,
    val alias: String?,
    val avatarUrl: String?,
    val isSpace: Boolean,
    val memberCount: Long,
    val worldReadable: Boolean,
    val guestCanJoin: Boolean,
    val suggested: Boolean
)

@Serializable
data class SpaceHierarchyPage(
    val children: List<SpaceChildInfo>,
    val nextBatch: String?
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
    val widgetBaseUrl: String?,
    val parentUrl: String?,
)

interface CallWidgetObserver {
    fun onToWidget(message: String)
}

@Serializable
data class HomeserverLoginDetails(
    val supportsOauth: Boolean,
    val supportsSso: Boolean,
    val supportsPassword: Boolean,
)

interface MatrixPort {

    @Serializable
    data class SyncStatus(val phase: SyncPhase, val message: String?)
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

    suspend fun init(hs: String, accountId: String? = null)
    suspend fun login(user: String, password: String, deviceDisplayName: String?)
    suspend fun listRooms(): List<RoomSummary>
    suspend fun recent(roomId: String, limit: Int = 50): List<MessageEvent>
    fun timelineDiffs(roomId: String): Flow<TimelineDiff<MessageEvent>>
    suspend fun send(roomId: String, body: String, formattedBody: String? = null): Boolean

    suspend fun sendQueueSetEnabled(enabled: Boolean): Boolean
    suspend fun roomSendQueueSetEnabled(roomId: String, enabled: Boolean): Boolean

    suspend fun sendExistingAttachment(
        roomId: String,
        attachment: AttachmentInfo,
        body: String? = null,
        onProgress: ((Long, Long?) -> Unit)? = null
    ): Boolean

    fun isLoggedIn(): Boolean
    fun close()

    suspend fun setTyping(roomId: String, typing: Boolean): Boolean
    fun whoami(): String?
    fun accountManagementUrl(): String?
    fun setupRecovery(observer: RecoveryObserver): ULong
    fun observeRecoveryState(observer: RecoveryStateObserver): ULong
    fun unobserveRecoveryState(subId: ULong): Boolean

    fun observeBackupState(observer: BackupStateObserver): ULong
    fun unobserveBackupState(subId: ULong): Boolean

    suspend fun backupExistsOnServer(fetch: Boolean = false): Boolean
    suspend fun setKeyBackupEnabled(enabled: Boolean): Boolean

    suspend fun enqueueText(roomId: String, body: String, txnId: String? = null): String
    fun observeSends(): Flow<SendUpdate>

    suspend fun roomTags(roomId: String): Pair<Boolean, Boolean>?
    suspend fun setRoomFavourite(roomId: String, favourite: Boolean): Result<Unit>
    suspend fun setRoomLowPriority(roomId: String, lowPriority: Boolean): Result<Unit>

    suspend fun thumbnailToCache(info: AttachmentInfo, width: Int, height: Int, crop: Boolean): Result<String>

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

    fun observeConnection(observer: ConnectionObserver): ULong
    fun stopConnectionObserver(token: ULong)

    fun startVerificationInbox(observer: VerificationInboxObserver): ULong
    fun stopVerificationInbox(token: ULong)
    interface ConnectionObserver {
        fun onConnectionChange(state: ConnectionState)
    }

    suspend fun retryByTxn(roomId: String, txnId: String): Boolean

    fun stopTypingObserver(token: ULong)

    suspend fun paginateBack(roomId: String, count: Int): Boolean
    suspend fun paginateForward(roomId: String, count: Int): Boolean
    suspend fun markRead(roomId: String): Boolean
    suspend fun markReadAt(roomId: String, eventId: String): Boolean
    suspend fun react(roomId: String, eventId: String, emoji: String): Boolean
    suspend fun reply(roomId: String, inReplyToEventId: String, body: String, formattedBody: String? = null): Boolean
    suspend fun edit(roomId: String, targetEventId: String, newBody: String, formattedBody: String? = null): Boolean
    suspend fun redact(roomId: String, eventId: String, reason: String? = null): Boolean
    suspend fun getUserPowerLevel(roomId: String, userId: String): Long
    
    suspend fun getPinnedEvents(roomId: String): List<String>
    suspend fun setPinnedEvents(roomId: String, eventIds: List<String>): Boolean
    
    fun observeTyping(roomId: String, onUpdate: (List<String>) -> Unit): ULong

    fun startSupervisedSync(observer: SyncObserver)

    suspend fun listMyDevices(): List<DeviceSummary>

    suspend fun startSelfSas(targetDeviceId: String, observer: VerificationObserver): String
    suspend fun startUserSas(userId: String, observer: VerificationObserver): String

    suspend fun acceptVerificationRequest(flowId: String, otherUserId: String?, observer: VerificationObserver): Boolean
    suspend fun acceptSas(flowId: String, otherUserId: String?, observer: VerificationObserver): Boolean

    suspend fun confirmVerification(flowId: String): Boolean
    suspend fun cancelVerification(flowId: String): Boolean

    suspend fun cancelVerificationRequest(flowId: String, otherUserId: String?): Boolean

    fun enterForeground()
    fun enterBackground()

    suspend fun logout(): Boolean

    suspend fun checkVerificationRequest(userId: String, flowId: String): Boolean

    suspend fun sendAttachmentFromPath(
        roomId: String,
        path: String,
        mime: String,
        filename: String? = null,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Boolean

    suspend fun sendAttachmentBytes(
        roomId: String,
        data: ByteArray,
        mime: String,
        filename: String,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Boolean

    suspend fun downloadAttachmentToCache(
        info: AttachmentInfo,
        filenameHint: String? = null
    ): Result<String>

    suspend fun downloadAttachmentToPath(
        info: AttachmentInfo,
        savePath: String,
        onProgress: ((Long, Long?) -> Unit)? = null
    ): Result<String>

    suspend fun searchRoom(
        roomId: String,
        query: String,
        limit: Int = 50,
        offset: Int? = null
    ): SearchPage

    suspend fun recoverWithKey(recoveryKey: String): Boolean
    fun observeReceipts(roomId: String, observer: ReceiptsObserver): ULong
    fun stopReceiptsObserver(token: ULong)
    suspend fun dmPeerUserId(roomId: String): String?
    suspend fun isEventReadBy(roomId: String, eventId: String, userId: String): Boolean

    interface CallObserver { fun onInvite(invite: CallInvite) }
    fun startCallInbox(observer: CallObserver): ULong
    fun stopCallInbox(token: ULong)
    suspend fun registerUnifiedPush(appId: String, pushKey: String, gatewayUrl: String, deviceName: String, lang: String, profileTag: String? = null): Boolean
    suspend fun unregisterUnifiedPush(appId: String, pushKey: String): Boolean

    suspend fun roomUnreadStats(roomId: String): UnreadStats?
    suspend fun ownLastRead(roomId: String): Pair<String?, Long?>
    fun observeOwnReceipt(roomId: String, observer: ReceiptsObserver): ULong
    suspend fun markFullyReadAt(roomId: String, eventId: String): Boolean

    suspend fun encryptionCatchupOnce(): Boolean

    interface RoomListObserver { fun onReset(items: List<RoomListEntry>); fun onUpdate(item: RoomListEntry) }

    fun observeRoomList(observer: RoomListObserver): ULong
    fun unobserveRoomList(token: ULong)

    suspend fun fetchNotification(roomId: String, eventId: String): RenderedNotification?

    suspend fun fetchNotificationsSince(
        sinceMs: Long,
        maxRooms: Int = 50,
        maxEvents: Int = 20
    ): List<RenderedNotification>

    fun roomListSetUnreadOnly(token: ULong, unreadOnly: Boolean): Boolean

    suspend fun loginSsoLoopback(openUrl: (String) -> Boolean, deviceName: String? = null): Boolean

    suspend fun loginOauthLoopback(openUrl: (String) -> Boolean, deviceName: String? = null): Boolean

    suspend fun homeserverLoginDetails(): HomeserverLoginDetails

    suspend fun searchUsers(term: String, limit: Int = 20): List<DirectoryUser>
    suspend fun getUserProfile(userId: String): DirectoryUser?
    suspend fun publicRooms(server: String? = null, search: String? = null, limit: Int = 50, since: String? = null): PublicRoomsPage
    suspend fun roomPreview(idOrAlias: String): Result<RoomPreview>
    suspend fun joinByIdOrAlias(idOrAlias: String): Result<Unit>
    suspend fun knock(idOrAlias: String): Boolean
    suspend fun ensureDm(userId: String): String?
    suspend fun resolveRoomId(idOrAlias: String): String?

    suspend fun listInvited(): List<RoomProfile>
    suspend fun acceptInvite(roomId: String): Boolean
    suspend fun leaveRoom(roomId: String): Result<Unit>

    suspend fun createRoom(name: String?, topic: String?, invitees: List<String>, isPublic: Boolean, roomAlias: String?): String?
    suspend fun setRoomName(roomId: String, name: String): Result<Unit>
    suspend fun setRoomTopic(roomId: String, topic: String): Result<Unit>

    suspend fun roomProfile(roomId: String): RoomProfile?

    suspend fun roomNotificationMode(roomId: String): RoomNotificationMode?
    suspend fun setRoomNotificationMode(roomId: String, mode: RoomNotificationMode): Result<Unit>

    suspend fun listMembers(roomId: String): List<MemberSummary>
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
    ): Boolean
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
    ): Boolean
    suspend fun spaceRemoveChild(spaceId: String, childRoomId: String): Boolean
    suspend fun spaceHierarchy(
        spaceId: String,
        from: String?,
        limit: Int,
        maxDepth: Int?,
        suggestedOnly: Boolean
    ): SpaceHierarchyPage?
    suspend fun spaceInviteUser(spaceId: String, userId: String): Boolean

    suspend fun setPresence(presence: Presence, status: String?): Result<Unit>
    suspend fun getPresence(userId: String): Pair<Presence, String?>?

    suspend fun ignoreUser(userId: String): Result<Unit>
    suspend fun unignoreUser(userId: String): Result<Unit>
    suspend fun ignoredUsers(): List<String>

    suspend fun roomDirectoryVisibility(roomId: String): RoomDirectoryVisibility?
    suspend fun setRoomDirectoryVisibility(roomId: String, visibility: RoomDirectoryVisibility): Result<Unit>
    suspend fun publishRoomAlias(roomId: String, alias: String): Boolean
    suspend fun unpublishRoomAlias(roomId: String, alias: String): Boolean
    suspend fun setRoomCanonicalAlias(roomId: String, alias: String?, altAliases: List<String>): Result<Unit>
    suspend fun roomAliases(roomId: String): List<String>

    suspend fun roomJoinRule(roomId: String): RoomJoinRule?
    suspend fun setRoomJoinRule(roomId: String, rule: RoomJoinRule): Result<Unit>
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

    suspend fun startLiveLocationShare(roomId: String, durationMs: Long): Result<Unit>
    suspend fun stopLiveLocationShare(roomId: String): Result<Unit>
    suspend fun sendLiveLocation(roomId: String, geoUri: String): Result<Unit>
    fun observeLiveLocation(roomId: String, onShares: (List<LiveLocationShare>) -> Unit): ULong
    fun stopObserveLiveLocation(token: ULong)

    suspend fun sendPoll(roomId: String, question: String, answers: List<String>): Boolean

    fun seenByForEvent(roomId: String, eventId: String, limit: Int): List<SeenByEntry>

    suspend fun mxcThumbnailToCache(mxcUri: String, width: Int, height: Int, crop: Boolean): String
    suspend fun loadRoomListCache(): List<RoomListEntry>

    suspend fun sendPollResponse(roomId: String, pollEventId: String, answers: List<String>): Boolean
    suspend fun sendPollEnd(roomId: String, pollEventId: String): Boolean
    suspend fun startElementCall(
        roomId: String,
        intent: CallIntent,
        elementCallUrl: String? = null,
        parentUrl: String? = null,
        languageTag: String? = null,
        theme: String? = null,
        observer: CallWidgetObserver
    ): CallSession?


    fun callWidgetFromWebview(sessionId: ULong, message: String): Boolean
    fun stopElementCall(sessionId: ULong): Boolean

}

expect fun createMatrixPort(): MatrixPort
