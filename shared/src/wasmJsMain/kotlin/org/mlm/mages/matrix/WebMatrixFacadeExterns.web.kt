@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:JsModule("./wasm/mages_bridge.js")

package org.mlm.mages.matrix

import kotlin.js.JsAny
import kotlin.js.JsName
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
        onDiff: (String?) -> Unit,
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

    @JsName("ensureDm")
    fun ensureDm(userId: String): String?

    @JsName("resolveRoomId")
    fun resolveRoomId(idOrAlias: String): String?

    @JsName("listMembers")
    fun listMembers(roomId: String): WebMembersValue?

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

    fun logout(): Promise<JsAny?>
}

@JsName("as_web_matrix_facade")
external fun asWebMatrixFacade(value: JsAny?): WebMatrixFacade
