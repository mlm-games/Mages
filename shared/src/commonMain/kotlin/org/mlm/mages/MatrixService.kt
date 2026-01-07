package org.mlm.mages

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mlm.mages.accounts.MatrixAccount
import org.mlm.mages.accounts.MatrixClients
import org.mlm.mages.matrix.*
import org.mlm.mages.storage.AvatarLoader
import kotlin.concurrent.Volatile

class MatrixService(
    private val clients: MatrixClients
) {
    val port: MatrixPort
        get() = clients.port

    val portOrNull: MatrixPort?
        get() = clients.portOrNull

    val activeAccount: StateFlow<MatrixAccount?>
        get() = clients.activeAccount

    val isReady: StateFlow<Boolean>
        get() = clients.isReady

    @Volatile
    private var supervisedSyncStarted = false

    private val _syncStatus = MutableStateFlow<MatrixPort.SyncStatus?>(null)
    val syncStatus: StateFlow<MatrixPort.SyncStatus?> = _syncStatus.asStateFlow()

    private var _avatars: AvatarLoader? = null
    val avatars: AvatarLoader
        get() {
            val current = _avatars
            if (current != null && clients.portOrNull != null) return current
            val newLoader = AvatarLoader(port)
            _avatars = newLoader
            return newLoader
        }

    suspend fun initFromDisk(): Boolean {
        val result = clients.initFromDisk()
        if (result && clients.portOrNull != null) {
            _avatars = AvatarLoader(port)
        }
        return result
    }

    suspend fun init(hs: String) {
        port.init(hs.trim())
    }

    suspend fun login(user: String, password: String, deviceDisplayName: String?) {
        port.login(user.trim(), password, deviceDisplayName)
    }

    fun isLoggedIn(): Boolean = clients.hasActiveClient()

    fun observeSends(): Flow<SendUpdate> = port.observeSends()

    suspend fun thumbnailToCache(info: AttachmentInfo, w: Int, h: Int, crop: Boolean) =
        port.thumbnailToCache(info, w, h, crop)

    fun startSupervisedSync(externalObserver: MatrixPort.SyncObserver? = null) {
        if (supervisedSyncStarted) return
        supervisedSyncStarted = true

        val wrappedObserver = object : MatrixPort.SyncObserver {
            override fun onState(status: MatrixPort.SyncStatus) {
                _syncStatus.value = status
                externalObserver?.onState(status)
            }
        }
        runCatching { port.startSupervisedSync(wrappedObserver) }
    }

    fun resetSyncState() {
        supervisedSyncStarted = false
        _syncStatus.value = null
    }

    suspend fun switchAccount(account: MatrixAccount): Boolean {
        resetSyncState()
        _avatars = null
        val result = clients.switchTo(account)
        if (result) {
            _avatars = AvatarLoader(port)
        }
        return result
    }

    suspend fun removeAccount(accountId: String) {
        if (clients.activeAccount.value?.id == accountId) {
            resetSyncState()
            _avatars = null
        }
        clients.removeAccount(accountId)
        if (clients.hasActiveClient()) {
            _avatars = AvatarLoader(port)
        }
    }

    fun timelineDiffs(roomId: String): Flow<TimelineDiff<MessageEvent>> = port.timelineDiffs(roomId)

    suspend fun sendMessage(roomId: String, body: String): Boolean = port.send(roomId, body)

    suspend fun paginateBack(roomId: String, count: Int) =
        runCatching { port.paginateBack(roomId, count) }.getOrElse { false }

    suspend fun paginateForward(roomId: String, count: Int) =
        runCatching { port.paginateForward(roomId, count) }.getOrElse { false }

    suspend fun markRead(roomId: String) =
        runCatching { port.markRead(roomId) }.getOrElse { false }

    suspend fun markReadAt(roomId: String, eventId: String) =
        runCatching { port.markReadAt(roomId, eventId) }.getOrElse { false }

    suspend fun react(roomId: String, eventId: String, emoji: String) =
        runCatching { port.react(roomId, eventId, emoji) }.getOrElse { false }

    suspend fun reply(roomId: String, inReplyToEventId: String, body: String) =
        runCatching { port.reply(roomId, inReplyToEventId, body) }.getOrElse { false }

    suspend fun edit(roomId: String, targetEventId: String, newBody: String) =
        runCatching { port.edit(roomId, targetEventId, newBody) }.getOrElse { false }

    suspend fun redact(roomId: String, eventId: String, reason: String? = null) =
        runCatching { port.redact(roomId, eventId, reason) }.getOrElse { false }

    fun observeTyping(roomId: String, onUpdate: (List<String>) -> Unit): ULong =
        port.observeTyping(roomId, onUpdate)

    fun stopTypingObserver(token: ULong) = port.stopTypingObserver(token)

    suspend fun enqueueText(roomId: String, body: String, txnId: String? = null) =
        port.enqueueText(roomId, body, txnId)

    suspend fun listMyDevices(): List<DeviceSummary> =
        runCatching { port.listMyDevices() }.getOrElse { emptyList() }

    suspend fun startSelfSas(deviceId: String, observer: VerificationObserver) =
        port.startSelfSas(deviceId, observer)

    suspend fun startUserSas(userId: String, observer: VerificationObserver) =
        port.startUserSas(userId, observer)

    suspend fun acceptVerificationRequest(flowId: String, otherUserId: String?, observer: VerificationObserver) =
        port.acceptVerificationRequest(flowId, otherUserId, observer)

    suspend fun acceptSas(flowId: String, otherUserId: String?, observer: VerificationObserver) =
        port.acceptSas(flowId, otherUserId, observer)

    suspend fun confirmVerification(flowId: String) = port.confirmVerification(flowId)
    suspend fun cancelVerification(flowId: String) = port.cancelVerification(flowId)
    suspend fun cancelVerificationRequest(flowId: String, otherUserId: String?) =
        port.cancelVerificationRequest(flowId, otherUserId)

    suspend fun logout(): Boolean {
        supervisedSyncStarted = false
        return port.logout()
    }

    suspend fun sendAttachmentFromPath(
        roomId: String,
        path: String,
        mime: String,
        filename: String? = null,
        onProgress: ((Long, Long?) -> Unit)? = null
    ) = runCatching { port.sendAttachmentFromPath(roomId, path, mime, filename, onProgress) }.getOrElse { false }

    suspend fun sendAttachmentBytes(
        roomId: String,
        data: ByteArray,
        mime: String,
        filename: String,
        onProgress: ((Long, Long?) -> Unit)? = null
    ) = runCatching { port.sendAttachmentBytes(roomId, data, mime, filename, onProgress) }.getOrElse { false }

    suspend fun recoverWithKey(recoveryKey: String) =
        runCatching { port.recoverWithKey(recoveryKey) }.getOrElse { false }

    suspend fun retryByTxn(roomId: String, txnId: String) =
        runCatching { port.retryByTxn(roomId, txnId) }.getOrElse { false }

    suspend fun isSpace(roomId: String): Boolean =
        runCatching { port.isSpace(roomId) }.getOrDefault(false)

    suspend fun mySpaces(): List<SpaceInfo> =
        runCatching { port.mySpaces() }.getOrDefault(emptyList())

    suspend fun createSpace(
        name: String,
        topic: String?,
        isPublic: Boolean,
        invitees: List<String>
    ): String? = runCatching { port.createSpace(name, topic, isPublic, invitees) }.getOrNull()

    suspend fun spaceAddChild(
        spaceId: String,
        childRoomId: String,
        order: String? = null,
        suggested: Boolean? = null
    ): Boolean = runCatching { port.spaceAddChild(spaceId, childRoomId, order, suggested) }.getOrDefault(false)

    suspend fun spaceRemoveChild(spaceId: String, childRoomId: String): Boolean =
        runCatching { port.spaceRemoveChild(spaceId, childRoomId) }.getOrDefault(false)

    suspend fun spaceHierarchy(
        spaceId: String,
        from: String? = null,
        limit: Int = 50,
        maxDepth: Int? = null,
        suggestedOnly: Boolean = false
    ): SpaceHierarchyPage? = runCatching {
        port.spaceHierarchy(spaceId, from, limit, maxDepth, suggestedOnly)
    }.getOrNull()

    suspend fun spaceInviteUser(spaceId: String, userId: String): Boolean =
        runCatching { port.spaceInviteUser(spaceId, userId) }.getOrDefault(false)

    suspend fun startCall(
        roomId: String,
        intent: CallIntent,
        elementCallUrl: String? = null,
        languageTag: String? = null,
        theme: String? = null,
        onToWidget: (String) -> Unit
    ): CallSession? {
        val observer = object : CallWidgetObserver {
            override fun onToWidget(message: String) = onToWidget(message)
        }
        return port.startElementCall(roomId, intent, elementCallUrl, languageTag, theme, observer)
    }
}