@file:OptIn(ExperimentalWasmJsInterop::class)
@file:JsModule("./wasm/mages_bridge.js")

package org.mlm.mages.matrix

import kotlin.js.JsAny
import kotlin.js.JsBoolean
import kotlin.js.JsName
import kotlin.js.JsString
import kotlin.js.Promise

external interface WebRoomSummaryListValue : JsAny
external interface WebRoomListEntryValue : JsAny
external interface WebRoomListEntriesValue : JsAny
external interface WebSyncStatusValue : JsAny
external interface WebTimelineItemsValue : JsAny
external interface WebSendResultValue : JsAny
external interface WebHomeserverLoginDetailsValue : JsAny
external interface WebPublicRoomsPageValue : JsAny
external interface WebMembersValue : JsAny
external interface WebReactionsValue : JsAny
external interface WebTimelineSubscription : JsAny
external interface WebSendUpdateValue : JsAny
external interface WebSpaceHierarchyValue : JsAny
external interface WebSpacesValue : JsAny
external interface WebRoomUpgradeInfoValue : JsAny
external interface WebRoomPredecessorInfoValue : JsAny
external fun ensureMagesFfi(): Promise<JsAny?>

external class WebMatrixFacade {
    companion object {
        fun create(homeserverUrl: String, baseStoreDir: String, accountId: String? = definedExternally): Promise<JsAny?>
    }

    fun free()
    fun login(username: String, password: String, deviceDisplayName: String? = definedExternally): Promise<JsAny?>
    fun isLoggedIn(): Boolean
    @JsName("loginOauthLoopbackAvailable")
    fun loginOauthLoopbackAvailable(): Boolean
    @JsName("loginSsoLoopbackAvailable")
    fun loginSsoLoopbackAvailable(): Boolean
    @JsName("homeserverLoginDetails")
    fun homeserverLoginDetails(): WebHomeserverLoginDetailsValue?
    fun whoami(): String?

    fun listRooms(): Promise<WebRoomSummaryListValue?>

    @JsName("loadRoomListCache")
    fun loadRoomListCache(): WebRoomListEntriesValue?

    @JsName("observeRoomList")
    fun observeRoomList(onReset: (WebRoomListEntriesValue?) -> Unit, onUpdate: (WebRoomListEntryValue?) -> Unit): Double

    @JsName("unobserveRoomList")
    fun unobserveRoomList(id: Double): Boolean

    @JsName("setRoomListUnreadOnly")
    fun setRoomListUnreadOnly(id: Double, unreadOnly: Boolean): Boolean

    @JsName("sendQueueSetEnabled")
    fun sendQueueSetEnabled(enabled: Boolean): Boolean

    @JsName("roomSendQueueSetEnabled")
    fun roomSendQueueSetEnabled(roomId: String, enabled: Boolean): Boolean

    @JsName("observeSends")
    fun observeSends(onUpdate: (WebSendUpdateValue?) -> Unit): Double

    @JsName("unobserveSends")
    fun unobserveSends(id: Double): Boolean

    @JsName("startSync")
    fun startSync(onState: (WebSyncStatusValue?) -> Unit)

    @JsName("getRoomTimeline")
    fun getRoomTimeline(roomId: String, limit: Int = definedExternally): Promise<WebTimelineItemsValue?>

    @JsName("observeTimeline")
    fun observeTimeline(
        roomId: String,
        onDiff: (JsAny?) -> Unit,
        onError: (String?) -> Unit,
    ): WebTimelineSubscription

    @JsName("unobserveTimeline")
    fun unobserveTimeline(subscription: WebTimelineSubscription): Boolean

    @JsName("sendText")
    fun sendText(roomId: String, body: String, formattedBody: String? = definedExternally): Promise<WebSendResultValue?>

    @JsName("paginateBackwards")
    fun paginateBackwards(roomId: String, count: Int): Boolean

    @JsName("paginateForwards")
    fun paginateForwards(roomId: String, count: Int): Boolean

    @JsName("markRead")
    fun markRead(roomId: String): Boolean

    @JsName("markReadAt")
    fun markReadAt(roomId: String, eventId: String): Boolean

    fun react(roomId: String, eventId: String, emoji: String): Boolean
    fun reply(roomId: String, inReplyTo: String, body: String): Boolean
    fun edit(roomId: String, targetEventId: String, newBody: String): Boolean
    fun redact(roomId: String, eventId: String, reason: String? = definedExternally): Boolean

    @JsName("setTyping")
    fun setTyping(roomId: String, typing: Boolean): Boolean

    @JsName("observeTyping")
    fun observeTyping(roomId: String, onUpdate: (JsAny?) -> Unit): Double

    @JsName("unobserveTyping")
    fun unobserveTyping(id: Double): Boolean

    @JsName("reactionsForEvent")
    fun reactionsForEvent(roomId: String, eventId: String): WebReactionsValue?

    @JsName("reactionsBatch")
    fun reactionsBatch(roomId: String, eventIdsJson: String): WebReactionsValue?

    @JsName("publicRooms")
    fun publicRooms(
        server: String? = definedExternally,
        search: String? = definedExternally,
        limit: Int,
        since: String? = definedExternally,
    ): Promise<WebPublicRoomsPageValue?>

    @JsName("joinByIdOrAlias")
    fun joinByIdOrAlias(idOrAlias: String): Boolean

    @JsName("listInvited")
    fun listInvited(): Promise<JsAny?>

    @JsName("acceptInvite")
    fun acceptInvite(roomId: String): Boolean

    @JsName("leaveRoom")
    fun leaveRoom(roomId: String): Boolean

    @JsName("createRoom")
    fun createRoom(
        name: String? = definedExternally,
        topic: String? = definedExternally,
        invitees: JsAny,
        isPublic: Boolean,
        roomAlias: String? = definedExternally,
    ): String?

    @JsName("setRoomName")
    fun setRoomName(roomId: String, name: String): Boolean

    @JsName("setRoomTopic")
    fun setRoomTopic(roomId: String, topic: String): Boolean

    @JsName("roomProfile")
    fun roomProfile(roomId: String): Promise<JsAny?>

    @JsName("roomNotificationMode")
    fun roomNotificationMode(roomId: String): Promise<JsString?>

    @JsName("setRoomNotificationMode")
    fun setRoomNotificationMode(roomId: String, mode: String): Promise<JsBoolean>

    @JsName("ensureDm")
    fun ensureDm(userId: String): String?

    @JsName("resolveRoomId")
    fun resolveRoomId(idOrAlias: String): String?

    @JsName("listMembers")
    fun listMembers(roomId: String): Promise<WebMembersValue?>

    @JsName("roomPowerLevels")
    fun roomPowerLevels(roomId: String): JsAny?

    @JsName("getUserPowerLevel")
    fun getUserPowerLevel(roomId: String, userId: String): Promise<JsAny?>

    @JsName("canUserBan")
    fun canUserBan(roomId: String, userId: String): Boolean

    @JsName("canUserInvite")
    fun canUserInvite(roomId: String, userId: String): Boolean

    @JsName("canUserRedactOther")
    fun canUserRedactOther(roomId: String, userId: String): Boolean

    @JsName("updatePowerLevelForUser")
    fun updatePowerLevelForUser(roomId: String, userId: String, powerLevel: Double): Boolean

    @JsName("applyPowerLevelChanges")
    fun applyPowerLevelChanges(roomId: String, changesJson: String): Boolean

    @JsName("reportContent")
    fun reportContent(roomId: String, eventId: String, score: Int? = definedExternally, reason: String? = definedExternally): Boolean

    @JsName("reportRoom")
    fun reportRoom(roomId: String, reason: String? = definedExternally): Boolean

    @JsName("banUser")
    fun banUser(roomId: String, userId: String, reason: String? = definedExternally): Boolean

    @JsName("unbanUser")
    fun unbanUser(roomId: String, userId: String, reason: String? = definedExternally): Boolean

    @JsName("kickUser")
    fun kickUser(roomId: String, userId: String, reason: String? = definedExternally): Boolean

    @JsName("inviteUser")
    fun inviteUser(roomId: String, userId: String): Boolean

    @JsName("enableRoomEncryption")
    fun enableRoomEncryption(roomId: String): Boolean

    @JsName("mySpaces")
    fun mySpaces(): WebSpacesValue?

    @JsName("createSpace")
    fun createSpace(name: String, topic: String? = definedExternally, isPublic: Boolean, invitees: JsAny): String?

    @JsName("spaceAddChild")
    fun spaceAddChild(spaceId: String, childRoomId: String, order: String? = definedExternally, suggested: Boolean? = definedExternally): Boolean

    @JsName("spaceRemoveChild")
    fun spaceRemoveChild(spaceId: String, childRoomId: String): Boolean

    @JsName("spaceHierarchy")
    fun spaceHierarchy(spaceId: String, from: String? = definedExternally, limit: Int, maxDepth: Int? = definedExternally, suggestedOnly: Boolean): WebSpaceHierarchyValue?

    @JsName("spaceInviteUser")
    fun spaceInviteUser(spaceId: String, userId: String): Boolean

    @JsName("sendAttachmentBytes")
    fun sendAttachmentBytes(roomId: String, filename: String, mime: String, data: JsAny): Boolean

    @JsName("sendExistingAttachment")
    fun sendExistingAttachment(roomId: String, attachmentJson: String, body: String? = definedExternally): Boolean

    @JsName("downloadAttachmentToCacheFile")
    fun downloadAttachmentToCacheFile(infoJson: String, filenameHint: String? = definedExternally): String?

    @JsName("thumbnailToCache")
    fun thumbnailToCache(infoJson: String, width: Int, height: Int, crop: Boolean): String?

    @JsName("mxcThumbnailToCache")
    fun mxcThumbnailToCache(mxcUri: String, width: Int, height: Int, crop: Boolean): String?

    @JsName("roomSuccessor")
    fun roomSuccessor(roomId: String): WebRoomUpgradeInfoValue?

    @JsName("roomPredecessor")
    fun roomPredecessor(roomId: String): WebRoomPredecessorInfoValue?

    @JsName("roomTags")
    fun roomTags(roomId: String): JsAny?

    @JsName("setRoomFavourite")
    fun setRoomFavourite(roomId: String, favourite: Boolean): Boolean

    @JsName("setRoomLowPriority")
    fun setRoomLowPriority(roomId: String, lowPriority: Boolean): Boolean

    @JsName("fetchNotification")
    fun fetchNotification(roomId: String, eventId: String): JsAny?

    @JsName("roomUnreadStats")
    fun roomUnreadStats(roomId: String): JsAny?

    @JsName("ownLastRead")
    fun ownLastRead(roomId: String): JsAny?

    @JsName("markFullyReadAt")
    fun markFullyReadAt(roomId: String, eventId: String): Boolean

    @JsName("observeReceipts")
    fun observeReceipts(roomId: String, onChanged: () -> Unit): Double

    @JsName("observeOwnReceipt")
    fun observeOwnReceipt(roomId: String, onChanged: () -> Unit): Double

    @JsName("unobserveReceipts")
    fun unobserveReceipts(token: Double): Boolean

    @JsName("dmPeerUserId")
    fun dmPeerUserId(roomId: String): String?

    @JsName("isEventReadBy")
    fun isEventReadBy(roomId: String, eventId: String, userId: String): Boolean

    @JsName("searchUsers")
    fun searchUsers(term: String, limit: Int): JsAny?

    @JsName("getUserProfile")
    fun getUserProfile(userId: String): JsAny?

    @JsName("ignoreUser")
    fun ignoreUser(userId: String): Boolean

    @JsName("unignoreUser")
    fun unignoreUser(userId: String): Boolean

    @JsName("ignoredUsers")
    fun ignoredUsers(): JsAny?

    @JsName("getPinnedEvents")
    fun getPinnedEvents(roomId: String): JsAny?

    @JsName("setPinnedEvents")
    fun setPinnedEvents(roomId: String, eventIds: JsAny): Boolean

    @JsName("roomAliases")
    fun roomAliases(roomId: String): JsAny?

    @JsName("publishRoomAlias")
    fun publishRoomAlias(roomId: String, alias: String): Boolean

    @JsName("unpublishRoomAlias")
    fun unpublishRoomAlias(roomId: String, alias: String): Boolean

    @JsName("setRoomCanonicalAlias")
    fun setRoomCanonicalAlias(roomId: String, alias: String? = definedExternally, altAliases: JsAny): Boolean

    @JsName("roomJoinRule")
    fun roomJoinRule(roomId: String): String?

    @JsName("setRoomJoinRule")
    fun setRoomJoinRule(roomId: String, rule: String): Boolean

    @JsName("roomHistoryVisibility")
    fun roomHistoryVisibility(roomId: String): String?

    @JsName("setRoomHistoryVisibility")
    fun setRoomHistoryVisibility(roomId: String, visibility: String): Boolean

    @JsName("roomDirectoryVisibility")
    fun roomDirectoryVisibility(roomId: String): String?

    @JsName("setRoomDirectoryVisibility")
    fun setRoomDirectoryVisibility(roomId: String, visibility: String): Boolean

    @JsName("seenByForEvent")
    fun seenByForEvent(roomId: String, eventId: String, limit: Int): JsAny?

    @JsName("sendPollStart")
    fun sendPollStart(roomId: String, question: String, answers: JsAny, kind: String = definedExternally, maxSelections: Int = definedExternally): Boolean

    @JsName("sendPollResponse")
    fun sendPollResponse(roomId: String, pollEventId: String, answers: JsAny): Boolean

    @JsName("sendPollEnd")
    fun sendPollEnd(roomId: String, pollEventId: String): Boolean

    fun logout(): Promise<JsAny?>
}

@JsName("as_web_matrix_facade")
external fun asWebMatrixFacade(value: JsAny?): WebMatrixFacade
