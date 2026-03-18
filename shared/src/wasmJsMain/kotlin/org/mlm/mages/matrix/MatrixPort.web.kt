package org.mlm.mages.matrix

import io.github.vinceglb.filekit.utils.toJsArray
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.await
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import org.mlm.mages.AttachmentInfo
import org.mlm.mages.MessageEvent
import org.mlm.mages.RoomSummary
import org.mlm.mages.platform.clearWebBlob
import org.mlm.mages.platform.retrieveWebBlob
import org.mlm.mages.platform.navigatorOnLine
import org.w3c.dom.url.URL
import org.w3c.dom.events.Event
import kotlin.js.JsAny

// -- Callback fns since Kotlin/Wasm cannot pass lambdas as JsAny? --

@JsFun("(fn) => function() { fn(); }")
private external fun jsCallback0(fn: () -> Unit): JsAny

@JsFun("(fn) => function(a) { return fn(a); }")
private external fun jsCallback1(fn: (JsAny?) -> Unit): JsAny

@JsFun("(fn) => function(a) { return fn(a); }")
private external fun jsCallback1r(fn: (JsAny?) -> JsAny?): JsAny

@JsFun("(arr) => arr")
private external fun jsArrayPassthrough(arr: JsAny): JsAny

@Suppress("KotlinUnreachableCode")
fun List<String>.toJsArray(): JsAny {
    val jsArr = js("[]")
    for (item in this) {
        js("jsArr.push(item)")
    }
    return jsArr
}

@JsFun("(bytes) => new Uint8Array(bytes)")
private external fun bytesToUint8Array(bytes: JsAny): JsAny

fun ByteArray.toJsUint8Array(): JsAny = bytesToUint8Array(this.toJsReference())
// --

class WebStubMatrixPort : MatrixPort {
    private var client: WasmClient? = null
    private var currentHs: String? = null
    private var currentAccountId: String? = null
    private var isInForeground: Boolean = true

    private var nextConnectionObserverToken: ULong = 1uL
    private val connectionObserverStops = mutableMapOf<ULong, () -> Unit>()

    companion object {
        private const val PENDING_OAUTH_HS_KEY = "mages_pending_oauth_hs_v1"
        private const val PENDING_OAUTH_ACCOUNT_ID_KEY = "mages_pending_oauth_account_id_v1"
    }

    private fun savePendingOauth(hs: String, accountId: String?) {
        val storage = window.localStorage
        storage.setItem(PENDING_OAUTH_HS_KEY, hs)
        if (accountId != null) {
            storage.setItem(PENDING_OAUTH_ACCOUNT_ID_KEY, accountId)
        } else {
            storage.removeItem(PENDING_OAUTH_ACCOUNT_ID_KEY)
        }
    }

    private fun loadPendingOauth(): Pair<String, String?>? {
        val storage = window.localStorage
        val hs = storage.getItem(PENDING_OAUTH_HS_KEY) ?: return null
        val accountId = storage.getItem(PENDING_OAUTH_ACCOUNT_ID_KEY)
        return hs to accountId
    }

    private fun clearPendingOauth() {
        val storage = window.localStorage
        storage.removeItem(PENDING_OAUTH_HS_KEY)
        storage.removeItem(PENDING_OAUTH_ACCOUNT_ID_KEY)
    }

    private fun requireClient(): WasmClient {
        return client ?: throw IllegalStateException("Matrix client not initialized. Wait for init call.")
    }

    private fun requireClientOrNull(): WasmClient? = client

    private fun decodeTimelineDiff(diffValue: JsAny?): TimelineDiff<MessageEvent>? {
        val obj = diffValue.toJsonElement() as? JsonObject ?: return null
        if (obj.isEmpty()) return null

        val entry = obj.entries.first()
        val key = entry.key
        val value = entry.value as? JsonObject

        return when (key) {
            "Reset" -> TimelineDiff.Reset(
                wasmJson.decodeFromJsonElement<List<MessageEvent>>(value?.get("values") ?: return null)
            )

            "Clear" -> TimelineDiff.Clear()

            "Append" -> TimelineDiff.Append(
                wasmJson.decodeFromJsonElement<List<MessageEvent>>(value?.get("values") ?: return null)
            )

            "PushBack" -> TimelineDiff.Append(
                listOf(wasmJson.decodeFromJsonElement<MessageEvent>(value?.get("value") ?: return null))
            )

            "PushFront" -> TimelineDiff.Prepend(
                wasmJson.decodeFromJsonElement<MessageEvent>(value?.get("value") ?: return null)
            )

            "UpdateByItemId" -> TimelineDiff.UpdateByItemId(
                itemId = (value?.get("item_id") as? JsonPrimitive)?.content ?: return null,
                item = wasmJson.decodeFromJsonElement<MessageEvent>(value["value"] ?: return null)
            )

            "RemoveByItemId" -> TimelineDiff.RemoveByItemId(
                itemId = (value?.get("item_id") as? JsonPrimitive)?.content ?: return null,
            )

            "UpsertByItemId" -> TimelineDiff.UpsertByItemId(
                itemId = (value?.get("item_id") as? JsonPrimitive)?.content ?: return null,
                item = wasmJson.decodeFromJsonElement<MessageEvent>(value["value"] ?: return null)
            )

            else -> null
        }
    }

    private inline fun <reified T> decodeValue(value: JsAny?): T =
        wasmJson.decodeFromJsonElement(value.toJsonElement())

    private inline fun <reified T> decodeValueOrNull(value: JsAny?, label: String = "decode"): T? =
        runCatching {
            println("$label raw = ${value.toJsonString()}")
            decodeValue<T>(value)
        }.onFailure {
            println("$label failed: ${it.message}")
        }.getOrNull()

    private inline fun <reified T : Enum<T>> decodeEnum(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    private fun unitResult(ok: Boolean, action: String): Result<Unit> =
        if (ok) Result.success(Unit) else Result.failure(IllegalStateException("Failed to $action"))

    private fun decodeStringList(value: JsAny?): List<String> =
        decodeValueOrNull<List<String>>(value) ?: emptyList()

    private fun decodeRoomTags(value: JsAny?): Pair<Boolean, Boolean>? {
        val obj = value.toJsonObject() ?: return null
        val favourite = obj["favourite"]?.let { it as? JsonPrimitive }?.content == "true"
        val lowPriority = obj["low_priority"]?.let { it as? JsonPrimitive }?.content == "true"
        return favourite to lowPriority
    }

    private fun decodeOwnLastRead(value: JsAny?): Pair<String?, Long?> {
        val obj = value.toJsonObject() ?: return null to null
        val eventPrimitive = obj["event_id"] as? JsonPrimitive
        val eventId = eventPrimitive?.content?.takeUnless { it == "null" }
        val tsPrimitive = obj["ts_ms"] as? JsonPrimitive
        val tsMs = tsPrimitive?.content?.toLongOrNull()
        return eventId to tsMs
    }

    private fun parseVerificationInboxPayload(value: JsAny?): Triple<String, String, String>? {
        val obj = value?.toJsonObject() ?: return null
        val flowId = (obj["flowId"] as? JsonPrimitive)?.content ?: return null
        val fromUser = (obj["fromUser"] as? JsonPrimitive)?.content ?: return null
        val fromDevice = (obj["fromDevice"] as? JsonPrimitive)?.content ?: ""
        return Triple(flowId, fromUser, fromDevice)
    }

    private fun parseVerificationPhasePayload(value: JsAny?): Pair<String, SasPhase>? {
        val obj = value?.toJsonObject() ?: return null
        val flowId = (obj["flowId"] as? JsonPrimitive)?.content ?: return null
        val phaseName = (obj["phase"] as? JsonPrimitive)?.content ?: return null
        val phase = decodeEnum<SasPhase>(phaseName) ?: return null
        return flowId to phase
    }

    private fun parseVerificationErrorPayload(value: JsAny?): Pair<String, String>? {
        val obj = value?.toJsonObject() ?: return null
        val flowId = (obj["flowId"] as? JsonPrimitive)?.content ?: return null
        val message = (obj["message"] as? JsonPrimitive)?.content ?: return null
        return flowId to message
    }

    private fun parseSasEmojisPayload(value: JsAny?): Pair<String, Triple<String, String, List<String>>>? {
        val obj = value?.toJsonObject() ?: return null
        val flowId = (obj["flow_id"] as? JsonPrimitive)?.content ?: return null
        val otherUser = (obj["other_user"] as? JsonPrimitive)?.content ?: return null
        val otherDevice = (obj["other_device"] as? JsonPrimitive)?.content ?: return null
        val emojis = runCatching {
            wasmJson.decodeFromJsonElement<List<String>>(obj["emojis"] ?: return null)
        }.getOrDefault(emptyList())
        return flowId to Triple(otherUser, otherDevice, emojis)
    }

    override suspend fun init(hs: String, accountId: String?) {
        if (currentHs == hs && currentAccountId == accountId && client != null) return

        ensureWasmBridgeReady()
        client?.free()
        client = createWasmClient(
            hs,
            org.mlm.mages.platform.MagesPaths.storeDir(),
            accountId,
        )
        currentHs = hs
        currentAccountId = accountId
    }

    override suspend fun login(user: String, password: String, deviceDisplayName: String?) {
        val result = requireClient().loginAsync(user, password, deviceDisplayName).await<String?>()
        val error = result?.takeIf { it.isNotBlank() }
        if (error != null) {
            throw IllegalStateException(error)
        }
        if (!isLoggedIn()) {
            throw IllegalStateException("Login failed")
        }
    }

    override suspend fun listRooms(): List<RoomSummary> =
        run {
            val raw = requireClient().rooms().await<JsAny?>()
            val arr = raw.toJsonArray()
            wasmJson.decodeFromJsonElement<List<RoomSummary>>(arr)
        }

    override suspend fun recent(roomId: String, limit: Int): List<MessageEvent> {
        val raw = requireClient().recentEvents(roomId, limit.toDouble()).await<JsAny?>()
        return runCatching {
            println("recent raw = ${raw.toJsonString()}")
            wasmJson.decodeFromJsonElement<List<MessageEvent>>(raw.toJsonArray())
        }.onFailure {
            println("recent failed: ${it.message}")
        }.getOrElse { emptyList() }
    }

    override fun timelineDiffs(roomId: String): Flow<TimelineDiff<MessageEvent>> = callbackFlow {
        val f = requireClientOrNull() ?: run { close(); return@callbackFlow }
        val subscription = f.observeTimeline(
            roomId,
            jsCallback1 { diffValue: JsAny? ->
                runCatching {
                    println("timeline diff raw = ${diffValue.toJsonString()}")
                    decodeTimelineDiff(diffValue)
                }.onFailure {
                    println("timeline diff decode failed: ${it.message}")
                }.getOrNull()?.let { diff ->
                    trySend(diff)
                }
            },
            jsCallback1 { error: JsAny? ->
                val message = error?.toString() ?: "Timeline error"
                println("timeline observer error = $message")
                close(IllegalStateException(message))
            }
        )
        awaitClose {
            requireClientOrNull()?.unobserveTimeline(subscription.toDouble())
        }
    }

    override suspend fun send(roomId: String, body: String, formattedBody: String?): Boolean {
        return requireClient().sendMessage(roomId, body).await() ?: false
    }

    override suspend fun sendQueueSetEnabled(enabled: Boolean): Boolean =
        requireClient().sendQueueSetEnabled(enabled)

    override suspend fun sendExistingAttachment(
        roomId: String,
        attachment: AttachmentInfo,
        body: String?,
        onProgress: ((Long, Long?) -> Unit)?
    ): Boolean = requireClient().sendExistingAttachment(roomId, wasmJson.encodeToString(attachment), body).await<JsBoolean>().toBoolean()

    override fun isLoggedIn(): Boolean = client?.isLoggedIn() == true

    override fun close() {
        try { client?.free() } catch (e: Exception) { }
        client = null
        currentHs = null
        currentAccountId = null
    }

    override suspend fun setTyping(roomId: String, typing: Boolean): Boolean =
        requireClient().setTyping(roomId, typing).await()

    override fun whoami(): String? = client?.whoami()

    override fun accountManagementUrl(): String? = null

    override fun setupRecovery(observer: MatrixPort.RecoveryObserver): ULong =
        requireClient().setupRecovery(
            jsCallback1 { v: JsAny? -> observer.onProgress(v?.toString() ?: "") },
            jsCallback1 { v: JsAny? -> observer.onDone(v?.toString() ?: "") },
            jsCallback1 { v: JsAny? -> observer.onError(v?.toString() ?: "Recovery error") }
        ).toULong()

    override fun observeRecoveryState(observer: MatrixPort.RecoveryStateObserver): ULong =
        requireClient().observeRecoveryState(
            jsCallback1 { stateValue: JsAny? ->
                val state = runCatching {
                    wasmJson.decodeFromJsonElement<MatrixPort.RecoveryState>(stateValue.toJsonElement())
                }.getOrNull() ?: return@jsCallback1
                observer.onUpdate(state)
            }
        ).toULong()

    override fun unobserveRecoveryState(subId: ULong): Boolean =
        requireClient().unobserveRecoveryState(subId.toDouble())

    override fun observeBackupState(observer: MatrixPort.BackupStateObserver): ULong =
        requireClient().observeBackupState(
            jsCallback1 { stateValue: JsAny? ->
                val state = runCatching {
                    wasmJson.decodeFromJsonElement<MatrixPort.BackupState>(stateValue.toJsonElement())
                }.getOrNull() ?: return@jsCallback1
                observer.onUpdate(state)
            }
        ).toULong()

    override fun unobserveBackupState(subId: ULong): Boolean =
        requireClient().unobserveBackupState(subId.toDouble())

    override suspend fun backupExistsOnServer(fetch: Boolean): Boolean =
        requireClient().backupExistsOnServer(fetch).await()

    override suspend fun setKeyBackupEnabled(enabled: Boolean): Boolean =
        requireClient().setKeyBackupEnabled(enabled).await()

    override fun observeSends(): Flow<SendUpdate> = callbackFlow {
        val f = requireClientOrNull() ?: run { close(); return@callbackFlow }
        val token = f.observeSends(
            jsCallback1 { updateValue: JsAny? ->
                val update = runCatching {
                    wasmJson.decodeFromJsonElement<SendUpdate>(updateValue.toJsonElement())
                }.getOrNull() ?: return@jsCallback1
                trySend(update)
            }
        )
        awaitClose { requireClientOrNull()?.unobserveSends(token.toDouble()) }
    }

    override suspend fun roomTags(roomId: String): Pair<Boolean, Boolean>? =
        decodeRoomTags(requireClient().roomTags(roomId))

    override suspend fun setRoomFavourite(roomId: String, favourite: Boolean): Result<Unit> =
        unitResult(
            requireClient().setRoomFavourite(roomId, favourite).await(),
            "update room favourite"
        )

    override suspend fun setRoomLowPriority(roomId: String, lowPriority: Boolean): Result<Unit> =
        unitResult(
            requireClient().setRoomLowPriority(roomId, lowPriority).await(),
            "update room priority"
        )

    override suspend fun thumbnailToCache(
        info: AttachmentInfo,
        width: Int,
        height: Int,
        crop: Boolean
    ): Result<String> = runCatching {
        requireClient().thumbnailToCache(wasmJson.encodeToString(info), width.toDouble(), height.toDouble(), crop).await<String?>()
            .orEmpty()
    }

    override fun observeConnection(observer: MatrixPort.ConnectionObserver): ULong {
        fun emit() {
            val connected = navigatorOnLine()

            observer.onConnectionChange(
                if (connected) {
                    MatrixPort.ConnectionState.Connected
                } else {
                    MatrixPort.ConnectionState.Disconnected
                }
            )
        }

        val onlineHandler: (Event) -> Unit = { emit() }
        val offlineHandler: (Event) -> Unit = { emit() }

        window.addEventListener("online", onlineHandler)
        window.addEventListener("offline", offlineHandler)

        val token = nextConnectionObserverToken++
        connectionObserverStops[token] = {
            window.removeEventListener("online", onlineHandler)
            window.removeEventListener("offline", offlineHandler)
        }

        emit()
        return token
    }

    override fun stopConnectionObserver(token: ULong) {
        connectionObserverStops.remove(token)?.invoke()
    }

    override fun startVerificationInbox(observer: MatrixPort.VerificationInboxObserver): ULong =
        requireClient().startVerificationInbox(
            jsCallback1 { payload: JsAny? ->
                parseVerificationInboxPayload(payload)?.let { (flowId, fromUser, fromDevice) ->
                    observer.onRequest(flowId, fromUser, fromDevice)
                }
            },
            jsCallback1 { message: JsAny? ->
                observer.onError(message?.toString() ?: "Verification inbox error")
            }
        ).toULong()

    override fun stopVerificationInbox(token: ULong) {
        requireClient().unobserveVerificationInbox(token.toDouble())
    }

    override suspend fun retryByTxn(roomId: String, txnId: String): Boolean = false

    override fun stopTypingObserver(token: ULong) {
        requireClient().unobserveTyping(token.toDouble())
    }

    override suspend fun paginateBack(roomId: String, count: Int): Boolean =
        requireClient().paginateBackwards(roomId, count.toDouble()).await()

    override suspend fun paginateForward(roomId: String, count: Int): Boolean =
        requireClient().paginateForwards(roomId, count.toDouble()).await()

    override suspend fun markRead(roomId: String): Boolean =
        requireClient().markRead(roomId).await()

    override suspend fun markReadAt(roomId: String, eventId: String): Boolean =
        requireClient().markReadAt(roomId, eventId).await()

    override suspend fun markFullyReadAt(roomId: String, eventId: String): Boolean =
        requireClient().markFullyReadAt(roomId, eventId).await()

    override suspend fun react(roomId: String, eventId: String, emoji: String): Boolean =
        requireClient().react(roomId, eventId, emoji).await()

    override suspend fun reply(
        roomId: String,
        inReplyToEventId: String,
        body: String,
        formattedBody: String?
    ): Boolean =
        requireClient().reply(roomId, inReplyToEventId, body).await()

    override suspend fun edit(
        roomId: String,
        targetEventId: String,
        newBody: String,
        formattedBody: String?
    ): Boolean =
        requireClient().edit(roomId, targetEventId, newBody).await()

    override suspend fun redact(roomId: String, eventId: String, reason: String?): Boolean =
        requireClient().redact(roomId, eventId, reason).await()

    override suspend fun getUserPowerLevel(roomId: String, userId: String): Long =
        requireClient()
            .getUserPowerLevel(roomId, userId)
            .await<JsAny?>()
            .toString()
            .toDoubleOrNull()
            ?.toLong()
            ?: 0L

    override suspend fun getPinnedEvents(roomId: String): List<String> =
        decodeStringList(requireClient().getPinnedEvents(roomId))

    override suspend fun setPinnedEvents(roomId: String, eventIds: List<String>): Boolean =
        requireClient().setPinnedEvents(roomId, eventIds.toJsArray())

    override fun observeTyping(roomId: String, onUpdate: (List<String>) -> Unit): ULong =
        requireClient().observeTyping(
            roomId,
            jsCallback1 { users: JsAny? ->
                val parsed = runCatching {
                    wasmJson.decodeFromJsonElement<List<String>>(users.toJsonElement())
                }.getOrDefault(emptyList())
                onUpdate(parsed)
            }
        ).toULong()

    override fun startSupervisedSync(observer: MatrixPort.SyncObserver) {
        client?.startSupervisedSync(
            jsCallback1 { stateValue: JsAny? ->
                val state = runCatching {
                    wasmJson.decodeFromJsonElement<MatrixPort.SyncStatus>(stateValue.toJsonElement())
                }.getOrElse {
                    MatrixPort.SyncStatus(MatrixPort.SyncPhase.Error, it.message)
                }
                observer.onState(state)
            }
        )
    }

    override suspend fun listMyDevices(): List<DeviceSummary> =
        decodeValueOrNull(requireClient().listMyDevices().await(), "listMyDevices") ?: emptyList()

    override suspend fun startSelfSas(targetDeviceId: String, observer: VerificationObserver): String =
        requireClient().startSelfSas(
            targetDeviceId,
            jsCallback1 { payload: JsAny? ->
                parseVerificationPhasePayload(payload)?.let { (flowId, phase) ->
                    observer.onPhase(flowId, phase)
                }
            },
            jsCallback1 { payload: JsAny? ->
                parseSasEmojisPayload(payload)?.let { (flowId, triple) ->
                    observer.onEmojis(flowId, triple.first, triple.second, triple.third)
                }
            },
            jsCallback1 { payload: JsAny? ->
                parseVerificationErrorPayload(payload)?.let { (flowId, message) ->
                    observer.onError(flowId, message)
                }
            }
        ).await<String?>().orEmpty()

    override suspend fun startUserSas(userId: String, observer: VerificationObserver): String =
        requireClient().startUserSas(
            userId,
            jsCallback1 { payload: JsAny? ->
                parseVerificationPhasePayload(payload)?.let { (flowId, phase) ->
                    observer.onPhase(flowId, phase)
                }
            },
            jsCallback1 { payload: JsAny? ->
                parseSasEmojisPayload(payload)?.let { (flowId, triple) ->
                    observer.onEmojis(flowId, triple.first, triple.second, triple.third)
                }
            },
            jsCallback1 { payload: JsAny? ->
                parseVerificationErrorPayload(payload)?.let { (flowId, message) ->
                    observer.onError(flowId, message)
                }
            }
        ).await<String?>().orEmpty()

    override suspend fun acceptVerificationRequest(
        flowId: String,
        otherUserId: String?,
        observer: VerificationObserver
    ): Boolean =
        requireClient().acceptVerificationRequest(
            flowId,
            otherUserId,
            jsCallback1 { payload: JsAny? ->
                parseVerificationPhasePayload(payload)?.let { (fid, phase) ->
                    observer.onPhase(fid, phase)
                }
            },
            jsCallback1 { payload: JsAny? ->
                parseSasEmojisPayload(payload)?.let { (fid, triple) ->
                    observer.onEmojis(fid, triple.first, triple.second, triple.third)
                }
            },
            jsCallback1 { payload: JsAny? ->
                parseVerificationErrorPayload(payload)?.let { (fid, message) ->
                    observer.onError(fid, message)
                }
            }
        ).await()

    override suspend fun acceptSas(
        flowId: String,
        otherUserId: String?,
        observer: VerificationObserver
    ): Boolean =
        requireClient().acceptSas(
            flowId,
            otherUserId,
            jsCallback1 { payload: JsAny? ->
                parseVerificationPhasePayload(payload)?.let { (fid, phase) ->
                    observer.onPhase(fid, phase)
                }
            },
            jsCallback1 { payload: JsAny? ->
                parseSasEmojisPayload(payload)?.let { (fid, triple) ->
                    observer.onEmojis(fid, triple.first, triple.second, triple.third)
                }
            },
            jsCallback1 { payload: JsAny? ->
                parseVerificationErrorPayload(payload)?.let { (fid, message) ->
                    observer.onError(fid, message)
                }
            }
        ).await()

    override suspend fun confirmVerification(flowId: String): Boolean =
        requireClient().confirmVerification(flowId).await()

    override suspend fun cancelVerification(flowId: String): Boolean =
        requireClient().cancelVerification(flowId).await()

    override suspend fun cancelVerificationRequest(flowId: String, otherUserId: String?): Boolean =
        requireClient().cancelVerificationRequest(flowId, otherUserId).await()

    override fun enterForeground() {
        isInForeground = true
    }

    override fun enterBackground() {
        isInForeground = false
    }

    override suspend fun logout(): Boolean {
        val f = requireClientOrNull() ?: return false
        return try {
            f.logout()
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun sendAttachmentFromPath(
        roomId: String,
        path: String,
        mime: String,
        filename: String?,
        onProgress: ((Long, Long?) -> Unit)?
    ): Boolean {
        val bytes = retrieveWebBlob(path)
        if (bytes != null) {
            clearWebBlob(path)
            return sendAttachmentBytes(roomId, bytes, mime, filename ?: path, onProgress)
        }
        return false
    }

    override suspend fun sendAttachmentBytes( // TODO: fix
        roomId: String,
        data: ByteArray,
        mime: String,
        filename: String,
        onProgress: ((Long, Long?) -> Unit)?
    ): Boolean = requireClient().sendAttachmentBytes(roomId, filename, mime, data.toJsArray()).await()

    override suspend fun downloadAttachmentToCache(
        info: AttachmentInfo,
        filenameHint: String?
    ): Result<String> = runCatching {
        (requireClient().downloadAttachmentToCacheFile(wasmJson.encodeToString(info), filenameHint)
            ?: error("Attachment download failed"))
    }

    override suspend fun searchRoom(
        roomId: String,
        query: String,
        limit: Int,
        offset: Int?
    ): SearchPage = decodeValueOrNull(
        requireClient().searchRoom(roomId, query, limit.toDouble(), offset?.toDouble()),
        "searchRoom"
    ) ?: SearchPage(emptyList(), null)

    override suspend fun recoverWithKey(recoveryKey: String): Boolean =
        requireClient().recoverWithKey(recoveryKey).await()

    override fun observeReceipts(roomId: String, observer: ReceiptsObserver): ULong =
        requireClient().observeReceipts(roomId, jsCallback0 { observer.onChanged() }).toULong()

    override fun stopReceiptsObserver(token: ULong) {
        requireClient().unobserveReceipts(token.toDouble())
    }

    override suspend fun dmPeerUserId(roomId: String): String? =
        requireClient().dmPeerUserId(roomId).await<JsAny?>()?.toString()

    override suspend fun isEventReadBy(roomId: String, eventId: String, userId: String): Boolean =
        requireClient().isEventReadBy(roomId, eventId, userId).await()

    override fun startCallInbox(observer: MatrixPort.CallObserver): ULong =
        requireClient().startCallInbox(
            jsCallback1 { payload: JsAny? ->
                val invite = decodeValueOrNull<CallInvite>(payload, "startCallInbox")
                    ?: return@jsCallback1
                observer.onInvite(invite)
            }
        ).toULong()

    override fun stopCallInbox(token: ULong) {
        requireClient().stopCallInbox(token.toDouble())
    }

    override suspend fun registerUnifiedPush(
        appId: String,
        pushKey: String,
        gatewayUrl: String,
        deviceName: String,
        lang: String,
        profileTag: String?
    ): Boolean = false

    override suspend fun unregisterUnifiedPush(appId: String, pushKey: String): Boolean = false

    override suspend fun roomUnreadStats(roomId: String): UnreadStats? =
        decodeValueOrNull(requireClient().roomUnreadStats(roomId), "roomUnreadStats")

    override suspend fun ownLastRead(roomId: String): Pair<String?, Long?> =
        decodeOwnLastRead(requireClient().ownLastRead(roomId).await())

    override fun observeOwnReceipt(roomId: String, observer: ReceiptsObserver): ULong =
        requireClient().observeOwnReceipt(roomId, jsCallback0 { observer.onChanged() }).toULong()

    override fun observeRoomList(observer: MatrixPort.RoomListObserver): ULong {
        val token = requireClient().observeRoomList(
            jsCallback1 { itemsValue: JsAny? ->
                val raw = runCatching { itemsValue.toJsonArray() }.getOrElse {
                    JsonArray(emptyList())
                }
                val items = runCatching {
                    wasmJson.decodeFromJsonElement<List<RoomListEntry>>(raw)
                }.getOrDefault(emptyList())
                observer.onReset(items)
            },
            jsCallback1 { itemValue: JsAny? ->
                val item = runCatching {
                    wasmJson.decodeFromJsonElement<RoomListEntry>(itemValue.toJsonElement())
                }.getOrNull()
                if (item != null) {
                    observer.onUpdate(item)
                }
            }
        )
        return token.toULong()
    }

    override fun unobserveRoomList(token: ULong) {
        requireClient().unobserveRoomList(token.toDouble())
    }

    override suspend fun fetchNotification(roomId: String, eventId: String): RenderedNotification? =
        decodeValueOrNull(
            requireClient().fetchNotification(roomId, eventId).await(),
            "fetchNotification"
        )

    override suspend fun fetchNotificationsSince(
        sinceMs: Long,
        maxRooms: Int,
        maxEvents: Int
    ): List<RenderedNotification> =
        decodeValueOrNull(
            requireClient().fetchNotificationsSince(sinceMs.toDouble(), maxRooms.toDouble(), maxEvents.toDouble()).await(),
            "fetchNotificationsSince"
        ) ?: emptyList()

    override fun roomListSetUnreadOnly(token: ULong, unreadOnly: Boolean): Boolean =
        requireClient().roomListSetUnreadOnly(token.toDouble(), unreadOnly)

    override suspend fun loginSsoLoopback(openUrl: (String) -> Boolean, deviceName: String?): Boolean {
        return false
    }

    override suspend fun loginOauthLoopback(
        openUrl: (String) -> Boolean,
        deviceName: String?
    ): Boolean {
        return when (loginOauth(openUrl, deviceName)) {
            MatrixPort.OauthLoginResult.Completed,
            MatrixPort.OauthLoginResult.RedirectStarted -> true

            is MatrixPort.OauthLoginResult.Failed -> false
        }
    }

    override suspend fun loginOauth(
        openUrl: (String) -> Boolean,
        deviceName: String?
    ): MatrixPort.OauthLoginResult {
        val hs = currentHs ?: return MatrixPort.OauthLoginResult.Failed("Matrix client not initialized")
        val redirectUri = URL(".", document.baseURI).href

        return try {
            val result = requireClient()
                .loginOauthBrowser(redirectUri, deviceName)
                .await<JsAny?>()

            val obj = result?.toJsonObject()
                ?: return MatrixPort.OauthLoginResult.Failed("OAuth start failed")

            val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull == true
            val url = (obj["url"] as? JsonPrimitive)?.contentOrNull
            val error = (obj["error"] as? JsonPrimitive)?.contentOrNull

            if (ok && !url.isNullOrBlank()) {
                savePendingOauth(hs, currentAccountId)
                window.location.href = url
                MatrixPort.OauthLoginResult.RedirectStarted
            } else {
                MatrixPort.OauthLoginResult.Failed(error ?: "OAuth start failed")
            }
        } catch (e: Exception) {
            MatrixPort.OauthLoginResult.Failed(e.message)
        }
    }

    override suspend fun maybeFinishOauthRedirect(): Boolean {
        val href = window.location.href
        if (!href.contains("code=") && !href.contains("error=")) return false

        if (client == null) {
            val pending = loadPendingOauth() ?: return false
            init(pending.first, pending.second)
        }

        val ok = requireClient()
            .finishLoginFromRedirect(href, "", null)
            .await<Boolean>()

        if (ok) {
            clearPendingOauth()
            window.history.replaceState(null, "", URL(".", document.baseURI).href)
        } else if (href.contains("error=")) {
            clearPendingOauth()
        }

        return ok
    }

    override suspend fun homeserverLoginDetails(): HomeserverLoginDetails =
        wasmJson.decodeFromJsonElement(
            requireClient()
                .homeserverLoginDetails()
                .await<JsAny?>()
                .toJsonElement()
        )
    override suspend fun searchUsers(term: String, limit: Int): List<DirectoryUser> =
        decodeValueOrNull(requireClient().searchUsers(term, limit.toDouble()), "searchUsers") ?: emptyList()

    override suspend fun getUserProfile(userId: String): DirectoryUser? =
        decodeValueOrNull(requireClient().getUserProfile(userId), "getUserProfile")

    override suspend fun publicRooms(
        server: String?,
        search: String?,
        limit: Int,
        since: String?
    ): PublicRoomsPage =
        wasmJson.decodeFromJsonElement(
            requireClient().publicRooms(server, search, limit.toDouble(), since).toJsonElement()
        )

    override suspend fun joinByIdOrAlias(idOrAlias: String): Result<Unit> {
        return try {
            requireClient().joinByIdOrAlias(idOrAlias).await<JsAny?>()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Failed to join room $idOrAlias", e))
        }
    }

    override suspend fun ensureDm(userId: String): String? = requireClient().ensureDm(userId).await()

    override suspend fun resolveRoomId(idOrAlias: String): String? =
        requireClient().resolveRoomId(idOrAlias).await()

    override suspend fun listInvited(): List<RoomProfile> =
        decodeValueOrNull<List<RoomProfile>>(
            requireClient().listInvited().await(),
            "listInvited"
        ) ?: emptyList()

    override suspend fun acceptInvite(roomId: String): Boolean = requireClient().acceptInvite(roomId).await()

    override suspend fun leaveRoom(roomId: String): Result<Unit> =
        unitResult(requireClient().leaveRoom(roomId).await(), "leave room")

    override suspend fun createRoom(
        name: String?,
        topic: String?,
        invitees: List<String>,
        isPublic: Boolean,
        roomAlias: String?
    ): String? = requireClient().createRoom(
        name,
        topic,
        invitees.toJsArray(),
        isPublic,
        roomAlias
    )

    override suspend fun setRoomName(roomId: String, name: String): Result<Unit> =
        unitResult(
            requireClient().setRoomName(roomId, name).await(),
            "set room name"
        )

    override suspend fun setRoomTopic(roomId: String, topic: String): Result<Unit> =
        unitResult(
            requireClient().setRoomTopic(roomId, topic).await(),
            "set room topic"
        )

    override suspend fun roomProfile(roomId: String): RoomProfile? =
        decodeValueOrNull(
            requireClient().roomProfile(roomId).await(),
            "roomProfile"
        )

    override suspend fun roomNotificationMode(roomId: String): RoomNotificationMode? =
        decodeEnum<RoomNotificationMode>(
            requireClient().roomNotificationMode(roomId).await()
        )

    override suspend fun setRoomNotificationMode(
        roomId: String,
        mode: RoomNotificationMode
    ): Result<Unit> = unitResult(
        requireClient()
            .setRoomNotificationMode(roomId, mode.name)
            .await(),
        "set room notification mode"
    )

    override suspend fun listMembers(roomId: String): List<MemberSummary> =
        decodeValueOrNull<List<MemberSummary>>(
            requireClient().listMembers(roomId).await(),
            "listMembers"
        ) ?: emptyList()

    override suspend fun reactions(roomId: String, eventId: String): List<ReactionSummary> =
        wasmJson.decodeFromJsonElement(
            requireClient().reactionsForEvent(roomId, eventId).await<WebReactionsValue?>().toJsonElement()
        )

    override suspend fun reactionsBatch(
        roomId: String,
        eventIds: List<String>
    ): Map<String, List<ReactionSummary>> =
        wasmJson.decodeFromJsonElement(
            requireClient()
                .reactionsBatch(roomId, wasmJson.encodeToString(eventIds))
                .await<WebReactionsValue?>()
                .toJsonElement()
        )

    override suspend fun sendThreadText(
        roomId: String,
        rootEventId: String,
        body: String,
        replyToEventId: String?,
        latestEventId: String?,
        formattedBody: String?
    ): Boolean =
        requireClient().sendThreadText(
            roomId,
            rootEventId,
            body,
            replyToEventId,
            latestEventId,
            formattedBody
        ).await()

    override suspend fun threadSummary(
        roomId: String,
        rootEventId: String,
        perPage: Int,
        maxPages: Int
    ): ThreadSummary =
        decodeValueOrNull(
            requireClient().threadSummary(roomId, rootEventId, perPage.toDouble(), maxPages.toDouble()).await(),
            "threadSummary"
        ) ?: ThreadSummary(rootEventId, roomId, 0, null)

    override suspend fun threadReplies(
        roomId: String,
        rootEventId: String,
        from: String?,
        limit: Int,
        forward: Boolean
    ): ThreadPage =
        decodeValueOrNull(
            requireClient().threadReplies(roomId, rootEventId, from, limit.toDouble(), forward).await(),
            "threadReplies"
        ) ?: ThreadPage(rootEventId, roomId, emptyList(), null, null)

    override suspend fun isSpace(roomId: String): Boolean =
        mySpaces().any { it.roomId == roomId }

    override suspend fun mySpaces(): List<SpaceInfo> =
        wasmJson.decodeFromJsonElement(requireClient().mySpaces().await<JsAny?>().toJsonElement())

    override suspend fun createSpace(
        name: String,
        topic: String?,
        isPublic: Boolean,
        invitees: List<String>
    ): String? =
        requireClient().createSpace(name, topic, isPublic, invitees.toJsArray()).await()

    override suspend fun spaceAddChild(
        spaceId: String,
        childRoomId: String,
        order: String?,
        suggested: Boolean?
    ): Boolean = requireClient().spaceAddChild(spaceId, childRoomId, order, suggested).await()

    override suspend fun spaceRemoveChild(spaceId: String, childRoomId: String): Boolean =
        requireClient().spaceRemoveChild(spaceId, childRoomId).await()

    override suspend fun spaceHierarchy(
        spaceId: String,
        from: String?,
        limit: Int,
        maxDepth: Int?,
        suggestedOnly: Boolean
    ): SpaceHierarchyPage? = runCatching {
        wasmJson.decodeFromJsonElement<SpaceHierarchyPage>(
            requireClient().spaceHierarchy(spaceId, from, limit.toDouble(), maxDepth?.toDouble(), suggestedOnly).await<JsAny?>().toJsonElement()
        )
    }.getOrNull()

    override suspend fun spaceInviteUser(spaceId: String, userId: String): Boolean =
        requireClient().spaceInviteUser(spaceId, userId).await()

    override suspend fun setPresence(presence: Presence, status: String?): Result<Unit> =
        unitResult(
            requireClient().setPresence(presence.name, status).await(),
            "set presence"
        )

    override suspend fun getPresence(userId: String): Pair<Presence, String?>? =
        decodeValueOrNull<PresenceInfo>(
            requireClient().getPresence(userId).await(),
            "getPresence"
        )?.let { it.presence to it.statusMsg }

    override suspend fun ignoreUser(userId: String): Result<Unit> =
        unitResult(requireClient().ignoreUser(userId), "ignore user")

    override suspend fun unignoreUser(userId: String): Result<Unit> =
        unitResult(requireClient().unignoreUser(userId), "unignore user")

    override suspend fun ignoredUsers(): List<String> =
        decodeStringList(requireClient().ignoredUsers())

    override suspend fun roomDirectoryVisibility(roomId: String): RoomDirectoryVisibility? =
        decodeEnum(requireClient().roomDirectoryVisibility(roomId))

    override suspend fun setRoomDirectoryVisibility(
        roomId: String,
        visibility: RoomDirectoryVisibility
    ): Result<Unit> = unitResult(
        requireClient().setRoomDirectoryVisibility(roomId, visibility.name),
        "set room directory visibility"
    )

    override suspend fun publishRoomAlias(roomId: String, alias: String): Boolean =
        requireClient().publishRoomAlias(roomId, alias)

    override suspend fun unpublishRoomAlias(roomId: String, alias: String): Boolean =
        requireClient().unpublishRoomAlias(roomId, alias)

    override suspend fun setRoomCanonicalAlias(
        roomId: String,
        alias: String?,
        altAliases: List<String>
    ): Result<Unit> = unitResult(
        requireClient().setRoomCanonicalAlias(
            roomId,
            alias,
            wasmJson.encodeToString(altAliases),
        ),
        "set room canonical alias"
    )

    override suspend fun roomAliases(roomId: String): List<String> =
        decodeStringList(requireClient().roomAliases(roomId))

    override suspend fun roomJoinRule(roomId: String): RoomJoinRule? =
        decodeEnum(requireClient().roomJoinRule(roomId))

    override suspend fun setRoomJoinRule(roomId: String, rule: RoomJoinRule): Result<Unit> =
        unitResult(requireClient().setRoomJoinRule(roomId, rule.name), "set room join rule")

    override suspend fun roomHistoryVisibility(roomId: String): RoomHistoryVisibility? =
        decodeEnum(requireClient().roomHistoryVisibility(roomId))

    override suspend fun setRoomHistoryVisibility(
        roomId: String,
        visibility: RoomHistoryVisibility
    ): Result<Unit> = unitResult(
        requireClient().setRoomHistoryVisibility(roomId, visibility.name),
        "set room history visibility"
    )

    override suspend fun roomPowerLevels(roomId: String): RoomPowerLevels? =
        decodeValueOrNull(requireClient().roomPowerLevels(roomId).await(), "roomPowerLevels")

    override suspend fun canUserBan(roomId: String, userId: String): Boolean = false

    override suspend fun canUserInvite(roomId: String, userId: String): Boolean = false

    override suspend fun canUserRedactOther(roomId: String, userId: String): Boolean = false

    override suspend fun updatePowerLevelForUser(
        roomId: String,
        userId: String,
        powerLevel: Long
    ): Result<Unit> = unitResult(
        requireClient().updatePowerLevelForUser(roomId, userId, powerLevel.toDouble()),
        "update power level"
    )

    override suspend fun applyPowerLevelChanges(
        roomId: String,
        changes: RoomPowerLevelChanges
    ): Result<Unit> = unitResult(
        requireClient().applyPowerLevelChanges(roomId, wasmJson.encodeToString(changes)),
        "apply power level changes"
    )

    override suspend fun reportContent(
        roomId: String,
        eventId: String,
        score: Int?,
        reason: String?
    ): Result<Unit> = unitResult(
        requireClient().reportContent(roomId, eventId, score?.toDouble(), reason),
        "report content"
    )

    override suspend fun reportRoom(roomId: String, reason: String?): Result<Unit> =
        unitResult(requireClient().reportRoom(roomId, reason), "report room")

    override suspend fun banUser(roomId: String, userId: String, reason: String?): Result<Unit> =
        unitResult(requireClient().banUser(roomId, userId, reason), "ban user")

    override suspend fun unbanUser(roomId: String, userId: String, reason: String?): Result<Unit> =
        unitResult(requireClient().unbanUser(roomId, userId, reason), "unban user")

    override suspend fun kickUser(roomId: String, userId: String, reason: String?): Result<Unit> =
        unitResult(requireClient().kickUser(roomId, userId, reason), "kick user")

    override suspend fun inviteUser(roomId: String, userId: String): Result<Unit> =
        unitResult(requireClient().inviteUser(roomId, userId), "invite user")

    override suspend fun enableRoomEncryption(roomId: String): Result<Unit> =
        unitResult(requireClient().enableRoomEncryption(roomId), "enable room encryption")

    override suspend fun roomSuccessor(roomId: String): RoomUpgradeInfo? =
        decodeValueOrNull(requireClient().roomSuccessor(roomId).await(), "roomSuccessor")

    override suspend fun roomPredecessor(roomId: String): RoomPredecessorInfo? =
        decodeValueOrNull(requireClient().roomPredecessor(roomId).await(), "roomPredecessor")

    override suspend fun startLiveLocationShare(roomId: String, durationMs: Long): Result<Unit> =
        unitResult(
            requireClient().startLiveLocation(roomId, durationMs.toDouble()).await(),
            "start live location"
        )

    override suspend fun stopLiveLocationShare(roomId: String): Result<Unit> =
        unitResult(
            requireClient().stopLiveLocation(roomId).await(),
            "stop live location"
        )

    override suspend fun sendLiveLocation(roomId: String, geoUri: String): Result<Unit> =
        unitResult(
            requireClient().sendLiveLocation(roomId, geoUri).await(),
            "send live location"
        )

    override fun observeLiveLocation(roomId: String, onShares: (List<LiveLocationShare>) -> Unit): ULong =
        requireClient().observeLiveLocation(
            roomId,
            jsCallback1 { payload: JsAny? ->
                val shares = decodeValueOrNull<List<LiveLocationShare>>(payload, "observeLiveLocation")
                    ?: emptyList()
                onShares(shares)
            }
        ).toULong()

    override fun stopObserveLiveLocation(token: ULong) {
        requireClient().unobserveLiveLocation(token.toDouble())
    }

    override suspend fun sendPoll(roomId: String, question: String, answers: List<String>): Boolean =
        requireClient().sendPollStart(roomId, question, wasmJson.encodeToString(answers), "disclosed", 1.0)

    override fun seenByForEvent(roomId: String, eventId: String, limit: Int): List<SeenByEntry> =
        decodeValueOrNull(requireClient().seenByForEvent(roomId, eventId, limit.toDouble()), "seenByForEvent") ?: emptyList()

    override suspend fun mxcThumbnailToCache(mxcUri: String, width: Int, height: Int, crop: Boolean): String =
        requireClient().mxcThumbnailToCache(mxcUri, width.toDouble(), height.toDouble(), crop).await<String?>()
            .orEmpty()

    override suspend fun loadRoomListCache(): List<RoomListEntry> =
        wasmJson.decodeFromJsonElement(requireClient().loadRoomListCache().toJsonArray())

    override suspend fun sendPollResponse(roomId: String, pollEventId: String, answers: List<String>): Boolean =
        requireClient().sendPollResponse(roomId, pollEventId, wasmJson.encodeToString(answers))

    override suspend fun sendPollEnd(roomId: String, pollEventId: String): Boolean =
        requireClient().sendPollEnd(roomId, pollEventId)

    override suspend fun startElementCall(
        roomId: String,
        intent: CallIntent,
        elementCallUrl: String?,
        parentUrl: String?,
        languageTag: String?,
        theme: String?,
        observer: CallWidgetObserver
    ): CallSession? =
        decodeValueOrNull(
            requireClient().startElementCall(
                roomId,
                intent.name,
                elementCallUrl,
                parentUrl,
                languageTag,
                theme,
                jsCallback1 { message: JsAny? ->
                    observer.onToWidget(message?.toString() ?: "")
                }
            ).await(),
            "startElementCall"
        )

    override fun callWidgetFromWebview(sessionId: ULong, message: String): Boolean =
        requireClient().callWidgetFromWebview(sessionId.toDouble(), message)

    override fun stopElementCall(sessionId: ULong): Boolean =
        requireClient().stopElementCall(sessionId.toDouble())

    override suspend fun roomPreview(idOrAlias: String): Result<RoomPreview> =
        runCatching {
            decodeValue<RoomPreview>(
                requireClient().roomPreview(idOrAlias).await()
            )
        }

    override suspend fun knock(idOrAlias: String): Boolean =
        requireClient().knock(idOrAlias).await()

    override suspend fun listKnockRequests(roomId: String): List<KnockRequestSummary> {
        return emptyList()
    }

    override suspend fun acceptKnockRequest(roomId: String, userId: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Not implemented on web"))
    }

    override suspend fun declineKnockRequest(roomId: String, userId: String, reason: String?): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Not implemented on web"))
    }
}

actual fun createMatrixPort(): MatrixPort = WebStubMatrixPort()
