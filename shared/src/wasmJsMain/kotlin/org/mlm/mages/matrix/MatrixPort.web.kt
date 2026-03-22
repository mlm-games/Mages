package org.mlm.mages.matrix

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.JsAny
import kotlin.js.Promise

// -- Callback fns since Kotlin/Wasm cannot pass lambdas as JsAny? --

@JsFun("(fn) => function() { fn(); }")
private external fun jsCallback0(fn: () -> Unit): JsAny

@JsFun("(fn) => function(a) { return fn(a); }")
private external fun jsCallback1(fn: (JsAny?) -> Unit): JsAny

@JsFun("(fn) => function(msg) { fn(msg); }")
private external fun jsWidgetObserver(fn: (String) -> Unit): JsAny

@JsFun("(arr) => arr")
private external fun jsArrayPassthrough(arr: JsAny): JsAny

@JsFun("() => []")
private external fun createEmptyJsArray(): JsAny

@JsFun("(arr, item) => { arr.push(item); }")
private external fun pushToJsArray(arr: JsAny, item: String)

fun List<String>.toJsArray(): JsAny {
    val jsArr = createEmptyJsArray()
    for (item in this) {
        pushToJsArray(jsArr, item)
    }
    return jsArr
}

data class ResultWithError(val ok: Boolean, val error: String?) {
    companion object {
        fun success() = ResultWithError(ok = true, error = null)
    }
}


private suspend fun Promise<JsAny?>.awaitResult(): ResultWithError {
    val obj = await<JsAny?>()?.toJsonObject()
        ?: return ResultWithError(ok = false, error = "null response")
    val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull == true
    val error = (obj["error"] as? JsonPrimitive)?.contentOrNull
    return ResultWithError(ok, error)
}

/** Envelope → Boolean (ok field). Use for envelope-returning methods only. */
private suspend fun Promise<JsAny?>.awaitBool(): Boolean =
    awaitResult().ok

/** Envelope → Result<Unit>. */
private suspend fun Promise<JsAny?>.awaitUnitResult(): Result<Unit> {
    val result = awaitResult()
    return if (result.ok) Result.success(Unit) else Result.failure(Exception(result.error ?: "Unknown error"))
}

/** Envelope → Result<Boolean> (reads inner "value" bool). */
private suspend fun Promise<JsAny?>.awaitBoolResult(): Result<Boolean> {
    val obj = await<JsAny?>()?.toJsonObject()
        ?: return Result.failure(Exception("null response"))

    val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull == true
    if (!ok) {
        val error = (obj["error"] as? JsonPrimitive)?.contentOrNull
        return Result.failure(Exception(error ?: "Unknown error"))
    }

    val value = (obj["value"] as? JsonPrimitive)?.booleanOrNull
        ?: return Result.failure(Exception("Missing inner value"))

    return Result.success(value)
}

/** Envelope → typed value extracted from "value" key, or null on failure. */
private suspend inline fun <reified T> Promise<JsAny?>.awaitValue(): T? {
    val obj = await<JsAny?>()?.toJsonObject() ?: return null
    val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull == true
    if (!ok) {
        val error = (obj["error"] as? JsonPrimitive)?.contentOrNull
        println("awaitValue failed: $error")
        return null
    }
    val value = obj["value"] ?: return null
    return wasmJson.decodeFromJsonElement(value)
}

/** Envelope → String extracted from "value" key, or null. */
private suspend fun Promise<JsAny?>.awaitStringValue(): String? {
    val obj = await<JsAny?>()?.toJsonObject() ?: return null
    val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull == true
    if (!ok) return null
    return (obj["value"] as? JsonPrimitive)?.contentOrNull
}


/** Plain bool (no envelope). For Rust methods that still return bare true/false. */
private suspend fun Promise<JsAny?>.awaitPlainBool(): Boolean =
    await<JsAny?>()?.toString() == "true"

private suspend fun Promise<JsAny?>.awaitString(): String? =
    await<JsAny?>()?.toString()?.takeIf { it != "null" && it != "undefined" }

private suspend fun Promise<JsAny?>.awaitUnit(): Result<Unit> {
    await<JsAny?>()
    return Result.success(Unit)
}

private suspend fun Promise<JsAny?>.awaitAny(): JsAny? =
    await()

@JsFun("""(base64) => {
    const bin = atob(base64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
}""")
private external fun base64ToUint8Array(base64: String): JsAny

@JsFun("(msg) => console.log(msg)")
private external fun consoleLog(msg: String)

@OptIn(ExperimentalEncodingApi::class)
fun ByteArray.toJsUint8Array(): JsAny {
    val b64 = Base64.Default.encode(this)
    return base64ToUint8Array(b64)
}


@JsFun("""(url, redirectOrigin) => new Promise((resolve) => {
    const w = window.open(url, '_blank', 'width=600,height=700,popup=yes');
    if (!w) { resolve(null); return; }

    const timer = setInterval(() => {
        try {
            if (w.location.origin === redirectOrigin) {
                const href = w.location.href;
                clearInterval(timer);
                w.close();
                resolve(href);
            }
        } catch (e) {
           
        }
    }, 200);

    const closeTimer = setInterval(() => {
        if (w.closed) {
            clearInterval(timer);
            clearInterval(closeTimer);
            resolve(null);
        }
    }, 500);
})""")
private external fun openOAuthPopup(url: String, redirectOrigin: String): Promise<JsAny?>

class WebStubMatrixPort : MatrixPort, VerificationService {
    private var client: WasmClient? = null
    private var currentHs: String? = null
    private var currentAccountId: String? = null
    private var isInForeground: Boolean = true

    private var nextConnectionObserverToken: ULong = 1uL
    private val connectionObserverStops = mutableMapOf<ULong, () -> Unit>()

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

    private inline fun <reified T : Enum<T>> decodeEnum(name: JsAny?): T? {
        val str = name?.toString()?.removeSurrounding("\"")?.takeIf { it != "null" && it != "undefined" } ?: return null
        return runCatching { enumValueOf<T>(str) }.getOrNull()
    }

    private fun unitResult(ok: Boolean, action: String): Result<Unit> =
        unitResult(ok, action, null)

    private fun unitResult(ok: Boolean, action: String, error: String?): Result<Unit> =
        if (ok) Result.success(Unit)
        else Result.failure(IllegalStateException(error ?: "Failed to $action"))

    private fun decodeStringList(value: JsAny?): List<String> =
        decodeValueOrNull<List<String>>(value) ?: emptyList()

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
            requireClientOrNull()?.unobserveTimeline(subscription)
        }
    }

    override suspend fun send(roomId: String, body: String, formattedBody: String?): Result<Unit> {
        return requireClient().sendMessage(roomId, body).awaitUnitResult()
    }

    override suspend fun sendQueueSetEnabled(enabled: Boolean): Result<Unit> =
        requireClient().sendQueueSetEnabled(enabled).awaitUnitResult()

    override suspend fun sendExistingAttachment(
        roomId: String,
        attachment: AttachmentInfo,
        body: String?,
        onProgress: ((Long, Long?) -> Unit)?
    ): Result<Unit> {
        val result = requireClient().sendExistingAttachment(roomId, wasmJson.encodeToString(attachment), body).awaitResult()
        return if (result.ok) Result.success(Unit) else Result.failure(Exception(result.error ?: "Send failed"))
    }

    override fun isLoggedIn(): Boolean = client?.isLoggedIn() == true

    override fun close() {
        try { client?.free() } catch (e: Exception) { e.printStackTrace() }
        client = null
        currentHs = null
        currentAccountId = null
    }

    override suspend fun setTyping(roomId: String, typing: Boolean): Result<Unit> =
        requireClient().setTyping(roomId, typing).awaitUnitResult()

    override fun whoami(): String? = client?.whoami()

    override suspend fun accountManagementUrl(): String? =
        requireClient().accountManagementUrl().await<JsAny?>()?.toString()?.takeIf { it != "null" }

    override fun setupRecovery(observer: MatrixPort.RecoveryObserver): Boolean {
        val client = requireClient()
        GlobalScope.launch {
            try {
                observer.onProgress("Starting recovery setup…")
                val result = client.setupRecovery().await<JsAny?>()
                val key = result?.toString() ?: ""
                if (key.isNotBlank()) {
                    observer.onDone(key)
                } else {
                    observer.onError("Recovery returned empty key")
                }
            } catch (e: Exception) {
                observer.onError(e.message ?: "Recovery error")
            }
        }
        return true
    }

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
        requireClient().backupExistsOnServer(fetch).awaitPlainBool()

    override suspend fun setKeyBackupEnabled(enabled: Boolean): Boolean =
        requireClient().setKeyBackupEnabled(enabled).awaitPlainBool()

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
        awaitClose { requireClientOrNull()?.unobserveSends(token) }
    }

    override suspend fun roomTags(roomId: String): Pair<Boolean, Boolean>? {
        val obj = requireClient().roomTags(roomId).await<JsAny?>()?.toJsonObject() ?: return null
        val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull == true
        if (!ok) return null
        val value = obj["value"] as? JsonObject ?: return null
        val favourite = (value["favourite"] as? JsonPrimitive)?.booleanOrNull == true
        val lowPriority = (value["low_priority"] as? JsonPrimitive)?.booleanOrNull == true
        return favourite to lowPriority
    }

    override suspend fun setRoomFavourite(roomId: String, favourite: Boolean): Result<Unit> {
        val result = requireClient().setRoomFavourite(roomId, favourite).awaitResult()
        return unitResult(result.ok, "update room favourite", result.error)
    }

    override suspend fun setRoomLowPriority(roomId: String, lowPriority: Boolean): Result<Unit> {
        val result = requireClient().setRoomLowPriority(roomId, lowPriority).awaitResult()
        return unitResult(result.ok, "update room priority", result.error)
    }

    override suspend fun thumbnailToCache(
        info: AttachmentInfo,
        width: Int,
        height: Int,
        crop: Boolean
    ): Result<String> = runCatching {
        val raw = requireClient()
            .thumbnailToCache(wasmJson.encodeToString(info), width.toDouble(), height.toDouble(), crop)
            .await<JsAny?>()
            ?.toString()
            ?.takeIf { it.startsWith("data:") }
            ?: error("Thumbnail fetch failed")
        raw
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

    override suspend fun paginateBack(roomId: String, count: Int): Result<Boolean> =
        requireClient().paginateBackwards(roomId, count.toDouble()).awaitBoolResult()

    override suspend fun paginateForward(roomId: String, count: Int): Result<Boolean> =
        requireClient().paginateForwards(roomId, count.toDouble()).awaitBoolResult()

    override suspend fun markRead(roomId: String): Result<Unit> =
        requireClient().markRead(roomId).awaitUnitResult()

    override suspend fun markReadAt(roomId: String, eventId: String): Result<Unit> =
        requireClient().markReadAt(roomId, eventId).awaitUnitResult()

    override suspend fun markFullyReadAt(roomId: String, eventId: String): Result<Unit> =
        requireClient().markFullyReadAt(roomId, eventId).awaitUnitResult()

    override suspend fun react(roomId: String, eventId: String, emoji: String): Result<Unit> =
        requireClient().react(roomId, eventId, emoji).awaitUnitResult()

    override suspend fun reply(
        roomId: String,
        inReplyToEventId: String,
        body: String,
        formattedBody: String?
    ): Result<Unit> =
        requireClient().reply(roomId, inReplyToEventId, body).awaitUnitResult()

    override suspend fun edit(
        roomId: String,
        targetEventId: String,
        newBody: String,
        formattedBody: String?
    ): Result<Unit> =
        requireClient().edit(roomId, targetEventId, newBody).awaitUnitResult()

    override suspend fun redact(roomId: String, eventId: String, reason: String?): Result<Unit> =
        requireClient().redact(roomId, eventId, reason).awaitUnitResult()

    override suspend fun getUserPowerLevel(roomId: String, userId: String): Long =
        requireClient()
            .getUserPowerLevel(roomId, userId)
            .await<JsAny?>()
            .toString()
            .toDoubleOrNull()
            ?.toLong()
            ?: 0L

    override suspend fun getPinnedEvents(roomId: String): List<String> =
        decodeStringList(requireClient().getPinnedEvents(roomId).awaitAny())

    override suspend fun setPinnedEvents(roomId: String, eventIds: List<String>): Result<Unit> =
        requireClient().setPinnedEvents(roomId, eventIds.toJsArray()).awaitUnitResult()

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
        decodeValueOrNull(requireClient().listMyDevices().awaitAny(), "listMyDevices") ?: emptyList()

    override fun enterForeground() {
        isInForeground = true
    }

    override fun enterBackground() {
        isInForeground = false
    }

    override suspend fun logout(): Boolean {
        val f = requireClientOrNull() ?: return false
        return f.logout().awaitUnitResult().isSuccess
    }

    override suspend fun sendAttachmentFromPath(
        roomId: String,
        path: String,
        mime: String,
        filename: String?,
        onProgress: ((Long, Long?) -> Unit)?
    ): Boolean {
        val bytes = retrieveWebBlob(path) ?: throw Exception("Blob not found for path: $path")
        clearWebBlob(path)
        val result = requireClient().sendAttachmentBytes(roomId, filename ?: path, mime, bytes.toJsUint8Array()).awaitUnitResult()
        return result.isSuccess
    }

    override suspend fun downloadAttachmentToCache(
        info: AttachmentInfo,
        filenameHint: String?
    ): Result<String> = runCatching {
        requireClient()
            .downloadAttachmentToCacheFile(wasmJson.encodeToString(info), filenameHint)
            .awaitStringValue()
            ?: error("Attachment download failed")
    }

    override suspend fun searchRoom(
        roomId: String,
        query: String,
        limit: Int,
        offset: Int?
    ): SearchPage = decodeValueOrNull(
        requireClient().searchRoom(roomId, query, limit.toDouble(), offset?.toDouble()).awaitAny(),
        "searchRoom"
    ) ?: SearchPage(emptyList(), null)

    override suspend fun recoverWithKey(recoveryKey: String): Result<Unit> = runCatching {
        val ok = requireClient().recoverWithKey(recoveryKey).awaitPlainBool()
        if (!ok) error("Recovery failed")
    }

    override fun observeReceipts(roomId: String, observer: ReceiptsObserver): ULong =
        requireClient().observeReceipts(roomId, jsCallback0 { observer.onChanged() }).toULong()

    override fun stopReceiptsObserver(token: ULong) {
        requireClient().unobserveReceipts(token.toDouble())
    }

    override suspend fun dmPeerUserId(roomId: String): String? =
        requireClient().dmPeerUserId(roomId).awaitStringValue()

    override suspend fun isEventReadBy(roomId: String, eventId: String, userId: String): Boolean =
        requireClient().isEventReadBy(roomId, eventId, userId).awaitBool()

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
        requireClient().roomUnreadStats(roomId).awaitValue()

    override suspend fun ownLastRead(roomId: String): Pair<String?, Long?> =
        decodeOwnLastRead(requireClient().ownLastRead(roomId).awaitAny())

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
            requireClient().fetchNotification(roomId, eventId).awaitAny(),
            "fetchNotification"
        )

    override suspend fun fetchNotificationsSince(
        sinceMs: Long,
        maxRooms: Int,
        maxEvents: Int
    ): List<RenderedNotification> =
        decodeValueOrNull(
            requireClient().fetchNotificationsSince(sinceMs.toDouble(), maxRooms.toDouble(), maxEvents.toDouble()).awaitAny(),
            "fetchNotificationsSince"
        ) ?: emptyList()

    override fun roomListSetUnreadOnly(token: ULong, unreadOnly: Boolean): Boolean =
        requireClient().roomListSetUnreadOnly(token.toDouble(), unreadOnly)

    override suspend fun loginSsoLoopback(openUrl: (String) -> Boolean, deviceName: String?): Result<Unit> {
        return Result.failure(UnsupportedOperationException("SSO not supported on web"))
    }

    override suspend fun loginOauthLoopback(
        openUrl: (String) -> Boolean,
        deviceName: String?
    ): Result<Unit> {
        return when (loginOauth(openUrl, deviceName)) {
            MatrixPort.OauthLoginResult.Completed -> Result.success(Unit)
            MatrixPort.OauthLoginResult.RedirectStarted -> Result.success(Unit)
            is MatrixPort.OauthLoginResult.Failed -> Result.failure(Exception("OAuth failed"))
        }
    }

    override suspend fun loginOauth(
        openUrl: (String) -> Boolean,
        deviceName: String?
    ): MatrixPort.OauthLoginResult {
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

            if (!ok || url.isNullOrBlank()) {
                return MatrixPort.OauthLoginResult.Failed(error ?: "OAuth start failed")
            }

            val redirectOrigin = URL(".", document.baseURI).origin
            val callbackHref = openOAuthPopup(url, redirectOrigin)
                .await<JsAny?>()
                ?.toString()

            if (callbackHref.isNullOrBlank()) {
                return MatrixPort.OauthLoginResult.Failed("OAuth popup closed or blocked")
            }

            val finishResult = requireClient()
                .finishLoginFromRedirect(callbackHref, "", null)
                .await<JsAny?>()
                ?.toJsonObject()

            val finishOk = (finishResult?.get("ok") as? JsonPrimitive)?.booleanOrNull == true
            val finishError = (finishResult?.get("error") as? JsonPrimitive)?.contentOrNull

            if (finishOk) {
                MatrixPort.OauthLoginResult.Completed
            } else {
                MatrixPort.OauthLoginResult.Failed(finishError ?: "OAuth finish failed")
            }
        } catch (e: Exception) {
            MatrixPort.OauthLoginResult.Failed(e.message)
        }
    }

    override suspend fun maybeFinishOauthRedirect(): Boolean = false

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

    override suspend fun ensureDm(userId: String): String? =
        requireClient().ensureDm(userId).awaitStringValue()

    override suspend fun resolveRoomId(idOrAlias: String): String? =
        requireClient().resolveRoomId(idOrAlias).awaitStringValue()

    override suspend fun listInvited(): List<RoomProfile> =
        requireClient().listInvited().awaitValue<List<RoomProfile>>() ?: emptyList()

    override suspend fun acceptInvite(roomId: String): Result<Unit> =
        requireClient().acceptInvite(roomId).awaitUnitResult()

    override suspend fun leaveRoom(roomId: String): Result<Unit> {
        val result = requireClient().leaveRoom(roomId).awaitResult()
        return unitResult(result.ok, "leave room", result.error)
    }

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
    ).awaitString()

    override suspend fun setRoomName(roomId: String, name: String): Result<Unit> {
        val result = requireClient().setRoomName(roomId, name).awaitResult()
        return unitResult(result.ok, "set room name", result.error)
    }

    override suspend fun setRoomTopic(roomId: String, topic: String): Result<Unit> {
        val result = requireClient().setRoomTopic(roomId, topic).awaitResult()
        return unitResult(result.ok, "set room topic", result.error)
    }

    override suspend fun roomProfile(roomId: String): RoomProfile? =
        decodeValueOrNull(
            requireClient().roomProfile(roomId).awaitAny(),
            "roomProfile"
        )

    override suspend fun roomNotificationMode(roomId: String): RoomNotificationMode? {
        val name = requireClient().roomNotificationMode(roomId).awaitStringValue() ?: return null
        return runCatching { enumValueOf<RoomNotificationMode>(name) }.getOrNull()
    }

    override suspend fun setRoomNotificationMode(
        roomId: String,
        mode: RoomNotificationMode
    ): Result<Unit> = unitResult(
        requireClient()
            .setRoomNotificationMode(roomId, mode.name)
            .awaitPlainBool(),
        "set room notification mode"
    )

    override suspend fun listMembers(roomId: String): List<MemberSummary> =
        requireClient().listMembers(roomId).awaitValue<List<MemberSummary>>() ?: emptyList()

    override suspend fun reactions(roomId: String, eventId: String): List<ReactionSummary> =
        decodeValueOrNull<List<ReactionSummary>>(
            requireClient().reactionsForEvent(roomId, eventId).awaitAny(), "reactions"
        ) ?: emptyList()

    override suspend fun reactionsBatch(
        roomId: String,
        eventIds: List<String>
    ): Map<String, List<ReactionSummary>> =
        decodeValueOrNull<Map<String, List<ReactionSummary>>>(
            requireClient().reactionsBatch(roomId, eventIds.toJsArray()).awaitAny(), "reactionsBatch"
        ) ?: emptyMap()

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
        ).awaitBool()

    override suspend fun threadSummary(
        roomId: String,
        rootEventId: String,
        perPage: Int,
        maxPages: Int
    ): ThreadSummary =
        requireClient().threadSummary(roomId, rootEventId, perPage.toDouble(), maxPages.toDouble())
            .awaitValue<ThreadSummary>()
            ?: ThreadSummary(rootEventId, roomId, 0, null)

    override suspend fun threadReplies(
        roomId: String,
        rootEventId: String,
        from: String?,
        limit: Int,
        forward: Boolean
    ): ThreadPage =
        requireClient().threadReplies(roomId, rootEventId, from, limit.toDouble(), forward)
            .awaitValue<ThreadPage>()
            ?: ThreadPage(rootEventId, roomId, emptyList(), null, null)

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
        requireClient().createSpace(name, topic, isPublic, invitees.toJsArray()).awaitString()

    override suspend fun spaceAddChild(
        spaceId: String,
        childRoomId: String,
        order: String?,
        suggested: Boolean?
    ): Result<Unit> = requireClient().spaceAddChild(spaceId, childRoomId, order, suggested).awaitUnitResult()

    override suspend fun spaceRemoveChild(spaceId: String, childRoomId: String): Result<Unit> =
        requireClient().spaceRemoveChild(spaceId, childRoomId).awaitUnitResult()

    override suspend fun spaceHierarchy(
        spaceId: String,
        from: String?,
        limit: Int,
        maxDepth: Int?,
        suggestedOnly: Boolean
    ): SpaceHierarchyPage? =
        requireClient().spaceHierarchy(spaceId, from, limit.toDouble(), maxDepth?.toDouble(), suggestedOnly)
            .awaitValue<SpaceHierarchyPage>()

    override suspend fun spaceInviteUser(spaceId: String, userId: String): Result<Unit> =
        requireClient().spaceInviteUser(spaceId, userId).awaitUnitResult()

    override suspend fun setPresence(presence: Presence, status: String?): Result<Unit> {
        val result = requireClient().setPresence(presence.name, status).awaitResult()
        return unitResult(result.ok, "set presence", result.error)
    }

    override suspend fun getPresence(userId: String): Pair<Presence, String?>? =
        requireClient().getPresence(userId).awaitValue<PresenceInfo>()
            ?.let { it.presence to it.statusMsg }

    override suspend fun ignoreUser(userId: String): Result<Unit> {
        val result = requireClient().ignoreUser(userId).awaitResult()
        return unitResult(result.ok, "ignore user", result.error)
    }

    override suspend fun unignoreUser(userId: String): Result<Unit> {
        val result = requireClient().unignoreUser(userId).awaitResult()
        return unitResult(result.ok, "unignore user", result.error)
    }

    override suspend fun ignoredUsers(): List<String> =
        requireClient().ignoredUsers().awaitValue<List<String>>() ?: emptyList()

    override suspend fun roomDirectoryVisibility(roomId: String): RoomDirectoryVisibility? =
        decodeEnum(requireClient().roomDirectoryVisibility(roomId).awaitAny())

    override suspend fun setRoomDirectoryVisibility(
        roomId: String,
        visibility: RoomDirectoryVisibility
    ): Result<Unit> = unitResult(
        requireClient().setRoomDirectoryVisibility(roomId, visibility.name).awaitPlainBool(),
        "set room directory visibility"
    )

    override suspend fun publishRoomAlias(roomId: String, alias: String): Result<Unit> =
        unitResult(requireClient().publishRoomAlias(roomId, alias).awaitPlainBool(), "publish room alias")

    override suspend fun unpublishRoomAlias(roomId: String, alias: String): Result<Unit> =
        unitResult(requireClient().unpublishRoomAlias(roomId, alias).awaitPlainBool(), "unpublish room alias")

    override suspend fun setRoomCanonicalAlias(
        roomId: String,
        alias: String?,
        altAliases: List<String>
    ): Result<Unit> = unitResult(
        requireClient().setRoomCanonicalAlias(
            roomId,
            alias,
            altAliases.toJsArray(),
        ).awaitPlainBool(),
        "set room canonical alias"
    )

    override suspend fun roomAliases(roomId: String): List<String> =
        decodeStringList(requireClient().roomAliases(roomId).awaitAny())

    override suspend fun roomJoinRule(roomId: String): RoomJoinRule? =
        decodeEnum(requireClient().roomJoinRule(roomId).awaitAny())

    override suspend fun setRoomJoinRule(roomId: String, rule: RoomJoinRule): Result<Unit> =
        unitResult(requireClient().setRoomJoinRule(roomId, rule.name).awaitPlainBool(), "set room join rule")

    override suspend fun roomHistoryVisibility(roomId: String): RoomHistoryVisibility? =
        decodeEnum(requireClient().roomHistoryVisibility(roomId).awaitAny())

    override suspend fun setRoomHistoryVisibility(
        roomId: String,
        visibility: RoomHistoryVisibility
    ): Result<Unit> =
        unitResult(requireClient().setRoomHistoryVisibility(roomId, visibility.name).awaitPlainBool(), "set room history visibility")

    override suspend fun roomPowerLevels(roomId: String): RoomPowerLevels? =
        requireClient().roomPowerLevels(roomId).awaitValue()

    override suspend fun canUserBan(roomId: String, userId: String): Boolean =
        requireClient().canUserBan(roomId, userId).awaitPlainBool()

    override suspend fun canUserInvite(roomId: String, userId: String): Boolean =
        requireClient().canUserInvite(roomId, userId).awaitPlainBool()

    override suspend fun canUserRedactOther(roomId: String, userId: String): Boolean =
        requireClient().canUserRedactOther(roomId, userId).awaitPlainBool()

    override suspend fun updatePowerLevelForUser(
        roomId: String,
        userId: String,
        powerLevel: Long
    ): Result<Unit> = unitResult(
        requireClient().updatePowerLevelForUser(roomId, userId, powerLevel.toDouble()).awaitPlainBool(),
        "update power level"
    )

    override suspend fun applyPowerLevelChanges(
        roomId: String,
        changes: RoomPowerLevelChanges
    ): Result<Unit> = unitResult(
        requireClient().applyPowerLevelChanges(roomId, wasmJson.encodeToString(changes)).awaitPlainBool(),
        "apply power level changes"
    )

    override suspend fun reportContent(
        roomId: String,
        eventId: String,
        score: Int?,
        reason: String?
    ): Result<Unit> = unitResult(
        requireClient().reportContent(roomId, eventId, score?.toDouble(), reason).awaitPlainBool(),
        "report content"
    )

    override suspend fun reportRoom(roomId: String, reason: String?): Result<Unit> {
        val result = requireClient().reportRoom(roomId, reason).awaitResult()
        return unitResult(result.ok, "report room", result.error)
    }

    override suspend fun banUser(roomId: String, userId: String, reason: String?): Result<Unit> {
        val result = requireClient().banUser(roomId, userId, reason).awaitResult()
        return unitResult(result.ok, "ban user", result.error)
    }

    override suspend fun unbanUser(roomId: String, userId: String, reason: String?): Result<Unit> {
        val result = requireClient().unbanUser(roomId, userId, reason).awaitResult()
        return unitResult(result.ok, "unban user", result.error)
    }

    override suspend fun kickUser(roomId: String, userId: String, reason: String?): Result<Unit> {
        val result = requireClient().kickUser(roomId, userId, reason).awaitResult()
        return unitResult(result.ok, "kick user", result.error)
    }

    override suspend fun inviteUser(roomId: String, userId: String): Result<Unit> {
        val result = requireClient().inviteUser(roomId, userId).awaitResult()
        return unitResult(result.ok, "invite user", result.error)
    }

    override suspend fun enableRoomEncryption(roomId: String): Result<Unit> {
        val result = requireClient().enableRoomEncryption(roomId).awaitResult()
        return unitResult(result.ok, "enable room encryption", result.error)
    }

    override suspend fun roomSuccessor(roomId: String): RoomUpgradeInfo? =
        requireClient().roomSuccessor(roomId).awaitValue()

    override suspend fun roomPredecessor(roomId: String): RoomPredecessorInfo? =
        requireClient().roomPredecessor(roomId).awaitValue()

    override suspend fun startLiveLocationShare(roomId: String, durationMs: Long): Result<Unit> =
        requireClient().startLiveLocation(roomId, durationMs.toDouble()).awaitUnitResult()

    override suspend fun stopLiveLocationShare(roomId: String): Result<Unit> {
        val result = requireClient().stopLiveLocation(roomId).awaitResult()
        return unitResult(result.ok, "stop live location", result.error)
    }

    override suspend fun sendLiveLocation(roomId: String, geoUri: String): Result<Unit> {
        val result = requireClient().sendLiveLocation(roomId, geoUri).awaitResult()
        return unitResult(result.ok, "send live location", result.error)
    }

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

    override suspend fun sendPoll(roomId: String, question: String, answers: List<String>): Result<Unit> {
        val eventId = requireClient().sendPollStart(roomId, question, answers.toJsArray(), "disclosed", 1.0).awaitString()
        return if (eventId != null) Result.success(Unit) else Result.failure(Exception("Failed to send poll"))
    }

    override fun seenByForEvent(roomId: String, eventId: String, limit: Int): List<SeenByEntry> =
        emptyList()

    override suspend fun mxcThumbnailToCache(mxcUri: String, width: Int, height: Int, crop: Boolean): String =
        requireClient().mxcThumbnailToCache(mxcUri, width.toDouble(), height.toDouble(), crop).awaitString() ?: ""

    override suspend fun loadRoomListCache(): List<RoomListEntry> =
        wasmJson.decodeFromJsonElement(requireClient().loadRoomListCache().toJsonArray())

    override suspend fun sendPollResponse(roomId: String, pollEventId: String, answers: List<String>): Result<Unit> =
        unitResult(
            requireClient().sendPollResponse(roomId, pollEventId, answers.toJsArray()).awaitPlainBool(),
            "send poll response"
        )

    override suspend fun sendPollEnd(roomId: String, pollEventId: String): Result<Unit> =
        requireClient().sendPollEnd(roomId, pollEventId).awaitUnitResult()

    override suspend fun startElementCall(
        roomId: String,
        intent: CallIntent,
        elementCallUrl: String?,
        parentUrl: String?,
        languageTag: String?,
        theme: String?,
        observer: CallWidgetObserver
    ): CallSession? {
        val jsObserver = jsWidgetObserver { msg: String ->
            observer.onToWidget(msg)
        }
        val result = requireClient().startElementCall(
            roomId,
            elementCallUrl,
            parentUrl,
            intent.name,
            languageTag,
            theme,
            jsObserver
        ).await<JsAny?>()

        return decodeValueOrNull<CallSession>(result, "startElementCall")
    }

    override fun callWidgetFromWebview(sessionId: ULong, message: String): Boolean =
        requireClient().callWidgetFromWebview(sessionId.toDouble(), message)

    override fun stopElementCall(sessionId: ULong): Boolean =
        requireClient().stopElementCall(sessionId.toDouble())

    override suspend fun roomPreview(idOrAlias: String): Result<RoomPreview> {
        val value = requireClient().roomPreview(idOrAlias).awaitValue<RoomPreview>()
        return if (value != null) Result.success(value)
        else Result.failure(Exception("Failed to load room preview"))
    }

    override suspend fun knock(idOrAlias: String): Result<Unit> =
        requireClient().knock(idOrAlias).awaitUnitResult()

    override suspend fun listKnockRequests(roomId: String): List<KnockRequestSummary> {
        return emptyList()
    }

    override suspend fun acceptKnockRequest(roomId: String, userId: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Not implemented on web"))
    }

    override suspend fun declineKnockRequest(roomId: String, userId: String, reason: String?): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Not implemented on web"))
    }

    override fun startDeviceVerification(deviceId: String): Flow<VerifEvent> = callbackFlow {
        val flowId = requireClient().startDeviceVerification(
            deviceId,
            jsCallback1 { event: JsAny? ->
                val jsonStr = event?.toString() ?: return@jsCallback1
                val parsed = runCatching {
                    verifJson.decodeFromString<VerifEvent>(jsonStr)
                }.getOrNull() ?: return@jsCallback1
                trySend(parsed)
            }
        ).await<JsAny?>()?.toString().orEmpty()
        if (flowId.isBlank()) {
            trySend(VerifEvent.Error("Failed to start device verification"))
            channel.close()
            return@callbackFlow
        }
        awaitClose { }
    }

    override fun startUserVerification(userId: String): Flow<VerifEvent> = callbackFlow {
        val flowId = requireClient().startUserVerification(
            userId,
            jsCallback1 { event: JsAny? ->
                val jsonStr = event?.toString() ?: return@jsCallback1
                val parsed = runCatching {
                    verifJson.decodeFromString<VerifEvent>(jsonStr)
                }.getOrNull() ?: return@jsCallback1
                trySend(parsed)
            }
        ).await<JsAny?>()?.toString().orEmpty()
        if (flowId.isBlank()) {
            trySend(VerifEvent.Error("Failed to start user verification"))
            channel.close()
            return@callbackFlow
        }
        awaitClose { }
    }

    override fun acceptAndObserveVerification(flowId: String, otherUserId: String): Flow<VerifEvent> = callbackFlow {
        val ok = requireClient().acceptAndObserveVerification(
            flowId,
            otherUserId,
            jsCallback1 { event: JsAny? ->
                val jsonStr = event?.toString() ?: return@jsCallback1
                runCatching {
                    verifJson.decodeFromString<VerifEvent>(jsonStr)
                }.getOrNull()?.let { trySend(it) }
            }
        ).awaitPlainBool()
        if (!ok) {
            trySend(VerifEvent.Error("Failed to accept verification"))
            channel.close()
            return@callbackFlow
        }
        awaitClose { }
    }

    override suspend fun acceptSas(flowId: String, otherUserId: String): Boolean =
        requireClient().acceptSas(flowId, otherUserId).awaitPlainBool()

    override suspend fun confirmSas(flowId: String): Boolean =
        requireClient().confirmSas(flowId).awaitPlainBool()

    override suspend fun cancelVerification(flowId: String): Boolean =
        requireClient().cancelVerification(flowId).awaitPlainBool()
}

class WebVerificationService(private val client: WasmClient) : VerificationService {
    override fun startDeviceVerification(deviceId: String): Flow<VerifEvent> = callbackFlow {
        val flowId = client.startDeviceVerification(
            deviceId,
            jsCallback1 { event: JsAny? ->
                val jsonStr = event?.toString() ?: return@jsCallback1
                val parsed = runCatching {
                    verifJson.decodeFromString<VerifEvent>(jsonStr)
                }.getOrNull() ?: return@jsCallback1
                trySend(parsed)
            }
        ).await<JsAny?>()?.toString().orEmpty()
        if (flowId.isBlank()) {
            trySend(VerifEvent.Error("Failed to start device verification"))
            channel.close()
            return@callbackFlow
        }
        awaitClose { }
    }

    override fun startUserVerification(userId: String): Flow<VerifEvent> = callbackFlow {
        val flowId = client.startUserVerification(
            userId,
            jsCallback1 { event: JsAny? ->
                val jsonStr = event?.toString() ?: return@jsCallback1
                val parsed = runCatching {
                    verifJson.decodeFromString<VerifEvent>(jsonStr)
                }.getOrNull() ?: return@jsCallback1
                trySend(parsed)
            }
        ).await<JsAny?>()?.toString().orEmpty()
        if (flowId.isBlank()) {
            trySend(VerifEvent.Error("Failed to start user verification"))
            channel.close()
            return@callbackFlow
        }
        awaitClose { }
    }

    override suspend fun confirmSas(flowId: String): Boolean =
        client.confirmSas(flowId).awaitPlainBool()

    override suspend fun cancelVerification(flowId: String): Boolean =
        client.cancelVerification(flowId).awaitPlainBool()

    override fun acceptAndObserveVerification(flowId: String, otherUserId: String): Flow<VerifEvent> = callbackFlow {
        val ok = client.acceptAndObserveVerification(
            flowId,
            otherUserId,
            jsCallback1 { event: JsAny? ->
                val jsonStr = event?.toString() ?: return@jsCallback1
                runCatching {
                    verifJson.decodeFromString<VerifEvent>(jsonStr)
                }.getOrNull()?.let { trySend(it) }
            }
        ).awaitPlainBool()
        if (!ok) {
            trySend(VerifEvent.Error("Failed to accept verification"))
            channel.close()
            return@callbackFlow
        }
        awaitClose { }
    }

    override suspend fun acceptSas(flowId: String, otherUserId: String): Boolean =
        client.acceptSas(flowId, otherUserId).awaitPlainBool()
}

actual fun createMatrixPort(): MatrixPort = WebStubMatrixPort()