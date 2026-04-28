package org.mlm.mages.matrix

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mages.FfiException
import mages.FfiPushRuleKind
import mages.FfiRoomNotificationMode
import mages.TimelineDiffKind
import mages.Client as FfiClient
import mages.RoomSummary as FfiRoom
import mages.ActionAvailability as FfiActionAvailability
import mages.ActionPresentation as FfiActionPresentation
import mages.RoomActionState as FfiRoomActionState
import mages.MemberActionState as FfiMemberActionState
import mages.MessageActionState as FfiMessageActionState
import org.mlm.mages.*
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.platform.platformNeedsControlledAudioDevices

private inline fun <T> runWithFfiResult(block: () -> T): Result<T> =
    runCatching(block).recoverCatching { e ->
        val mapped = (e as? FfiException)?.let { ex ->
            when (ex) {
                is FfiException.Msg -> IllegalStateException(ex.v1)
//                else -> IllegalStateException(e.message ?: "Unknown error")
            }
        } ?: e as? Exception ?: IllegalStateException(e.toString())
        throw mapped
    }

private val matrixDispatcher = Dispatchers.IO.limitedParallelism(4)

class RustMatrixPort : MatrixPort, VerificationService {
    @Volatile
    private var client: FfiClient? = null
    private val clientLock = Any()
    private var currentHs: String? = null
    private var currentAccountId: String? = null

    override suspend fun init(hs: String, accountId: String?) =
        withContext(matrixDispatcher) {
            synchronized(clientLock) {
                if (currentHs == hs && currentAccountId == accountId && client != null) return@synchronized

                client?.let { c ->
                    runCatching { c.shutdown() }
                    runCatching { c.close() }
                }

                client = FfiClient(hs, MagesPaths.storeDir(), accountId)
                currentHs = hs
                currentAccountId = accountId
            }
        }

    private inline fun <T> withClient(block: (FfiClient) -> T): T {
        val c = client ?: error("Matrix client not initialized. Wait for init call.")
        return block(c)
    }

    override fun close() {
        runBlocking(matrixDispatcher) {
            synchronized(clientLock) {
                withClient {
                    it.let { c ->
                        runCatching { c.shutdown() }
                    }
                }
            }
        }
    }

    override suspend fun login(user: String, password: String, deviceDisplayName: String?) =
        withContext(matrixDispatcher) {
            withClient {
                it.login(user, password, deviceDisplayName)
            }
        }

    override suspend fun loginEmail(email: String, password: String, deviceDisplayName: String?) =
        withContext(matrixDispatcher) {
            withClient {
                it.loginEmail(email, password, deviceDisplayName)
            }
        }

    override suspend fun loginPhone(country: String, phone: String, password: String, deviceDisplayName: String?) =
        withContext(matrixDispatcher) {
            withClient {
                it.loginPhone(country, phone, password, deviceDisplayName)
            }
        }

    override fun isLoggedIn(): Boolean = // NOTE: Single use only
        runBlocking(matrixDispatcher) {
            synchronized(clientLock) { client?.isLoggedIn() ?: false }
        }

    override suspend fun isLoggedInSuspend(): Boolean =
        withContext(matrixDispatcher) {
            synchronized(clientLock) { client?.isLoggedIn() ?: false }
        }

    override suspend fun listRooms(): List<RoomSummary> =
        withContext(matrixDispatcher) {
            withClient { it.rooms().map { r -> r.toModel() } }
        }

    override suspend fun recent(roomId: String, limit: Int): List<MessageEvent> =
        withContext(matrixDispatcher) {
            withClient { cl ->
                cl.recentEvents(roomId, limit.toUInt()).map { it.toModel() }
            }
        }

    override fun timelineDiffs(roomId: String): Flow<TimelineDiff<MessageEvent>> = callbackFlow {
        val obs = object : mages.TimelineObserver {
            override fun onDiff(diff: TimelineDiffKind) {
                val mapped: TimelineDiff<MessageEvent>? = when (diff) {
                    is TimelineDiffKind.Reset -> TimelineDiff.Reset(diff.values.map { it.toModel() })
                    is TimelineDiffKind.Clear -> TimelineDiff.Clear()
                    is TimelineDiffKind.Append -> {
                        val items = diff.values.map { it.toModel() }
                        if (items.isNotEmpty()) TimelineDiff.Append(items) else null
                    }
                    is TimelineDiffKind.PushBack -> TimelineDiff.Append(listOf(diff.value.toModel()))
                    is TimelineDiffKind.PushFront -> TimelineDiff.Prepend(diff.value.toModel())
                    is TimelineDiffKind.UpdateByItemId -> TimelineDiff.UpdateByItemId(diff.itemId, diff.value.toModel())
                    is TimelineDiffKind.RemoveByItemId -> TimelineDiff.RemoveByItemId(diff.itemId)
                    is TimelineDiffKind.UpsertByItemId -> TimelineDiff.UpsertByItemId(diff.itemId, diff.value.toModel())
                    is TimelineDiffKind.PopBack,
                    is TimelineDiffKind.PopFront,
                    is TimelineDiffKind.Truncate -> null
                }
                mapped?.let { trySendBlocking(it) }
            }

            override fun onError(message: String) {
                println("Err: $message")
            }
        }

        val token = withContext(matrixDispatcher) {
            withClient { it.observeTimeline(roomId, obs) }
        }
        awaitClose {
            runBlocking(matrixDispatcher) {
                withClient { it.unobserveTimeline(token) }
            }
        }
    }

    override suspend fun observeConnection(observer: MatrixPort.ConnectionObserver): ULong =
        withContext(matrixDispatcher) {
            val cb = object : mages.ConnectionObserver {
                override fun onConnectionChange(state: mages.ConnectionState) {
                    val mapped = when (state) {
                        is mages.ConnectionState.Disconnected -> MatrixPort.ConnectionState.Disconnected
                        is mages.ConnectionState.Connecting -> MatrixPort.ConnectionState.Connecting
                        is mages.ConnectionState.Connected -> MatrixPort.ConnectionState.Connected
                        is mages.ConnectionState.Syncing -> MatrixPort.ConnectionState.Syncing
                        is mages.ConnectionState.Reconnecting -> MatrixPort.ConnectionState.Reconnecting
                    }
                    observer.onConnectionChange(mapped)
                }
            }
            withClient { it.monitorConnection(cb) }
        }

    override fun stopConnectionObserver(token: ULong) {
        runBlocking(matrixDispatcher) {
            withClient { it.unobserveConnection(token) }
        }
    }

    override suspend fun startVerificationInbox(observer: MatrixPort.VerificationInboxObserver): ULong =
        withContext(matrixDispatcher) {
            val cb = object : mages.VerificationInboxObserver {
                override fun onRequest(flowId: String, fromUser: String, fromDevice: String) {
                    observer.onRequest(flowId, fromUser, fromDevice)
                }

                override fun onError(message: String) {
                    observer.onError(message)
                }
            }
            withClient { it.startVerificationInbox(cb) }
        }

    override fun stopVerificationInbox(token: ULong) {
        runBlocking(matrixDispatcher) {
            withClient { it.unobserveVerificationInbox(token) }
        }
    }

    override suspend fun send(roomId: String, body: String, formattedBody: String?): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.sendMessage(roomId, body, formattedBody) } }
        }

    override suspend fun sendQueueSetEnabled(enabled: Boolean): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.sendQueueSetEnabled(enabled) } }
        }

    override suspend fun sendExistingAttachment(
        roomId: String,
        attachment: AttachmentInfo,
        body: String?,
        onProgress: ((Long, Long?) -> Unit)?
    ): Result<Unit> = withContext(matrixDispatcher) {
        val cb = if (onProgress != null) object : mages.ProgressObserver {
            override fun onProgress(sent: ULong, total: ULong?) {
                onProgress(sent.toLong(), total?.toLong())
            }
        } else null

        runWithFfiResult { withClient { it.sendExistingAttachment(roomId, attachment.toFfi(), body, cb) } }
    }

    override fun observeSends(): Flow<SendUpdate> = callbackFlow {
        val obs = object : mages.SendObserver {
            override fun onUpdate(update: mages.SendUpdate) {
                trySend(
                    SendUpdate(
                        roomId = update.roomId,
                        txnId = update.txnId,
                        attempts = update.attempts.toInt(),
                        state = when (update.state) {
                            mages.SendState.ENQUEUED -> SendState.Enqueued
                            mages.SendState.SENDING -> SendState.Sending
                            mages.SendState.SENT -> SendState.Sent
                            mages.SendState.RETRYING -> SendState.Retrying
                            mages.SendState.FAILED -> SendState.Failed
                        },
                        eventId = update.eventId,
                        error = update.error
                    )
                )
            }
        }
        val token = withContext(matrixDispatcher) {
            withClient { it.observeSends(obs) }
        }
        awaitClose {
            runBlocking(matrixDispatcher) {
                withClient { it.unobserveSends(token) }
            }
        }
    }

    override suspend fun thumbnailToCache(
        info: AttachmentInfo, width: Int, height: Int, crop: Boolean
    ): Result<String> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.thumbnailToCache(info.toFfi(), width.toUInt(), height.toUInt(), crop) } }
        }

    override suspend fun setTyping(roomId: String, typing: Boolean): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.setTyping(roomId, typing) } }
        }

    override fun whoami(): String? {
        return runBlocking(matrixDispatcher) {
            withClient { it.whoami() }
        }
    }

    override suspend fun accountManagementUrl(): String? {
        return withContext(matrixDispatcher) {
            withClient { it.accountManagementUrl() }
        }
    }

    override fun setupRecovery(observer: MatrixPort.RecoveryObserver): Boolean {
        val cb = object : mages.RecoveryObserver {
            override fun onProgress(step: String) {
                observer.onProgress(step)
            }
            override fun onDone(recoveryKey: String) {
                observer.onDone(recoveryKey)
            }
            override fun onError(message: String) {
                observer.onError(message)
            }
        }
        return runBlocking(matrixDispatcher) { withClient { it.setupRecovery(cb) } }
    }

    override fun observeRecoveryState(observer: MatrixPort.RecoveryStateObserver): ULong {
        val cb = object : mages.RecoveryStateObserver {
            override fun onUpdate(state: mages.RecoveryState) {
                val mapped = when (state) {
                    mages.RecoveryState.DISABLED -> MatrixPort.RecoveryState.Disabled
                    mages.RecoveryState.ENABLED -> MatrixPort.RecoveryState.Enabled
                    mages.RecoveryState.INCOMPLETE -> MatrixPort.RecoveryState.Incomplete
                    else -> MatrixPort.RecoveryState.Unknown
                }
                observer.onUpdate(mapped)
            }
        }
        return withClient { it.observeRecoveryState(cb) }
    }

    override fun unobserveRecoveryState(subId: ULong): Unit =
        withClient { it.unobserveRecoveryState(subId) }

    override fun observeBackupState(observer: MatrixPort.BackupStateObserver): ULong {
        val cb = object : mages.BackupStateObserver {
            override fun onUpdate(state: mages.BackupState) {
                val mapped = when (state) {
                    mages.BackupState.UNKNOWN -> MatrixPort.BackupState.Unknown
                    mages.BackupState.CREATING -> MatrixPort.BackupState.Creating
                    mages.BackupState.ENABLING -> MatrixPort.BackupState.Enabling
                    mages.BackupState.RESUMING -> MatrixPort.BackupState.Resuming
                    mages.BackupState.ENABLED -> MatrixPort.BackupState.Enabled
                    mages.BackupState.DOWNLOADING -> MatrixPort.BackupState.Downloading
                    mages.BackupState.DISABLING -> MatrixPort.BackupState.Disabling
                }
                observer.onUpdate(mapped)
            }
        }
        return withClient { it.observeBackupState(cb) }
    }

    override fun unobserveBackupState(subId: ULong): Unit =
        withClient { it.unobserveBackupState(subId) }

    override suspend fun backupExistsOnServer(fetch: Boolean): Boolean =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.backupExistsOnServer(fetch) } }.isSuccess
        }

    override suspend fun setKeyBackupEnabled(enabled: Boolean): Boolean =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.setKeyBackupEnabled(enabled) } }.isSuccess
        }

    override suspend fun paginateBack(roomId: String, count: Int): Result<Boolean> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.paginateBackwards(roomId, count.toUShort()) } }
        }

    override suspend fun paginateForward(roomId: String, count: Int): Result<Boolean> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.paginateForwards(roomId, count.toUShort()) } }
        }

    override suspend fun markRead(roomId: String): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.markRead(roomId) } }
        }

    override suspend fun markReadAt(roomId: String, eventId: String): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.markReadAt(roomId, eventId) } }
        }

    override suspend fun react(roomId: String, eventId: String, emoji: String): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.react(roomId, eventId, emoji) } }
        }

    override suspend fun reply(roomId: String, inReplyToEventId: String, body: String, formattedBody: String?): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.reply(roomId, inReplyToEventId, body, formattedBody) } }
        }

    override suspend fun edit(roomId: String, targetEventId: String, newBody: String, formattedBody: String?): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.edit(roomId, targetEventId, newBody, formattedBody) } }
        }

    override suspend fun redact(roomId: String, eventId: String, reason: String?): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.redact(roomId, eventId, reason) } }
        }

    override suspend fun reportContent(roomId: String, eventId: String, score: Int?, reason: String?): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.reportContent(roomId, eventId, score, reason) } }
        }

    override suspend fun getUserPowerLevel(roomId: String, userId: String): Long =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.getUserPowerLevel(roomId, userId) } }.getOrDefault(-1L)
        }

    override suspend fun getPinnedEvents(roomId: String): List<String> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.getPinnedEvents(roomId) } }.getOrDefault(emptyList())
        }

    override suspend fun setPinnedEvents(roomId: String, eventIds: List<String>): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.setPinnedEvents(roomId, eventIds) } }
        }

    override suspend fun observeTyping(roomId: String, onUpdate: (List<String>) -> Unit): ULong =
        withContext(matrixDispatcher) {
            val obs = object : mages.TypingObserver {
                override fun onUpdate(names: List<String>) {
                    onUpdate(names)
                }
            }
            withClient { it.observeTyping(roomId, obs) }
        }

    override fun stopTypingObserver(token: ULong) {
        runBlocking(matrixDispatcher) {
            withClient { it.unobserveTyping(token) }
        }
    }

    override suspend fun observeReceipts(roomId: String, observer: ReceiptsObserver): ULong =
        withContext(matrixDispatcher) {
            val cb = object : mages.ReceiptsObserver {
                override fun onChanged() {
                    observer.onChanged()
                }
            }
            withClient { it.observeReceipts(roomId, cb) }
        }

    override fun stopReceiptsObserver(token: ULong) {
        runBlocking(matrixDispatcher) {
            withClient { it.unobserveReceipts(token) }
        }
    }

    override suspend fun dmPeerUserId(roomId: String): String? =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.dmPeerUserId(roomId) } }.getOrNull()
        }

    override suspend fun isEventReadBy(roomId: String, eventId: String, userId: String): Boolean =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.isEventReadBy(roomId, eventId, userId) } }.isSuccess
        }

    override suspend fun startCallInbox(observer: MatrixPort.CallObserver): ULong =
        withContext(matrixDispatcher) {
            val cb = object : mages.CallObserver {
                override fun onInvite(invite: mages.CallInvite) {
                    observer.onInvite(
                        CallInvite(
                            roomId = invite.roomId,
                            sender = invite.sender,
                            callId = invite.callId,
                            isVideo = invite.isVideo,
                            tsMs = invite.tsMs.toLong()
                        )
                    )
                }
            }
            withClient { it.startCallInbox(cb) }
        }

    override fun stopCallInbox(token: ULong) {
        runBlocking(matrixDispatcher) {
            withClient { it.stopCallInbox(token) }
        }
    }

    override suspend fun startSupervisedSync(observer: MatrixPort.SyncObserver) =
        withContext(matrixDispatcher) {
            val cb = object : mages.SyncObserver {
                override fun onState(status: mages.SyncStatus) {
                    val phase = when (status.phase) {
                        mages.SyncPhase.IDLE -> MatrixPort.SyncPhase.Idle
                        mages.SyncPhase.RUNNING -> MatrixPort.SyncPhase.Running
                        mages.SyncPhase.BACKING_OFF -> MatrixPort.SyncPhase.BackingOff
                        mages.SyncPhase.ERROR -> MatrixPort.SyncPhase.Error
                    }
                    observer.onState(MatrixPort.SyncStatus(phase, status.message))
                }
            }
            withClient { it.startSupervisedSync(cb) }
        }

    override suspend fun listMyDevices(): List<DeviceSummary> =
        withContext(matrixDispatcher) {
            runCatching {
                withClient {
                    it.devices().map { d ->
                        DeviceSummary(
                            deviceId = d.deviceId,
                            displayName = d.displayName,
                            ed25519 = d.ed25519,
                            isOwn = d.isOwn,
                            verified = d.verified
                        )
                    }
                }
            }.getOrElse { emptyList() }
        }

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "phase" }

    override fun startDeviceVerification(deviceId: String): Flow<VerifEvent> = callbackFlow {
        val listener = object : mages.VerifEventListener {
            override fun onEvent(eventJson: String) {
                runCatching {
                    json.decodeFromString<VerifEvent>(eventJson)
                }.getOrNull()?.let { trySend(it) }
            }
        }
        val flowId = withContext(matrixDispatcher) {
            client?.startDeviceVerification(deviceId, listener).orEmpty()
        }
        if (flowId.isBlank()) {
            trySend(VerifEvent.Error("Failed to start device verification"))
            channel.close()
            return@callbackFlow
        }
        awaitClose { }
    }

    override fun startUserVerification(userId: String): Flow<VerifEvent> = callbackFlow {
        val listener = object : mages.VerifEventListener {
            override fun onEvent(eventJson: String) {
                runCatching {
                    json.decodeFromString<VerifEvent>(eventJson)
                }.getOrNull()?.let { trySend(it) }
            }
        }
        val flowId = withContext(matrixDispatcher) {
            client?.startUserVerification(userId, listener).orEmpty()
        }
        if (flowId.isBlank()) {
            trySend(VerifEvent.Error("Failed to start user verification"))
            channel.close()
            return@callbackFlow
        }
        awaitClose { }
    }

    override fun acceptAndObserveVerification(flowId: String, otherUserId: String): Flow<VerifEvent> = callbackFlow {
        val listener = object : mages.VerifEventListener {
            override fun onEvent(eventJson: String) {
                runCatching {
                    json.decodeFromString<VerifEvent>(eventJson)
                }.getOrNull()?.let { trySend(it) }
            }
        }
        val ok = withContext(matrixDispatcher) {
            client?.acceptAndObserveVerification(flowId, otherUserId, listener) ?: false
        }
        if (!ok) {
            trySend(VerifEvent.Error("Failed to accept verification"))
            channel.close()
            return@callbackFlow
        }
        awaitClose { }
    }

    override suspend fun acceptSas(flowId: String, otherUserId: String): Boolean =
        withContext(matrixDispatcher) {
            client?.acceptSas(flowId, otherUserId.ifEmpty { null }) ?: false
        }

    override suspend fun confirmSas(flowId: String): Boolean =
        withContext(matrixDispatcher) {
            client?.confirmSas(flowId) ?: false
        }

    override suspend fun cancelVerification(flowId: String): Boolean =
        withContext(matrixDispatcher) {
            client?.cancelVerification(flowId) ?: false
        }

    override suspend fun logout(): Boolean =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.logout() } }.isSuccess
        }

    override suspend fun retryByTxn(roomId: String, txnId: String): Boolean =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.retryByTxn(roomId, txnId) } }.isSuccess
        }

    override fun enterForeground() {
        runBlocking(matrixDispatcher) {
            withClient { it.enterForeground() }
        }
    }

    override fun enterBackground() {
        runBlocking(matrixDispatcher) {
            withClient { it.enterBackground() }
        }
    }

    override suspend fun sendAttachmentFromPath(
        roomId: String,
        path: String,
        mime: String,
        filename: String?,
        onProgress: ((Long, Long?) -> Unit)?
    ): Boolean = withContext(matrixDispatcher) {
        val cb = if (onProgress != null) object : mages.ProgressObserver {
            override fun onProgress(sent: ULong, total: ULong?) {
                onProgress(sent.toLong(), total?.toLong())
            }
        } else null
        runWithFfiResult { withClient { it.sendAttachmentFromPath(roomId, path, mime, filename, cb) } }.isSuccess
    }

    override suspend fun downloadAttachmentToCache(
        info: AttachmentInfo,
        filenameHint: String?
    ): Result<String> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.downloadAttachmentToCacheFile(info.toFfi(), filenameHint).path } }
        }

    override suspend fun sendStickerFromPath(
        roomId: String,
        path: String,
        mime: String,
        body: String,
        filename: String?,
        onProgress: ((Long, Long?) -> Unit)?
    ): Boolean = withContext(matrixDispatcher) {
        val cb = if (onProgress != null) object : mages.ProgressObserver {
            override fun onProgress(sent: ULong, total: ULong?) {
                onProgress(sent.toLong(), total?.toLong())
            }
        } else null
        runWithFfiResult { withClient { it.sendStickerFromPath(roomId, path, mime, body, filename, cb) } }.isSuccess
    }

    override suspend fun downloadStickerToCache(
        info: StickerInfo,
        filenameHint: String?
    ): Result<String> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.downloadStickerToCache(info.toFfi(), filenameHint).path } }
        }

    override suspend fun recoverWithKey(recoveryKey: String): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.recoverWithKey(recoveryKey) } }
        }

    override suspend fun registerUnifiedPush(
        appId: String,
        pushKey: String,
        gatewayUrl: String,
        deviceName: String,
        lang: String,
        profileTag: String?,
    ): Boolean = withContext(matrixDispatcher) {
        runCatching {
            withClient { it.registerUnifiedpush(appId, pushKey, gatewayUrl, deviceName, lang, profileTag) }
        }.isSuccess
    }

    override suspend fun unregisterUnifiedPush(appId: String, pushKey: String): Boolean =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.unregisterUnifiedpush(appId, pushKey) } }.isSuccess
        }

    override suspend fun roomUnreadStats(roomId: String): UnreadStats? =
        withContext(matrixDispatcher) {
            runCatching {
                withClient {
                    it.roomUnreadStats(roomId)?.let { s ->
                        UnreadStats(
                            s.messages.toLong(),
                            s.notifications.toLong(),
                            s.mentions.toLong()
                        )
                    }
                }
            }.getOrNull()
        }

    override suspend fun roomNotificationMode(roomId: String): RoomNotificationMode? =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.roomNotificationMode(roomId)?.toKotlin() } }.getOrNull()
        }

    override suspend fun setRoomNotificationMode(
        roomId: String,
        mode: RoomNotificationMode
    ): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.setRoomNotificationMode(roomId, mode.toFfi()) } }
    }

    override suspend fun isPushRuleEnabled(kind: PushRuleKind, ruleId: String): Result<Boolean> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.isPushRuleEnabled(kind.toFfi(), ruleId) } }
        }

    override suspend fun setPushRuleEnabled(
        kind: PushRuleKind,
        ruleId: String,
        enabled: Boolean
    ): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.setPushRuleEnabled(kind.toFfi(), ruleId, enabled) } }
    }

    override suspend fun isReactionNotificationsEnabled(): Result<Boolean> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.isReactionNotificationsEnabled() } }
        }

    override suspend fun setReactionNotificationsEnabled(enabled: Boolean): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.setReactionNotificationsEnabled(enabled) } }
        }

    override suspend fun getDefaultRoomNotificationMode(
        isEncrypted: Boolean,
        isOneToOne: Boolean
    ): Result<RoomNotificationMode> = withContext(matrixDispatcher) {
        runWithFfiResult {
            withClient { it.getDefaultRoomNotificationMode(isEncrypted, isOneToOne).toKotlin() }
        }
    }

    override suspend fun setDefaultRoomNotificationMode(
        isEncrypted: Boolean,
        isOneToOne: Boolean,
        mode: RoomNotificationMode
    ): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult {
            withClient { it.setDefaultRoomNotificationMode(isEncrypted, isOneToOne, mode.toFfi()) }
        }
    }

    override suspend fun ownLastRead(roomId: String): Pair<String?, Long?> =
        withContext(matrixDispatcher) {
            runCatching {
                withClient { it.ownLastRead(roomId).let { r -> r.eventId to r.tsMs?.toLong() } }
            }.getOrElse { null to null }
        }

    override suspend fun observeOwnReceipt(roomId: String, observer: ReceiptsObserver): ULong =
        withContext(matrixDispatcher) {
            val cb = object : mages.ReceiptsObserver {
                override fun onChanged() {
                    observer.onChanged()
                }
            }
            withClient { it.observeOwnReceipt(roomId, cb) }
        }

    override suspend fun markFullyReadAt(roomId: String, eventId: String): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.markFullyReadAt(roomId, eventId) } }
        }

    override suspend fun markRoomSeenLatest(roomId: String, sendPublicReceipt: Boolean): Result<Boolean> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.markRoomSeenLatest(roomId, sendPublicReceipt) } }
        }

    override suspend fun observeRoomList(observer: MatrixPort.RoomListObserver): ULong =
        withContext(matrixDispatcher) {
            val currentVisible = linkedMapOf<String, RoomListEntry>()

            val cb = object : mages.RoomListObserver {
                override fun onReset(items: List<mages.RoomListEntry>) {
                    currentVisible.clear()
                    items.asSequence()
                        .map { it.toKotlinRoomListEntry() }
                        .filter { it.isVisibleInMainRoomList() }
                        .forEach { currentVisible[it.roomId] = it }

                    observer.onReset(currentVisible.values.toList())
                }

                override fun onUpdate(item: mages.RoomListEntry) {
                    val mapped = item.toKotlinRoomListEntry()

                    if (mapped.isVisibleInMainRoomList()) {
                        currentVisible[mapped.roomId] = mapped
                        observer.onUpdate(mapped)
                    } else {
                        val removed = currentVisible.remove(mapped.roomId)
                        if (removed != null) {
                            observer.onReset(currentVisible.values.toList())
                        }
                    }
                }
            }
            withClient { it.observeRoomList(cb) }
        }

    override fun unobserveRoomList(token: ULong) {
        runBlocking(matrixDispatcher) {
            withClient { it.unobserveRoomList(token) }
        }
    }

    override suspend fun fetchNotification(roomId: String, eventId: String): RenderedNotification? =
        withContext(matrixDispatcher) {
            runCatching {
                withClient {
                    it.fetchNotification(roomId, eventId)?.let { n ->
                        RenderedNotification(
                            roomId = n.roomId,
                            eventId = n.eventId,
                            roomName = n.roomName,
                            sender = n.sender,
                            body = n.body,
                            isNoisy = n.isNoisy,
                            hasMention = n.hasMention,
                            senderUserId = n.senderUserId,
                            tsMs = n.tsMs.toLong(),
                            isDm = n.isDm,
                            kind = n.kind.toKotlin(),
                            expiresAtMs = n.expiresAtMs?.toLong()
                        )
                    }
                }
            }.getOrNull()
        }

    override suspend fun fetchNotificationsSince(
        sinceMs: Long,
        maxRooms: Int,
        maxEvents: Int
    ): List<RenderedNotification> = withContext(matrixDispatcher) {
        runCatching {
            withClient {
                it.fetchNotificationsSince(
                    sinceMs.toULong(),
                    maxRooms.toUInt(),
                    maxEvents.toUInt()
                )
            }
        }.getOrElse { emptyList() }
            .map { n ->
                RenderedNotification(
                    roomId = n.roomId,
                    eventId = n.eventId,
                    roomName = n.roomName,
                    sender = n.sender,
                    body = n.body,
                    isNoisy = n.isNoisy,
                    hasMention = n.hasMention,
                    senderUserId = n.senderUserId,
                    tsMs = n.tsMs.toLong(),
                    isDm = n.isDm,
                    kind = n.kind.toKotlin(),
                    expiresAtMs = n.expiresAtMs?.toLong()
                )
            }
    }

    override suspend fun roomListSetUnreadOnly(token: ULong, unreadOnly: Boolean): Boolean =
        withContext(matrixDispatcher) {
            withClient {
                it.roomListSetUnreadOnly(token, unreadOnly)
            }
        }

    override suspend fun loginSsoLoopback(
        openUrl: (String) -> Boolean,
        deviceName: String?
    ): Result<Unit> = withContext(matrixDispatcher) {
        val opener = object : mages.UrlOpener {
            override fun open(url: String): Boolean = openUrl(url)
        }
        runWithFfiResult { withClient { it.loginSsoLoopback(opener, deviceName) } }
    }

    override suspend fun loginOauthLoopback(
        openUrl: (String) -> Boolean,
        deviceName: String?
    ): Result<Unit> = withContext(matrixDispatcher) {
        val opener = object : mages.UrlOpener {
            override fun open(url: String): Boolean = openUrl(url)
        }
        runWithFfiResult { withClient { it.loginOauthLoopback(opener, deviceName) } }
    }

    override suspend fun homeserverLoginDetails(): HomeserverLoginDetails = withContext(matrixDispatcher) {
        val details = withClient { it.homeserverLoginDetails() }
        HomeserverLoginDetails(
            supportsOauth = details.supportsOauth,
            supportsSso = details.supportsSso,
            supportsPassword = details.supportsPassword,
        )
    }

    override suspend fun searchUsers(term: String, limit: Int): List<DirectoryUser> =
        withContext(matrixDispatcher) {
            runCatching {
                withClient {
                    it.searchUsers(term, limit.toULong()).map { u ->
                        DirectoryUser(u.userId, u.displayName, u.avatarUrl)
                    }
                }
            }.getOrElse { emptyList() }
        }

    override suspend fun getUserProfile(userId: String): DirectoryUser? =
        withContext(matrixDispatcher) {
            runCatching {
                withClient {
                    val profile = it.getUserProfile(userId)
                    DirectoryUser(profile.userId, profile.displayName, profile.avatarUrl)
                }
            }.getOrNull()
        }

    override suspend fun publicRooms(
        server: String?,
        search: String?,
        limit: Int,
        since: String?
    ): PublicRoomsPage = withContext(matrixDispatcher) {
        val resp = withClient {
            it.publicRooms(server, search, limit.toUInt(), since)
        }
        PublicRoomsPage(
            rooms = resp.rooms.map { r ->
                PublicRoom(
                    roomId = r.roomId,
                    name = r.name,
                    topic = r.topic,
                    alias = r.alias,
                    avatarUrl = r.avatarUrl,
                    memberCount = r.memberCount.toLong(),
                    worldReadable = r.worldReadable,
                    guestCanJoin = r.guestCanJoin
                )
            },
            nextBatch = resp.nextBatch,
            prevBatch = resp.prevBatch
        )
    }

    override suspend fun joinByIdOrAlias(idOrAlias: String): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.joinByIdOrAlias(idOrAlias) } }
        }

    override suspend fun roomPreview(idOrAlias: String): Result<RoomPreview> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.roomPreview(idOrAlias).toModel() } }
        }

    override suspend fun knock(idOrAlias: String): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.knock(idOrAlias) } }
        }

    override suspend fun ensureDm(userId: String): String? =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.ensureDm(userId) } }.getOrNull()
        }

    override suspend fun ensureDmIfAllowed(roomId: String, userId: String): String? =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.ensureDmIfAllowed(roomId, userId) } }.getOrNull()
        }

    override suspend fun roomActionState(roomId: String): RoomActionState? =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.roomActionState(roomId) } }.getOrNull()?.toModel()
        }

    override suspend fun memberActionState(roomId: String, userId: String): MemberActionState? =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.memberActionState(roomId, userId) } }.getOrNull()?.toModel()
        }

    override suspend fun messageActionState(roomId: String, eventId: String, senderUserId: String): MessageActionState? =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.messageActionState(roomId, eventId, senderUserId) } }.getOrNull()?.toModel()
        }

    private fun FfiRoomActionState.toModel() = RoomActionState(
        roomId = roomId,
        voiceCall = voiceCall.toModel(),
        videoCall = videoCall.toModel(),
        sendMessage = sendMessage.toModel(),
        sendReaction = sendReaction.toModel(),
        editName = editName.toModel(),
        editTopic = editTopic.toModel(),
        invite = invite.toModel(),
        manageSettings = manageSettings.toModel(),
        redactOthers = redactOthers.toModel(),
        pin = pin.toModel(),
    )

    private fun FfiMemberActionState.toModel() = MemberActionState(
        roomId = roomId,
        userId = userId,
        directMessage = directMessage.toModel(),
        kick = kick.toModel(),
        ban = ban.toModel(),
        unban = unban.toModel(),
    )

    private fun FfiMessageActionState.toModel() = MessageActionState(
        roomId = roomId,
        eventId = eventId,
        edit = edit.toModel(),
        delete = delete.toModel(),
        pin = pin.toModel(),
        unpin = unpin.toModel(),
        react = react.toModel(),
    )

    private fun FfiActionAvailability.toModel() = ActionAvailability(
        presentation = when (presentation) {
            FfiActionPresentation.HIDDEN -> ActionPresentation.Hidden
            FfiActionPresentation.DISABLED -> ActionPresentation.Disabled
            FfiActionPresentation.ENABLED -> ActionPresentation.Enabled
        },
        reason = reason,
    )

    override suspend fun resolveRoomId(idOrAlias: String): String? =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.resolveRoomId(idOrAlias) } }.getOrNull()
        }

    override suspend fun listInvited(): List<RoomProfile> = withContext(matrixDispatcher) {
        runCatching {
            withClient {
                it.listInvited().map { p ->
                    RoomProfile(
                        p.roomId,
                        p.name,
                        p.topic,
                        p.memberCount.toLong(),
                        p.isEncrypted,
                        p.isDm,
                        p.isPublic,
                        p.avatarUrl,
                        p.canonicalAlias,
                        p.altAliases,
                        p.roomVersion
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    override suspend fun acceptInvite(roomId: String): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.acceptInvite(roomId) } }
    }

    override suspend fun leaveRoom(roomId: String): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.leaveRoom(roomId) } }
    }

    override suspend fun createRoom(
        name: String?, topic: String?, invitees: List<String>, isPublic: Boolean, roomAlias: String?
    ): String? = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.createRoom(name, topic, invitees, isPublic, roomAlias) } }
            .onFailure { println("createRoom failed: ${it.message}") }
            .getOrNull()
    }

    override suspend fun setRoomName(roomId: String, name: String): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.setRoomName(roomId, name) } }.map { }
        }

    override suspend fun setRoomTopic(roomId: String, topic: String): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.setRoomTopic(roomId, topic) } }.map { }
        }

    override suspend fun roomProfile(roomId: String): RoomProfile? = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.roomProfile(roomId) } }.getOrNull()?.let {
            RoomProfile(
                it.roomId,
                it.name,
                it.topic,
                it.memberCount.toLong(),
                it.isEncrypted,
                it.isDm,
                it.isPublic,
                it.avatarUrl,
                it.canonicalAlias,
                it.altAliases,
                it.roomVersion,
            )
        }
    }

    override suspend fun listMembers(roomId: String): List<MemberSummary> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.listMembers(roomId) } }
                .getOrElse { emptyList() }.map {
                    MemberSummary(it.userId, it.displayName, it.avatarUrl, it.isMe, it.membership)
                }
        }

    override suspend fun listKnockRequests(roomId: String): List<KnockRequestSummary> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.listKnockRequests(roomId) } }
                .getOrElse { emptyList() }
                .map {
                    KnockRequestSummary(
                        eventId = it.eventId,
                        userId = it.userId,
                        displayName = it.displayName,
                        avatarUrl = it.avatarUrl,
                        reason = it.reason,
                        tsMs = it.tsMs?.toLong(),
                        isSeen = it.isSeen,
                    )
                }
        }

    override suspend fun reactions(roomId: String, eventId: String): List<ReactionSummary> =
        withContext(matrixDispatcher) {
            runCatching {
                withClient {
                    it.reactionsForEvent(roomId, eventId)
                        .map { r -> ReactionSummary(r.key, r.count.toInt(), r.mine) }
                }
            }.getOrElse { emptyList() }
        }

    override suspend fun reactionsBatch(
        roomId: String,
        eventIds: List<String>
    ): Map<String, List<ReactionSummary>> = withContext(matrixDispatcher) {
        runCatching {
            withClient {
                it.reactionsBatch(roomId, eventIds)
                    .mapValues { (_, chips) ->
                        chips.map { c -> ReactionSummary(c.key, c.count.toInt(), c.mine) }
                    }
            }
        }.getOrElse { emptyMap() }
    }

    override suspend fun threadSummary(
        roomId: String,
        rootEventId: String,
        perPage: Int,
        maxPages: Int
    ): ThreadSummary = withContext(matrixDispatcher) {
        val s = withClient {
            it.threadSummary(roomId, rootEventId, perPage.toUInt(), maxPages.toUInt())
        }
        ThreadSummary(s.rootEventId, s.roomId, s.count.toLong(), s.latestTsMs?.toLong())
    }

    override suspend fun threadReplies(
        roomId: String,
        rootEventId: String,
        from: String?,
        limit: Int,
        forward: Boolean
    ): ThreadPage = withContext(matrixDispatcher) {
        val page = withClient { it.threadReplies(roomId, rootEventId, from, limit.toUInt(), forward) }
        ThreadPage(
            rootEventId = page.rootEventId,
            roomId = page.roomId,
            messages = page.messages.map { it.toModel() },
            nextBatch = page.nextBatch,
            prevBatch = page.prevBatch
        )
    }

    override suspend fun sendThreadText(
        roomId: String,
        rootEventId: String,
        body: String,
        replyToEventId: String?,
        latestEventId: String?,
        formattedBody: String?
    ): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.sendThreadText(roomId, rootEventId, body, replyToEventId, latestEventId, formattedBody) } }
    }

    override suspend fun isSpace(roomId: String): Boolean =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.isSpace(roomId) } }.isSuccess
        }

    override suspend fun mySpaces(): List<SpaceInfo> =
        withContext(matrixDispatcher) {
            runCatching {
                withClient {
                    it.mySpaces().map { space ->
                        SpaceInfo(
                            roomId = space.roomId,
                            name = space.name,
                            topic = space.topic,
                            memberCount = space.memberCount.toLong(),
                            isEncrypted = space.isEncrypted,
                            isPublic = space.isPublic,
                            avatarUrl = space.avatarUrl
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

    override suspend fun createSpace(
        name: String,
        topic: String?,
        isPublic: Boolean,
        invitees: List<String>
    ): String? = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.createSpace(name, topic, isPublic, invitees) } }.getOrNull()
    }

    override suspend fun spaceAddChild(
        spaceId: String,
        childRoomId: String,
        order: String?,
        suggested: Boolean?
    ): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.spaceAddChild(spaceId, childRoomId, order, suggested) } }
    }

    override suspend fun spaceRemoveChild(
        spaceId: String,
        childRoomId: String
    ): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.spaceRemoveChild(spaceId, childRoomId) } }
    }

    override suspend fun spaceHierarchy(
        spaceId: String,
        from: String?,
        limit: Int,
        maxDepth: Int?,
        suggestedOnly: Boolean
    ): SpaceHierarchyPage? = withContext(matrixDispatcher) {
        runCatching {
            val page = withClient {
                it.spaceHierarchy(
                    spaceId = spaceId,
                    from = from,
                    limit = limit.toUInt(),
                    maxDepth = maxDepth?.toUInt(),
                    suggestedOnly = suggestedOnly
                )
            }
            SpaceHierarchyPage(
                children = page.children.map { child ->
                    SpaceChildInfo(
                        roomId = child.roomId,
                        name = child.name,
                        topic = child.topic,
                        alias = child.alias,
                        avatarUrl = child.avatarUrl,
                        isSpace = child.isSpace,
                        memberCount = child.memberCount.toLong(),
                        worldReadable = child.worldReadable,
                        guestCanJoin = child.guestCanJoin,
                        suggested = child.suggested
                    )
                },
                nextBatch = page.nextBatch
            )
        }.getOrNull()
    }

    override suspend fun spaceInviteUser(spaceId: String, userId: String): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.spaceInviteUser(spaceId, userId) } }
        }

    override suspend fun roomTags(roomId: String): Pair<Boolean, Boolean>? = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.roomTags(roomId) } }.getOrNull()?.let {
            it.isFavourite to it.isLowPriority
        }
    }

    override suspend fun setRoomFavourite(roomId: String, favourite: Boolean): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.setRoomFavourite(roomId, favourite) } }.map { }
        }

    override suspend fun setRoomLowPriority(roomId: String, lowPriority: Boolean): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.setRoomLowPriority(roomId, lowPriority) } }.map { }
        }

    override suspend fun setPresence(presence: Presence, status: String?): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.setPresence(presence.toFfi(), status) } }.map { }
        }

    override suspend fun getPresence(userId: String): Pair<Presence, String?>? =
        withContext(matrixDispatcher) {
            val info = runWithFfiResult { withClient { it.getPresence(userId) } }.getOrNull()
                ?: return@withContext null
            info.presence.toKotlin() to info.statusMsg
        }

    override suspend fun ignoreUser(userId: String): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.ignoreUser(userId) } }
    }

    override suspend fun unignoreUser(userId: String): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.unignoreUser(userId) } }
    }

    override suspend fun ignoredUsers(): List<String> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.ignoredUsers() } }.getOrElse { emptyList() }
    }

    override suspend fun roomDirectoryVisibility(roomId: String): RoomDirectoryVisibility? =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.roomDirectoryVisibility(roomId) } }.getOrNull()?.toKotlin()
        }

    override suspend fun setRoomDirectoryVisibility(
        roomId: String,
        visibility: RoomDirectoryVisibility
    ): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.setRoomDirectoryVisibility(roomId, visibility.toFfi()) } }
    }

    override suspend fun publishRoomAlias(roomId: String, alias: String): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.publishRoomAlias(roomId, alias) } }.map { }
    }

    override suspend fun unpublishRoomAlias(roomId: String, alias: String): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.unpublishRoomAlias(roomId, alias) } }.map { }
    }

    override suspend fun setRoomCanonicalAlias(roomId: String, alias: String?, altAliases: List<String>): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.setRoomCanonicalAlias(roomId, alias, altAliases) } }
        }

    override suspend fun roomAliases(roomId: String): List<String> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.roomAliases(roomId) } }.getOrDefault(emptyList())
    }

    override suspend fun roomJoinRule(roomId: String): RoomJoinRule? = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.roomJoinRule(roomId) } }.getOrNull()?.toKotlin()
    }

    override suspend fun setRoomJoinRule(roomId: String, rule: RoomJoinRule): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.setRoomJoinRule(roomId, rule.toFfi()) } }
    }

    override suspend fun roomHistoryVisibility(roomId: String): RoomHistoryVisibility? = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.roomHistoryVisibility(roomId) } }.getOrNull()?.toKotlin()
    }

    override suspend fun setRoomHistoryVisibility(roomId: String, visibility: RoomHistoryVisibility): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.setRoomHistoryVisibility(roomId, visibility.toFfi()) } }
    }

    override suspend fun roomPowerLevels(roomId: String): RoomPowerLevels? = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.roomPowerLevels(roomId) } }.getOrNull()?.let {
            RoomPowerLevels(
                users = it.users,
                usersDefault = it.usersDefault,
                events = it.events,
                eventsDefault = it.eventsDefault,
                stateDefault = it.stateDefault,
                ban = it.ban,
                kick = it.kick,
                redact = it.redact,
                invite = it.invite,
                roomName = it.roomName,
                roomAvatar = it.roomAvatar,
                roomTopic = it.roomTopic,
                roomCanonicalAlias = it.roomCanonicalAlias,
                roomHistoryVisibility = it.roomHistoryVisibility,
                roomJoinRules = it.roomJoinRules,
                roomPowerLevels = it.roomPowerLevels,
                spaceChild = it.spaceChild
            )
        }
    }

    override suspend fun canUserBan(roomId: String, userId: String): Boolean = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.canUserBan(roomId, userId) } }.isSuccess
    }

    override suspend fun canUserInvite(roomId: String, userId: String): Boolean = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.canUserInvite(roomId, userId) } }.isSuccess
    }

    override suspend fun canUserRedactOther(roomId: String, userId: String): Boolean = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.canUserRedactOther(roomId, userId) } }.isSuccess
    }

    override suspend fun updatePowerLevelForUser(roomId: String, userId: String, powerLevel: Long): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.updatePowerLevelForUser(roomId, userId, powerLevel) } }
    }

    override suspend fun applyPowerLevelChanges(roomId: String, changes: RoomPowerLevelChanges): Result<Unit> = withContext(matrixDispatcher) {
        runCatching {
            withClient {
                it.applyPowerLevelChanges(
                    roomId,
                    mages.RoomPowerLevelChanges(
                        changes.usersDefault,
                        changes.eventsDefault,
                        changes.stateDefault,
                        changes.ban,
                        changes.kick,
                        changes.redact,
                        changes.invite,
                        changes.roomName,
                        changes.roomAvatar,
                        changes.roomTopic,
                        changes.spaceChild
                    )
                )
            }
        }
    }

    override suspend fun reportRoom(roomId: String, reason: String?): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.reportRoom(roomId, reason) } }
    }

    override suspend fun banUser(roomId: String, userId: String, reason: String?): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.banUser(roomId, userId, reason) } }.map { }
        }

    override suspend fun unbanUser(roomId: String, userId: String, reason: String?): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.unbanUser(roomId, userId, reason) } }.map { }
        }

    override suspend fun kickUser(roomId: String, userId: String, reason: String?): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.kickUser(roomId, userId, reason) } }.map { }
        }

    override suspend fun acceptKnockRequest(roomId: String, userId: String): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.acceptKnockRequest(roomId, userId) } }.map { }
        }

    override suspend fun declineKnockRequest(roomId: String, userId: String, reason: String?): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.declineKnockRequest(roomId, userId, reason) } }.map { }
        }

    override suspend fun inviteUser(roomId: String, userId: String): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.inviteUser(roomId, userId) } }.map { }
        }

    override suspend fun enableRoomEncryption(roomId: String): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.enableRoomEncryption(roomId) } }.map { }
    }

    override suspend fun roomSuccessor(roomId: String): RoomUpgradeInfo? = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.roomSuccessor(roomId) } }.getOrNull()?.let { ffi ->
            RoomUpgradeInfo(roomId = ffi.roomId, reason = ffi.reason)
        }
    }

    override suspend fun roomPredecessor(roomId: String): RoomPredecessorInfo? = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.roomPredecessor(roomId) } }.getOrNull()?.let { ffi ->
            RoomPredecessorInfo(roomId = ffi.roomId)
        }
    }

    override suspend fun startLiveLocationShare(
        roomId: String,
        durationMs: Long
    ): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.startLiveLocation(roomId, durationMs.toULong(), null) } }
    }

    override suspend fun stopLiveLocationShare(roomId: String): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.stopLiveLocation(roomId) } }
        }

    override suspend fun sendLiveLocation(roomId: String, geoUri: String): Result<Unit> =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.sendLiveLocation(roomId, geoUri) } }
        }

    override suspend fun observeLiveLocation(
        roomId: String,
        onShares: (List<LiveLocationShare>) -> Unit
    ): ULong = withContext(matrixDispatcher) {
        val cb = object : mages.LiveLocationObserver {
            override fun onUpdate(shares: List<mages.LiveLocationShareInfo>) {
                val mapped = shares.map {
                    LiveLocationShare(
                        userId = it.userId,
                        geoUri = it.geoUri,
                        tsMs = it.tsMs.toLong(),
                        isLive = it.isLive
                    )
                }
                onShares(mapped)
            }
        }
        withClient { it.observeLiveLocation(roomId, cb) }
    }

    override fun stopObserveLiveLocation(token: ULong) {
        runBlocking(matrixDispatcher) {
            withClient { it.unobserveLiveLocation(token) }
        }
    }

    override suspend fun sendPoll(
        roomId: String,
        question: String,
        answers: List<String>
    ): Result<Unit> = withContext(matrixDispatcher) {
        val def = mages.PollDefinition(
            question = question,
            answers = answers,
            kind = mages.PollKind.DISCLOSED,
            maxSelections = 1u
        )
        runWithFfiResult { withClient { it.sendPollStart(roomId, def) } }.map { }
    }

    override suspend fun sendPollResponse(
        roomId: String,
        pollEventId: String,
        answers: List<String>
    ): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.sendPollResponse(roomId, pollEventId, answers) } }
    }

    override suspend fun sendPollEnd(
        roomId: String,
        pollEventId: String
    ): Result<Unit> = withContext(matrixDispatcher) {
        runWithFfiResult { withClient { it.sendPollEnd(roomId, pollEventId) } }
    }

    override suspend fun seenByForEvent(
        roomId: String,
        eventId: String,
        limit: Int
    ): List<SeenByEntry> = withContext(matrixDispatcher) {
        withClient {
            it.seenByForEvent(roomId, eventId, limit.toUInt()).map { entry ->
                SeenByEntry(
                    userId = entry.userId,
                    displayName = entry.displayName,
                    avatarUrl = entry.avatarUrl,
                    tsMs = entry.tsMs
                )
            }
        }
    }

    override suspend fun mxcThumbnailToCache(
        mxcUri: String,
        width: Int,
        height: Int,
        crop: Boolean
    ): String = withContext(matrixDispatcher) {
        withClient {
            it.mxcThumbnailToCache(mxcUri, width.toUInt(), height.toUInt(), crop)
        }
    }

    override suspend fun loadRoomListCache(): List<RoomListEntry> =
        withContext(matrixDispatcher) {
            withClient { cl ->
                cl.loadRoomListCache().map { it.toKotlinRoomListEntry() }
            }
        }

    override suspend fun searchRoom(
        roomId: String,
        query: String,
        limit: Int,
        offset: Int?
    ): SearchPage = withContext(matrixDispatcher) {
        withClient {
            it.searchRoom(roomId, query, limit.toUInt(), offset?.toUInt()).toKotlin()
        }
    }

    override suspend fun startElementCall(
        roomId: String,
        intent: CallIntent,
        elementCallUrl: String?,
        parentUrl: String?,
        languageTag: String?,
        theme: String?,
        observer: CallWidgetObserver,
    ): CallSession? = withContext(matrixDispatcher) {
        val ffiIntent = when (intent) {
            CallIntent.StartCall -> mages.ElementCallIntent.START_CALL
            CallIntent.JoinExisting -> mages.ElementCallIntent.JOIN_EXISTING
            CallIntent.StartCallVoiceDm -> mages.ElementCallIntent.START_CALL_VOICE_DM
            CallIntent.JoinExistingVoiceDm -> mages.ElementCallIntent.JOIN_EXISTING_VOICE_DM

        }

        val cb = object : mages.CallWidgetObserver {
            override fun onToWidget(message: String) = observer.onToWidget(message)
        }

        runCatching {
            withClient { cl ->
                val info = cl.startElementCall(
                    roomId,
                    elementCallUrl,
                    parentUrl,
                    platformNeedsControlledAudioDevices(),
                    ffiIntent,
                    cb,
                    languageTag,
                    theme
                )
                CallSession(
                    sessionId = info.sessionId,
                    widgetUrl = info.widgetUrl,
                    widgetBaseUrl = info.widgetBaseUrl,
                    parentUrl = info.parentUrl
                )
            }
        }.getOrNull()
    }

    override suspend fun callWidgetFromWebview(sessionId: ULong, message: String): Boolean =
        withContext(matrixDispatcher) {
            runWithFfiResult { withClient { it.callWidgetFromWebview(sessionId, message) } }
                .getOrDefault(false)
        }

    override fun stopElementCall(sessionId: ULong): Boolean {
        return runBlocking(matrixDispatcher) {
            runWithFfiResult { withClient { it.stopElementCall(sessionId) } }.isSuccess
        }
    }

    override suspend fun mediaCacheOverview(): MediaCacheOverview? = withContext(matrixDispatcher) {
        withClient {
            runCatching {
                val overview = it.mediaCacheOverview()
                MediaCacheOverview(totalBytes = overview.totalBytes)
            }.getOrNull()
        }
    }

    override suspend fun clearMediaCache(): Result<Unit> = withContext(matrixDispatcher) {
        withClient {
            runWithFfiResult { it.clearMediaCache() }
        }
    }
}

private fun FfiRoom.toModel() = RoomSummary(id = id, name = name)

private fun mages.RoomPreview.toModel() = RoomPreview(
    roomId = roomId,
    canonicalAlias = canonicalAlias,
    name = name,
    topic = topic,
    avatarUrl = avatarUrl,
    memberCount = memberCount.toLong(),
    worldReadable = worldReadable,
    joinRule = joinRule?.toKotlin(),
    membership = membership?.toKotlin(),
)

private fun mages.MessageEvent.toModel() = MessageEvent(
    itemId = itemId,
    eventId = eventId,
    roomId = roomId,
    sender = sender,
    body = body,
    formattedBody = formattedBody,
    timestampMs = timestampMs.toLong(),
    sendState = sendState?.toKotlin(),
    txnId = txnId,
    replyToEventId = replyToEventId,
    replyToSender = replyToSender,
    replyToBody = replyToBody,
    attachment = attachment?.toModel(),
    sticker = sticker?.toModel(),
    threadRootEventId = threadRootEventId,
    isEdited = isEdited,
    senderAvatarUrl = senderAvatarUrl,
    senderDisplayName = senderDisplayName,
    replyToSenderDisplayName = replyToSenderDisplayName,
    pollData = pollData?.toModel(),
    reactions = reactions.map { ReactionSummary(it.key, it.count.toInt(), it.mine) },
    eventType = eventType.toKotlin(),
    isRedacted = isRedacted,
    stateEventType = stateEventType,
    liveLocation = liveLocation?.toModel(),
    rawJson = rawJson,
)

private fun mages.LiveLocationEvent.toModel() = LiveLocationEvent(
    userId = userId,
    geoUri = geoUri,
    tsMs = tsMs.toLong(),
    isLive = isLive,
)

private fun mages.SendState.toKotlin(): SendState = when (this) {
    mages.SendState.SENDING -> SendState.Sending
    mages.SendState.SENT -> SendState.Sent
    mages.SendState.FAILED -> SendState.Failed
    mages.SendState.ENQUEUED -> SendState.Enqueued
    mages.SendState.RETRYING -> SendState.Retrying
}

private fun mages.EventType.toKotlin(): EventType = when (this) {
    mages.EventType.MESSAGE -> EventType.Message
    mages.EventType.MEMBERSHIP_CHANGE -> EventType.MembershipChange
    mages.EventType.PROFILE_CHANGE -> EventType.ProfileChange
    mages.EventType.ROOM_NAME -> EventType.RoomName
    mages.EventType.ROOM_TOPIC -> EventType.RoomTopic
    mages.EventType.ROOM_AVATAR -> EventType.RoomAvatar
    mages.EventType.ROOM_ENCRYPTION -> EventType.RoomEncryption
    mages.EventType.ROOM_PINNED_EVENTS -> EventType.RoomPinnedEvents
    mages.EventType.ROOM_POWER_LEVELS -> EventType.RoomPowerLevels
    mages.EventType.ROOM_CANONICAL_ALIAS -> EventType.RoomCanonicalAlias
    mages.EventType.OTHER_STATE -> EventType.OtherState
    mages.EventType.CALL_INVITE -> EventType.CallInvite
    mages.EventType.CALL_NOTIFICATION -> EventType.CallNotification
    mages.EventType.POLL -> EventType.Poll
    mages.EventType.STICKER -> EventType.Sticker
    mages.EventType.LIVE_LOCATION -> EventType.LiveLocation
}

private fun mages.EncFile.toModel() = EncFile(url = url, json = json)

private fun mages.AttachmentInfo.toModel() = AttachmentInfo(
    kind = when (kind) {
        mages.AttachmentKind.IMAGE -> AttachmentKind.Image
        mages.AttachmentKind.VIDEO -> AttachmentKind.Video
        mages.AttachmentKind.AUDIO -> AttachmentKind.Audio
        mages.AttachmentKind.FILE -> AttachmentKind.File
    },
    mxcUri = mxcUri,
    mime = mime,
    sizeBytes = sizeBytes?.toLong(),
    width = width?.toInt(),
    height = height?.toInt(),
    durationMs = durationMs?.toLong(),
    thumbnailMxcUri = thumbnailMxcUri,
    encrypted = encrypted?.toModel(),
    thumbnailEncrypted = thumbnailEncrypted?.toModel(),
)

private fun EncFile.toFfi() = mages.EncFile(url = url, json = json)

private fun FfiRoomNotificationMode.toKotlin(): RoomNotificationMode = when (this) {
    FfiRoomNotificationMode.ALL_MESSAGES -> RoomNotificationMode.AllMessages
    FfiRoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY -> RoomNotificationMode.MentionsAndKeywordsOnly
    FfiRoomNotificationMode.MUTE -> RoomNotificationMode.Mute
}

private fun AttachmentInfo.toFfi() = mages.AttachmentInfo(
    kind = when (kind) {
        AttachmentKind.Image -> mages.AttachmentKind.IMAGE
        AttachmentKind.Video -> mages.AttachmentKind.VIDEO
        AttachmentKind.File -> mages.AttachmentKind.FILE
        AttachmentKind.Audio -> mages.AttachmentKind.AUDIO
    },
    mxcUri = mxcUri,
    mime = mime,
    fileName = fileName,
    sizeBytes = sizeBytes?.toULong(),
    width = width?.toUInt(),
    height = height?.toUInt(),
    durationMs = durationMs?.toULong(),
    thumbnailMxcUri = thumbnailMxcUri,
    encrypted = encrypted?.toFfi(),
    thumbnailEncrypted = thumbnailEncrypted?.toFfi(),
)

private fun StickerInfo.toFfi() = mages.StickerInfo(
    mxcUri = mxcUri,
    mime = mime,
    sizeBytes = sizeBytes?.toULong(),
    width = width?.toUInt(),
    height = height?.toUInt(),
    thumbnailMxcUri = thumbnailMxcUri,
    encrypted = encrypted?.toFfi(),
    thumbnailEncrypted = thumbnailEncrypted?.toFfi(),
)

private fun mages.StickerInfo.toModel() = StickerInfo(
    mxcUri = mxcUri,
    mime = mime,
    sizeBytes = sizeBytes?.toLong(),
    width = width?.toInt(),
    height = height?.toInt(),
    thumbnailMxcUri = thumbnailMxcUri,
    encrypted = encrypted?.toModel(),
    thumbnailEncrypted = thumbnailEncrypted?.toModel(),
)

private fun RoomNotificationMode.toFfi(): FfiRoomNotificationMode = when (this) {
    RoomNotificationMode.AllMessages -> FfiRoomNotificationMode.ALL_MESSAGES
    RoomNotificationMode.MentionsAndKeywordsOnly -> FfiRoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY
    RoomNotificationMode.Mute -> FfiRoomNotificationMode.MUTE
}

private fun PushRuleKind.toFfi(): FfiPushRuleKind = when (this) {
    PushRuleKind.Override -> FfiPushRuleKind.OVERRIDE
    PushRuleKind.Underride -> FfiPushRuleKind.UNDERRIDE
    PushRuleKind.Sender -> FfiPushRuleKind.SENDER
    PushRuleKind.Room -> FfiPushRuleKind.ROOM
    PushRuleKind.Content -> FfiPushRuleKind.CONTENT
}

private fun Presence.toFfi(): mages.Presence = when (this) {
    Presence.Online -> mages.Presence.ONLINE
    Presence.Offline -> mages.Presence.OFFLINE
    Presence.Unavailable -> mages.Presence.UNAVAILABLE
}

private fun mages.Presence.toKotlin(): Presence = when (this) {
    mages.Presence.ONLINE -> Presence.Online
    mages.Presence.OFFLINE -> Presence.Offline
    mages.Presence.UNAVAILABLE -> Presence.Unavailable
}

private fun RoomDirectoryVisibility.toFfi(): mages.RoomDirectoryVisibility = when (this) {
    RoomDirectoryVisibility.Public -> mages.RoomDirectoryVisibility.PUBLIC
    RoomDirectoryVisibility.Private -> mages.RoomDirectoryVisibility.PRIVATE
}

private fun mages.RoomDirectoryVisibility.toKotlin(): RoomDirectoryVisibility = when (this) {
    mages.RoomDirectoryVisibility.PUBLIC -> RoomDirectoryVisibility.Public
    mages.RoomDirectoryVisibility.PRIVATE -> RoomDirectoryVisibility.Private
}

private fun RoomJoinRule.toFfi(): mages.RoomJoinRule = when (this) {
    RoomJoinRule.Public -> mages.RoomJoinRule.PUBLIC
    RoomJoinRule.Invite -> mages.RoomJoinRule.INVITE
    RoomJoinRule.Knock -> mages.RoomJoinRule.KNOCK
    RoomJoinRule.Restricted -> mages.RoomJoinRule.RESTRICTED
    RoomJoinRule.KnockRestricted -> mages.RoomJoinRule.KNOCK_RESTRICTED
}

private fun mages.RoomJoinRule.toKotlin(): RoomJoinRule = when (this) {
    mages.RoomJoinRule.PUBLIC -> RoomJoinRule.Public
    mages.RoomJoinRule.INVITE -> RoomJoinRule.Invite
    mages.RoomJoinRule.KNOCK -> RoomJoinRule.Knock
    mages.RoomJoinRule.RESTRICTED -> RoomJoinRule.Restricted
    mages.RoomJoinRule.KNOCK_RESTRICTED -> RoomJoinRule.KnockRestricted
}

private fun mages.RoomPreviewMembership.toKotlin(): RoomPreviewMembership = when (this) {
    mages.RoomPreviewMembership.JOINED -> RoomPreviewMembership.Joined
    mages.RoomPreviewMembership.INVITED -> RoomPreviewMembership.Invited
    mages.RoomPreviewMembership.KNOCKED -> RoomPreviewMembership.Knocked
    mages.RoomPreviewMembership.LEFT -> RoomPreviewMembership.Left
    mages.RoomPreviewMembership.BANNED -> RoomPreviewMembership.Banned
}

private fun RoomHistoryVisibility.toFfi(): mages.RoomHistoryVisibility = when (this) {
    RoomHistoryVisibility.Invited -> mages.RoomHistoryVisibility.INVITED
    RoomHistoryVisibility.Joined -> mages.RoomHistoryVisibility.JOINED
    RoomHistoryVisibility.Shared -> mages.RoomHistoryVisibility.SHARED
    RoomHistoryVisibility.WorldReadable -> mages.RoomHistoryVisibility.WORLD_READABLE
}

private fun mages.RoomHistoryVisibility.toKotlin(): RoomHistoryVisibility = when (this) {
    mages.RoomHistoryVisibility.INVITED -> RoomHistoryVisibility.Invited
    mages.RoomHistoryVisibility.JOINED -> RoomHistoryVisibility.Joined
    mages.RoomHistoryVisibility.SHARED -> RoomHistoryVisibility.Shared
    mages.RoomHistoryVisibility.WORLD_READABLE -> RoomHistoryVisibility.WorldReadable
}

private fun mages.NotificationKind.toKotlin(): NotificationKind = when (this) {
    mages.NotificationKind.MESSAGE -> NotificationKind.Message
    mages.NotificationKind.REACTION -> NotificationKind.Reaction
    mages.NotificationKind.CALL_RING -> NotificationKind.CallRing
    mages.NotificationKind.CALL_NOTIFY -> NotificationKind.CallNotify
    mages.NotificationKind.CALL_INVITE -> NotificationKind.CallInvite
    mages.NotificationKind.INVITE -> NotificationKind.Invite
    mages.NotificationKind.STATE_EVENT -> NotificationKind.StateEvent
}

private fun mages.SearchPage.toKotlin(): SearchPage =
    SearchPage(
        hits = hits.map { it.toKotlin() },
        nextOffset = nextOffset
    )

private fun mages.SearchHit.toKotlin(): SearchHit =
    SearchHit(
        roomId = roomId,
        eventId = eventId,
        sender = sender,
        body = body,
        timestampMs = timestampMs
    )

//private fun mages.DownloadResult.toKotlin(): DownloadResult =
//    DownloadResult(
//        path,
//        bytes
//    )

private fun mages.RoomListMembership.toKotlin(): RoomListMembership = when (this) {
    mages.RoomListMembership.JOINED -> RoomListMembership.Joined
    mages.RoomListMembership.INVITED -> RoomListMembership.Invited
    mages.RoomListMembership.LEFT -> RoomListMembership.Left
    mages.RoomListMembership.KNOCKED -> RoomListMembership.Knocked
    mages.RoomListMembership.BANNED -> RoomListMembership.Banned
}

private fun RoomListEntry.isVisibleInMainRoomList(): Boolean =
    membership == RoomListMembership.Joined || membership == RoomListMembership.Invited

private fun mages.RoomListEntry.toKotlinRoomListEntry(): RoomListEntry =
    RoomListEntry(
        roomId = roomId,
        name = name,
        lastTs = lastTs,
        notifications = notifications,
        messages = messages,
        mentions = mentions,
        markedUnread = markedUnread,
        isFavourite = isFavourite,
        isLowPriority = isLowPriority,
        isInvited = isInvited,
        membership = membership.toKotlin(),
        avatarUrl = avatarUrl,
        isDm = isDm,
        isEncrypted = isEncrypted,
        memberCount = memberCount.toInt(),
        topic = topic,
        latestEvent = latestEvent?.let { e ->
            LatestRoomEvent(
                eventId = e.eventId,
                sender = e.sender,
                body = e.body,
                msgtype = e.msgtype,
                eventType = e.eventType,
                timestamp = e.timestamp,
                isRedacted = e.isRedacted,
                isEncrypted = e.isEncrypted
            )
        }
    )

private fun mages.PollData.toModel(): PollData {
    val mappedOptions = options.map { it.toModel() }
    val voteMap = options.associate { it.id to it.votes.toInt() }
    val mySelections = options.filter { it.isSelected }.map { it.id }

    return PollData(
        question = question,
        kind = if (kind == mages.PollKind.DISCLOSED) PollKind.Disclosed else PollKind.Undisclosed,
        maxSelections = maxSelections.toLong(),
        options = mappedOptions, //TODO: Use the new sent counts for all 3
        votes = voteMap,
        totalVotes = totalVotes.toLong(),
        mySelections = mySelections,
        isEnded = isEnded
    )
}

private fun mages.PollOption.toModel() = PollOption(
    id = id,
    text = text,
    isWinner = isWinner,
    votes = votes.toLong(),
    isSelected = isSelected
)

actual fun createMatrixPort(): MatrixPort = RustMatrixPort()
