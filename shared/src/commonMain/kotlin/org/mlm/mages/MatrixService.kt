package org.mlm.mages

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mages.shared.generated.resources.*
import org.mlm.mages.accounts.AccountStore
import org.mlm.mages.accounts.MatrixAccount
import org.mlm.mages.accounts.MatrixClients
import org.mlm.mages.matrix.*
import org.mlm.mages.storage.AvatarLoader
import org.mlm.mages.storage.LinkPreviewLoader
import org.mlm.mages.storage.ProfileCache
import kotlin.concurrent.Volatile
import org.jetbrains.compose.resources.getString
import mages.shared.generated.resources.Res

class MatrixService(
    val accountStore: AccountStore,
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

    @Volatile
    private var _avatars: AvatarLoader? = null
    val avatars: AvatarLoader
        get() {
            val currentPort = clients.port
            val loader = _avatars
            if (loader != null && loader.port === currentPort) return loader
            loader?.shutdown()
            return AvatarLoader(currentPort).also { _avatars = it }
        }

    @Volatile
    private var _linkPreviews: LinkPreviewLoader? = null
    val linkPreviews: LinkPreviewLoader
        get() {
            val currentPort = clients.port
            val loader = _linkPreviews
            if (loader != null && loader.port === currentPort) return loader
            loader?.shutdown()
            return LinkPreviewLoader(currentPort).also { _linkPreviews = it }
        }

    @Volatile
    private var _profiles: ProfileCache? = null
    val profiles: ProfileCache
        get() {
            val currentPort = clients.port
            val cache = _profiles
            if (cache != null && cache.port === currentPort) return cache
            cache?.shutdown()
            return ProfileCache(currentPort).also { _profiles = it }
        }

    suspend fun initFromDisk(proxyUrl: String? = null): Boolean =
        clients.initFromDisk(proxyUrl)

    suspend fun init(hs: String) {
        port.init(hs.trim())
    }

    suspend fun login(user: String, password: String, deviceDisplayName: String?) {
        port.login(user.trim(), password, deviceDisplayName)
    }

    suspend fun loginEmail(email: String, password: String, deviceDisplayName: String?) {
        port.loginEmail(email.trim(), password, deviceDisplayName)
    }

    suspend fun loginPhone(country: String, phone: String, password: String, deviceDisplayName: String?) {
        port.loginPhone(country.trim().uppercase(), phone.trim(), password, deviceDisplayName)
    }

    fun isLoggedIn(): Boolean = clients.hasActiveClient()

    suspend fun isLoggedInSuspend(): Boolean = clients.hasActiveClient()

    fun observeSends(): Flow<SendUpdate> = port.observeSends()

    suspend fun thumbnailToCache(info: AttachmentInfo, w: Int, h: Int, crop: Boolean) =
        port.thumbnailToCache(info, w, h, crop)

    suspend fun startSupervisedSync(externalObserver: MatrixPort.SyncObserver? = null) {
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

    suspend fun switchAccount(account: MatrixAccount): Result<Unit> {
        resetSyncState()
        _avatars?.shutdown()
        _avatars = null
        _linkPreviews?.shutdown()
        _linkPreviews = null
        _profiles?.shutdown()
        _profiles = null
        val ok = clients.switchTo(account)
        return if (ok) {
            Result.success(Unit)
        } else {
            Result.failure(Exception(getString(Res.string.failed_to_switch_account)))
        }
    }

    suspend fun removeAccount(accountId: String) {
        if (clients.activeAccount.value?.id == accountId) {
            resetSyncState()
            _avatars?.shutdown()
            _avatars = null
            _linkPreviews?.shutdown()
            _linkPreviews = null
            _profiles?.shutdown()
            _profiles = null
        }
        clients.removeAccount(accountId)
    }

    fun timelineDiffs(roomId: String): Flow<TimelineDiff<MessageEvent>> = port.timelineDiffs(roomId)

    suspend fun sendMessage(roomId: String, body: String, formattedBody: String? = null): Result<Unit> =
        port.send(roomId, body, formattedBody)

    suspend fun paginateBack(roomId: String, count: Int): Result<Boolean> =
        port.paginateBack(roomId, count)

    suspend fun markRead(roomId: String, sendPublicReceipt: Boolean = true): Result<Unit> =
        port.markRead(roomId, sendPublicReceipt)

    suspend fun markReadAt(roomId: String, eventId: String, sendPublicReceipt: Boolean = true): Result<Unit> =
        port.markReadAt(roomId, eventId, sendPublicReceipt)

    suspend fun markFullyReadAt(roomId: String, eventId: String, sendPublicReceipt: Boolean = true): Result<Unit> =
        port.markFullyReadAt(roomId, eventId, sendPublicReceipt)

    suspend fun markRoomSeenLatest(roomId: String, sendPublicReceipt: Boolean): Result<Boolean> =
        port.markRoomSeenLatest(roomId, sendPublicReceipt)

    suspend fun react(
        roomId: String,
        eventId: String,
        key: String,
        shortcode: String? = null
    ): Result<Unit> = port.react(roomId, eventId, key, shortcode)

    suspend fun reply(roomId: String, inReplyToEventId: String, body: String, formattedBody: String? = null): Result<Unit> =
        port.reply(roomId, inReplyToEventId, body, formattedBody)

    suspend fun edit(roomId: String, targetEventId: String, newBody: String, formattedBody: String? = null): Result<Unit> =
        port.edit(roomId, targetEventId, newBody, formattedBody)

    suspend fun redact(roomId: String, eventId: String, reason: String? = null): Result<Unit> =
        port.redact(roomId, eventId, reason)

    suspend fun observeTyping(roomId: String, onUpdate: (List<String>) -> Unit): ULong =
        port.observeTyping(roomId, onUpdate)

    fun stopTypingObserver(token: ULong) = port.stopTypingObserver(token)

    suspend fun listMyDevices(): Result<List<DeviceSummary>> =
        runCatching { port.listMyDevices() }

    suspend fun logout(): Result<Unit> = runCatching {
        supervisedSyncStarted = false
        val ok = port.logout()
        check(ok) { getString(Res.string.logout_failed) }
    }

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
        onProgress: ((sent: Long, total: Long?) -> Unit)? = null,
    ): Result<Unit> = runCatching {
        val ok = port.sendAttachmentFromPath(roomId, path, mime, filename, caption, formattedCaption, replyToEventId, voiceDurationMs, voiceWaveform, isVoice, txnId, onProgress)
        check(ok) { getString(Res.string.failed_to_send_attachment) }
    }

    suspend fun sendStickerFromPath(
        roomId: String,
        path: String,
        mime: String,
        body: String,
        filename: String? = null,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Result<Unit> = runCatching {
        val ok = port.sendStickerFromPath(roomId, path, mime, body, filename, onProgress)
        check(ok) { getString(Res.string.failed_to_send_sticker) }
    }

    suspend fun downloadStickerToCache(
        info: StickerInfo,
        filenameHint: String? = null,
    ): Result<String> = port.downloadStickerToCache(info, filenameHint)

    suspend fun recoverWithKey(recoveryKey: String): Result<Unit> =
        port.recoverWithKey(recoveryKey)

    suspend fun retryByTxn(roomId: String, txnId: String): Result<Unit> = runCatching {
        val ok = port.retryByTxn(roomId, txnId)
        check(ok) { getString(Res.string.retry_failed) }
    }

    suspend fun cancelSend(roomId: String, txnId: String): Result<Unit> = runCatching {
        val ok = port.cancelByTxn(roomId, txnId)
        check(ok) { getString(Res.string.delete_failed) }
    }

    suspend fun isSpace(roomId: String): Boolean =
        port.isSpace(roomId)

    suspend fun mySpaces(): List<SpaceInfo> =
        port.mySpaces()

    suspend fun createSpace(
        name: String,
        topic: String?,
        isPublic: Boolean,
        invitees: List<String>
    ): Result<String> {
        val result = port.createSpace(name, topic, isPublic, invitees)
        return if (result != null) Result.success(result) else Result.failure(Exception(getString(Res.string.failed_to_create_space)))
    }

    suspend fun spaceAddChild(
        spaceId: String,
        childRoomId: String,
        order: String? = null,
        suggested: Boolean? = null
    ): Result<Unit> = port.spaceAddChild(spaceId, childRoomId, order, suggested)

    suspend fun spaceRemoveChild(spaceId: String, childRoomId: String): Result<Unit> =
        port.spaceRemoveChild(spaceId, childRoomId)

    suspend fun spaceHierarchy(
        spaceId: String,
        from: String? = null,
        limit: Int = 50,
        maxDepth: Int? = null,
        suggestedOnly: Boolean = false
    ): Result<SpaceHierarchyPage> {
        val result = port.spaceHierarchy(spaceId, from, limit, maxDepth, suggestedOnly)
        return if (result != null) Result.success(result) else Result.failure(Exception(getString(Res.string.failed_to_load_space_contents)))
    }

    suspend fun spaceInviteUser(spaceId: String, userId: String): Result<Unit> =
        port.spaceInviteUser(spaceId, userId)

    suspend fun listSections(): List<SpaceSection> = port.listSections()

    suspend fun createSection(name: String, spaceId: String): Result<SpaceSection> {
        val result = port.createSection(name, spaceId)
        return result?.let { Result.success(it) }
            ?: Result.failure(Exception(getString(Res.string.failed_to_create_section)))
    }

    suspend fun renameSection(tag: String, name: String): Result<Unit> =
        port.renameSection(tag, name)

    suspend fun deleteSection(tag: String): Result<Unit> = port.deleteSection(tag)

    suspend fun moveSection(tag: String, index: Int): Result<Unit> =
        port.moveSection(tag, index)

    suspend fun setRoomSection(roomId: String, tag: String?): Result<Unit> =
        port.setRoomSection(roomId, tag)

    // The SDK keeps the space graph live, so this is a cheap in-memory lookup and needs no
    // caching of its own.
    suspend fun roomParentSpaces(roomId: String): List<SpaceParentInfo> =
        port.roomParentSpaces(roomId)

    suspend fun spaceUnreadCounts(): List<SpaceUnread> =
        port.spaceUnreadCounts()
}
