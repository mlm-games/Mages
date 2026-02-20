package org.mlm.mages.matrix

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mages.FfiRoomNotificationMode
import mages.SasEmojis
import mages.TimelineDiffKind
import mages.Client as FfiClient
import mages.RoomSummary as FfiRoom
import org.mlm.mages.*
import org.mlm.mages.platform.MagesPaths

class RustMatrixPort : MatrixPort {
    @Volatile
    private var client: FfiClient? = null
    private val clientLock = Any()
    private var currentHs: String? = null
    private var currentAccountId: String? = null

    override suspend fun init(hs: String, accountId: String?) =
        withContext(Dispatchers.IO) {
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
        runBlocking(Dispatchers.IO) {
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
        withContext(Dispatchers.IO) {
            withClient {
                it.login(user, password, deviceDisplayName)
            }
        }

    override fun isLoggedIn(): Boolean =
        runBlocking(Dispatchers.IO) {
            synchronized(clientLock) { client?.isLoggedIn() ?: false }
        }

    override suspend fun listRooms(): List<RoomSummary> =
        withContext(Dispatchers.IO) {
            withClient { it.rooms().map { r -> r.toModel() } }
        }

    override suspend fun recent(roomId: String, limit: Int): List<MessageEvent> =
        withContext(Dispatchers.IO) {
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

        val token = withContext(Dispatchers.IO) {
            withClient { it.observeTimeline(roomId, obs) }
        }
        awaitClose {
            runBlocking(Dispatchers.IO) {
                withClient { it.unobserveTimeline(token) }
            }
        }
    }

    override fun observeConnection(observer: MatrixPort.ConnectionObserver): ULong {
        return runBlocking(Dispatchers.IO) {
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
    }

    override fun stopConnectionObserver(token: ULong) {
        runBlocking(Dispatchers.IO) {
            withClient { it.unobserveConnection(token) }
        }
    }

    override fun startVerificationInbox(observer: MatrixPort.VerificationInboxObserver): ULong {
        return runBlocking(Dispatchers.IO) {
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
    }

    override fun stopVerificationInbox(token: ULong) {
        runBlocking(Dispatchers.IO) {
            withClient { it.unobserveVerificationInbox(token) }
        }
    }

    override suspend fun send(roomId: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.sendMessage(roomId, body) } }.getOrDefault(false)
        }

    override suspend fun sendQueueSetEnabled(enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.sendQueueSetEnabled(enabled) } }.getOrDefault(false)
        }

    override suspend fun roomSendQueueSetEnabled(roomId: String, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.roomSendQueueSetEnabled(roomId, enabled) } }.getOrDefault(false)
        }

    override suspend fun sendExistingAttachment(
        roomId: String,
        attachment: AttachmentInfo,
        body: String?,
        onProgress: ((Long, Long?) -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        val cb = if (onProgress != null) object : mages.ProgressObserver {
            override fun onProgress(sent: ULong, total: ULong?) {
                onProgress(sent.toLong(), total?.toLong())
            }
        } else null

        runCatching { withClient { it.sendExistingAttachment(roomId, attachment.toFfi(), body, cb) } }.getOrDefault(false)
    }

    override suspend fun enqueueText(roomId: String, body: String, txnId: String?): String =
        withContext(Dispatchers.IO) {
            withClient { it.enqueueText(roomId, body, txnId) }
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
        val token = withContext(Dispatchers.IO) {
            withClient { it.observeSends(obs) }
        }
        awaitClose {
            runBlocking(Dispatchers.IO) {
                withClient { it.unobserveSends(token) }
            }
        }
    }

    override suspend fun thumbnailToCache(
        info: AttachmentInfo, width: Int, height: Int, crop: Boolean
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.thumbnailToCache(info.toFfi(), width.toUInt(), height.toUInt(), crop) } }
        }

    override suspend fun setTyping(roomId: String, typing: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.setTyping(roomId, typing) } }.getOrDefault(false)
        }

    override fun whoami(): String? {
        return runBlocking(Dispatchers.IO) {
            withClient { it.whoami() }
        }
    }

    override suspend fun paginateBack(roomId: String, count: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                withClient { it.paginateBackwards(roomId, count.toUShort()) }
            }.getOrDefault(false)
        }

    override suspend fun paginateForward(roomId: String, count: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                withClient { it.paginateForwards(roomId, count.toUShort()) }
            }.getOrDefault(false)
        }

    override suspend fun markRead(roomId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.markRead(roomId) } }.getOrDefault(false)
        }

    override suspend fun markReadAt(roomId: String, eventId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.markReadAt(roomId, eventId) } }.getOrDefault(false)
        }

    override suspend fun react(roomId: String, eventId: String, emoji: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.react(roomId, eventId, emoji) } }.getOrDefault(false)
        }

    override suspend fun reply(roomId: String, inReplyToEventId: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.reply(roomId, inReplyToEventId, body) } }.getOrDefault(false)
        }

    override suspend fun edit(roomId: String, targetEventId: String, newBody: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.edit(roomId, targetEventId, newBody) } }.getOrDefault(false)
        }

    override suspend fun redact(roomId: String, eventId: String, reason: String?): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.redact(roomId, eventId, reason) } }.getOrDefault(false)
        }

    override suspend fun reportContent(roomId: String, eventId: String, score: Int?, reason: String?): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.reportContent(roomId, eventId, score, reason) } }.isSuccess
        }

    override suspend fun getUserPowerLevel(roomId: String, userId: String): Long =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.getUserPowerLevel(roomId, userId).toLong() } }.getOrDefault(-1L)
        }

    override suspend fun getPinnedEvents(roomId: String): List<String> =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.getPinnedEvents(roomId) } }.getOrDefault(emptyList())
        }

    override suspend fun setPinnedEvents(roomId: String, eventIds: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.setPinnedEvents(roomId, eventIds) } }.getOrDefault(false)
        }

    override fun observeTyping(roomId: String, onUpdate: (List<String>) -> Unit): ULong {
        return runBlocking(Dispatchers.IO) {
            val obs = object : mages.TypingObserver {
                override fun onUpdate(names: List<String>) {
                    onUpdate(names)
                }
            }
            withClient { it.observeTyping(roomId, obs) }
        }
    }

    override fun stopTypingObserver(token: ULong) {
        runBlocking(Dispatchers.IO) {
            withClient { it.unobserveTyping(token) }
        }
    }

    override fun observeReceipts(roomId: String, observer: ReceiptsObserver): ULong {
        return runBlocking(Dispatchers.IO) {
            val cb = object : mages.ReceiptsObserver {
                override fun onChanged() {
                    observer.onChanged()
                }
            }
            withClient { it.observeReceipts(roomId, cb) }
        }
    }

    override fun stopReceiptsObserver(token: ULong) {
        runBlocking(Dispatchers.IO) {
            withClient { it.unobserveReceipts(token) }
        }
    }

    override suspend fun dmPeerUserId(roomId: String): String? =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.dmPeerUserId(roomId) } }.getOrNull()
        }

    override suspend fun isEventReadBy(roomId: String, eventId: String, userId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.isEventReadBy(roomId, eventId, userId) } }.getOrDefault(false)
        }

    override fun startCallInbox(observer: MatrixPort.CallObserver): ULong {
        return runBlocking(Dispatchers.IO) {
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
    }

    override fun stopCallInbox(token: ULong) {
        runBlocking(Dispatchers.IO) {
            withClient { it.stopCallInbox(token) }
        }
    }

    override fun startSupervisedSync(observer: MatrixPort.SyncObserver) {
        runBlocking(Dispatchers.IO) {
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
    }

    override suspend fun listMyDevices(): List<DeviceSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                withClient {
                    it.listMyDevices().map { d ->
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

    private fun mages.SasPhase.toCommon(): SasPhase = when (this) {
        mages.SasPhase.CREATED -> SasPhase.Created
        mages.SasPhase.REQUESTED -> SasPhase.Requested
        mages.SasPhase.READY -> SasPhase.Ready
        mages.SasPhase.ACCEPTED -> SasPhase.Accepted
        mages.SasPhase.STARTED -> SasPhase.Started
        mages.SasPhase.EMOJIS -> SasPhase.Emojis
        mages.SasPhase.CONFIRMED -> SasPhase.Confirmed
        mages.SasPhase.CANCELLED -> SasPhase.Cancelled
        mages.SasPhase.FAILED -> SasPhase.Failed
        mages.SasPhase.DONE -> SasPhase.Done
    }

    override suspend fun startSelfSas(
        targetDeviceId: String,
        observer: VerificationObserver
    ): String = withContext(Dispatchers.IO) {
        val obs = object : mages.VerificationObserver {
            override fun onPhase(flowId: String, phase: mages.SasPhase) {
                observer.onPhase(flowId, phase.toCommon())
            }

            override fun onEmojis(payload: SasEmojis) {
                observer.onEmojis(
                    payload.flowId,
                    payload.otherUser,
                    payload.otherDevice,
                    payload.emojis
                )
            }

            override fun onError(flowId: String, message: String) {
                observer.onError(flowId, message)
            }
        }
        withClient { it.startSelfSas(targetDeviceId, obs) }
    }

    override suspend fun startUserSas(
        userId: String,
        observer: VerificationObserver
    ): String = withContext(Dispatchers.IO) {
        val obs = object : mages.VerificationObserver {
            override fun onPhase(flowId: String, phase: mages.SasPhase) {
                observer.onPhase(flowId, phase.toCommon())
            }

            override fun onEmojis(payload: SasEmojis) {
                observer.onEmojis(
                    payload.flowId,
                    payload.otherUser,
                    payload.otherDevice,
                    payload.emojis
                )
            }

            override fun onError(flowId: String, message: String) {
                observer.onError(flowId, message)
            }
        }
        withClient { it.startUserSas(userId, obs) }
    }

    override suspend fun acceptVerificationRequest(
        flowId: String,
        otherUserId: String?,
        observer: VerificationObserver
    ): Boolean = withContext(Dispatchers.IO) {
        val cb = object : mages.VerificationObserver {
            override fun onPhase(flowId: String, phase: mages.SasPhase) =
                observer.onPhase(flowId, phase.toCommon())

            override fun onEmojis(payload: SasEmojis) =
                observer.onEmojis(payload.flowId, payload.otherUser, payload.otherDevice, payload.emojis)

            override fun onError(flowId: String, message: String) =
                observer.onError(flowId, message)
        }

        runCatching { withClient { it.acceptVerificationRequest(flowId, otherUserId, cb) } }
            .getOrDefault(false)
    }

    override suspend fun acceptSas(
        flowId: String,
        otherUserId: String?,
        observer: VerificationObserver
    ): Boolean = withContext(Dispatchers.IO) {
        val cb = object : mages.VerificationObserver {
            override fun onPhase(flowId: String, phase: mages.SasPhase) =
                observer.onPhase(flowId, phase.toCommon())

            override fun onEmojis(payload: SasEmojis) =
                observer.onEmojis(payload.flowId, payload.otherUser, payload.otherDevice, payload.emojis)

            override fun onError(flowId: String, message: String) =
                observer.onError(flowId, message)
        }

        runCatching { withClient { it.acceptSas(flowId, otherUserId, cb) } }
            .getOrDefault(false)
    }

    override suspend fun confirmVerification(flowId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.confirmVerification(flowId) } }.getOrDefault(false)
        }

    override suspend fun cancelVerification(flowId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.cancelVerification(flowId) } }.getOrDefault(false)
        }

    override suspend fun cancelVerificationRequest(flowId: String, otherUserId: String?): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.cancelVerificationRequest(flowId, otherUserId) } }.getOrDefault(false)
        }

    override suspend fun logout(): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.logout() } }.getOrDefault(false)
        }

    override suspend fun retryByTxn(roomId: String, txnId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.retryByTxn(roomId, txnId) } }.getOrDefault(false)
        }

    override suspend fun checkVerificationRequest(userId: String, flowId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.checkVerificationRequest(userId, flowId) } }.getOrDefault(false)
        }

    override fun enterForeground() {
        runBlocking(Dispatchers.IO) {
            withClient { it.enterForeground() }
        }
    }

    override fun enterBackground() {
        runBlocking(Dispatchers.IO) {
            withClient { it.enterBackground() }
        }
    }

    override suspend fun sendAttachmentFromPath(
        roomId: String,
        path: String,
        mime: String,
        filename: String?,
        onProgress: ((Long, Long?) -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        val cb = if (onProgress != null) object : mages.ProgressObserver {
            override fun onProgress(sent: ULong, total: ULong?) {
                onProgress(sent.toLong(), total?.toLong())
            }
        } else null
        runCatching { withClient { it.sendAttachmentFromPath(roomId, path, mime, filename, cb) } }.getOrDefault(false)
    }

    override suspend fun sendAttachmentBytes(
        roomId: String,
        data: ByteArray,
        mime: String,
        filename: String,
        onProgress: ((Long, Long?) -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        val cb = if (onProgress != null) object : mages.ProgressObserver {
            override fun onProgress(sent: ULong, total: ULong?) {
                onProgress(sent.toLong(), total?.toLong())
            }
        } else null
        runCatching { withClient { it.sendAttachmentBytes(roomId, filename, mime, data, cb) } }.getOrDefault(false)
    }

    override suspend fun downloadAttachmentToCache(
        info: AttachmentInfo,
        filenameHint: String?
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.downloadAttachmentToCacheFile(info.toFfi(), filenameHint).path } }
        }

    override suspend fun downloadAttachmentToPath(
        info: AttachmentInfo,
        savePath: String,
        onProgress: ((Long, Long?) -> Unit)?
    ): Result<String> = withContext(Dispatchers.IO) {
        val cb = if (onProgress != null) object : mages.ProgressObserver {
            override fun onProgress(sent: ULong, total: ULong?) {
                onProgress(sent.toLong(), total?.toLong())
            }
        } else null

        runCatching { withClient { it.downloadAttachmentToPath(info.toFfi(), savePath, cb).path } }
    }

    override suspend fun recoverWithKey(recoveryKey: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.recoverWithKey(recoveryKey) } }.getOrDefault(false)
        }

    override suspend fun registerUnifiedPush(
        appId: String,
        pushKey: String,
        gatewayUrl: String,
        deviceName: String,
        lang: String,
        profileTag: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            withClient { it.registerUnifiedpush(appId, pushKey, gatewayUrl, deviceName, lang, profileTag) }
        }.getOrDefault(false)
    }

    override suspend fun unregisterUnifiedPush(appId: String, pushKey: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.unregisterUnifiedpush(appId, pushKey) } }.getOrDefault(false)
        }

    override suspend fun roomUnreadStats(roomId: String): UnreadStats? =
        withContext(Dispatchers.IO) {
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
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.roomNotificationMode(roomId)?.toKotlin() } }.getOrNull()
        }

    override suspend fun setRoomNotificationMode(
        roomId: String,
        mode: RoomNotificationMode
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.setRoomNotificationMode(roomId, mode.toFfi()) } }.isSuccess
    }

    override suspend fun encryptionCatchupOnce(): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.encryptionCatchupOnce() } }.getOrDefault(false)
        }

    override suspend fun ownLastRead(roomId: String): Pair<String?, Long?> =
        withContext(Dispatchers.IO) {
            runCatching {
                withClient { it.ownLastRead(roomId).let { r -> r.eventId to r.tsMs?.toLong() } }
            }.getOrElse { null to null }
        }

    override fun observeOwnReceipt(roomId: String, observer: ReceiptsObserver): ULong {
        return runBlocking(Dispatchers.IO) {
            val cb = object : mages.ReceiptsObserver {
                override fun onChanged() {
                    observer.onChanged()
                }
            }
            withClient { it.observeOwnReceipt(roomId, cb) }
        }
    }

    override suspend fun markFullyReadAt(roomId: String, eventId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.markFullyReadAt(roomId, eventId) } }.getOrDefault(false)
        }

    override fun observeRoomList(observer: MatrixPort.RoomListObserver): ULong {
        return runBlocking(Dispatchers.IO) {
            val cb = object : mages.RoomListObserver {
                override fun onReset(items: List<mages.RoomListEntry>) {
                    val mapped = items.map { it.toKotlinRoomListEntry() }
                    observer.onReset(mapped)
                }

                override fun onUpdate(item: mages.RoomListEntry) {
                    observer.onUpdate(item.toKotlinRoomListEntry())
                }
            }
            withClient { it.observeRoomList(cb) }
        }
    }

    override fun unobserveRoomList(token: ULong) {
        runBlocking(Dispatchers.IO) {
            withClient { it.unobserveRoomList(token) }
        }
    }

    override suspend fun fetchNotification(roomId: String, eventId: String): RenderedNotification? =
        withContext(Dispatchers.IO) {
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
    ): List<RenderedNotification> = withContext(Dispatchers.IO) {
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

    override fun roomListSetUnreadOnly(token: ULong, unreadOnly: Boolean): Boolean {
        return runBlocking(Dispatchers.IO) {
            withClient {
                it.roomListSetUnreadOnly(token, unreadOnly)
            }
        }
    }

    override suspend fun loginSsoLoopback(
        openUrl: (String) -> Boolean,
        deviceName: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val opener = object : mages.UrlOpener {
            override fun open(url: String): Boolean = openUrl(url)
        }
        runCatching { withClient { it.loginSsoLoopback(opener, deviceName) } }.isSuccess
    }

    override suspend fun searchUsers(term: String, limit: Int): List<DirectoryUser> =
        withContext(Dispatchers.IO) {
            runCatching {
                withClient {
                    it.searchUsers(term, limit.toULong()).map { u ->
                        DirectoryUser(u.userId, u.displayName, u.avatarUrl)
                    }
                }
            }.getOrElse { emptyList() }
        }

    override suspend fun publicRooms(
        server: String?,
        search: String?,
        limit: Int,
        since: String?
    ): PublicRoomsPage = withContext(Dispatchers.IO) {
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

    override suspend fun joinByIdOrAlias(idOrAlias: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.joinByIdOrAlias(idOrAlias) } }.getOrDefault(false)
        }

    override suspend fun ensureDm(userId: String): String? =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.ensureDm(userId) } }.getOrNull()
        }

    override suspend fun resolveRoomId(idOrAlias: String): String? =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.resolveRoomId(idOrAlias) } }.getOrNull()
        }

    override suspend fun listInvited(): List<RoomProfile> = withContext(Dispatchers.IO) {
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
                        p.avatarUrl,
                        p.canonicalAlias,
                        p.altAliases,
                        p.roomVersion
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    override suspend fun acceptInvite(roomId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.acceptInvite(roomId) } }.getOrDefault(false)
    }

    override suspend fun leaveRoom(roomId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.leaveRoom(roomId) } }.isSuccess
    }

    override suspend fun createRoom(
        name: String?, topic: String?, invitees: List<String>, isPublic: Boolean, roomAlias: String?
    ): String? = withContext(Dispatchers.IO) {
        runCatching { withClient { it.createRoom(name, topic, invitees, isPublic, roomAlias) } }
            .onFailure { println("createRoom failed: ${it.message}") }
            .getOrNull()
    }

    override suspend fun setRoomName(roomId: String, name: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.setRoomName(roomId, name) } }.getOrDefault(false)
        }

    override suspend fun setRoomTopic(roomId: String, topic: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.setRoomTopic(roomId, topic) } }.getOrDefault(false)
        }

    override suspend fun roomProfile(roomId: String): RoomProfile? = withContext(Dispatchers.IO) {
        runCatching { withClient { it.roomProfile(roomId) } }.getOrNull()?.let {
            RoomProfile(
                it.roomId,
                it.name,
                it.topic,
                it.memberCount.toLong(),
                it.isEncrypted,
                it.isDm,
                it.avatarUrl,
                it.canonicalAlias,
                it.altAliases,
                it.roomVersion,
            )
        }
    }

    override suspend fun listMembers(roomId: String): List<MemberSummary> =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.listMembers(roomId) } }
                .getOrElse { emptyList() }.map {
                    MemberSummary(it.userId, it.displayName, it.avatarUrl, it.isMe, it.membership)
                }
        }

    override suspend fun reactions(roomId: String, eventId: String): List<ReactionChip> =
        withContext(Dispatchers.IO) {
            runCatching {
                withClient {
                    it.reactionsForEvent(roomId, eventId)
                        .map { r -> ReactionChip(r.key, r.count.toInt(), r.me) }
                }
            }.getOrElse { emptyList() }
        }

    override suspend fun reactionsBatch(
        roomId: String,
        eventIds: List<String>
    ): Map<String, List<ReactionChip>> = withContext(Dispatchers.IO) {
        runCatching {
            withClient {
                it.reactionsBatch(roomId, eventIds)
                    .mapValues { (_, chips) ->
                        chips.map { c -> ReactionChip(c.key, c.count.toInt(), c.me) }
                    }
            }
        }.getOrElse { emptyMap() }
    }

    override suspend fun threadSummary(
        roomId: String,
        rootEventId: String,
        perPage: Int,
        maxPages: Int
    ): ThreadSummary = withContext(Dispatchers.IO) {
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
    ): ThreadPage = withContext(Dispatchers.IO) {
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
        replyToEventId: String?
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.sendThreadText(roomId, rootEventId, body, replyToEventId) } }.getOrDefault(false)
    }

    override suspend fun isSpace(roomId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.isSpace(roomId) } }.getOrDefault(false)
        }

    override suspend fun mySpaces(): List<SpaceInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                withClient {
                    it.mySpaces().map { space ->
                        SpaceInfo(
                            roomId = space.roomId,
                            name = space.name,
                            topic = space.topic,
                            memberCount = space.memberCount.toLong(),
                            isEncrypted = space.isEncrypted,
                            isPublic = space.isPublic
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
    ): String? = withContext(Dispatchers.IO) {
        runCatching { withClient { it.createSpace(name, topic, isPublic, invitees) } }.getOrNull()
    }

    override suspend fun spaceAddChild(
        spaceId: String,
        childRoomId: String,
        order: String?,
        suggested: Boolean?
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.spaceAddChild(spaceId, childRoomId, order, suggested) } }.isSuccess
    }

    override suspend fun spaceRemoveChild(
        spaceId: String,
        childRoomId: String
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.spaceRemoveChild(spaceId, childRoomId) } }.isSuccess
    }

    override suspend fun spaceHierarchy(
        spaceId: String,
        from: String?,
        limit: Int,
        maxDepth: Int?,
        suggestedOnly: Boolean
    ): SpaceHierarchyPage? = withContext(Dispatchers.IO) {
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

    override suspend fun spaceInviteUser(spaceId: String, userId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.spaceInviteUser(spaceId, userId) } }.getOrDefault(false)
        }

    override suspend fun roomTags(roomId: String): Pair<Boolean, Boolean>? = withContext(Dispatchers.IO) {
        runCatching { withClient { it.roomTags(roomId) } }.getOrNull()?.let {
            it.isFavourite to it.isLowPriority
        }
    }

    override suspend fun setRoomFavourite(roomId: String, favourite: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.setRoomFavourite(roomId, favourite) } }.getOrDefault(false)
        }

    override suspend fun setRoomLowPriority(roomId: String, lowPriority: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.setRoomLowPriority(roomId, lowPriority) } }.getOrDefault(false)
        }

    override suspend fun setPresence(presence: Presence, status: String?): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.setPresence(presence.toFfi(), status) } }.isSuccess
        }

    override suspend fun getPresence(userId: String): Pair<Presence, String?>? =
        withContext(Dispatchers.IO) {
            val info = runCatching { withClient { it.getPresence(userId) } }.getOrNull()
                ?: return@withContext null
            info.presence.toKotlin() to info.statusMsg
        }

    override suspend fun ignoreUser(userId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.ignoreUser(userId) } }.isSuccess
    }

    override suspend fun unignoreUser(userId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.unignoreUser(userId) } }.isSuccess
    }

    override suspend fun ignoredUsers(): List<String> = withContext(Dispatchers.IO) {
        runCatching { withClient { it.ignoredUsers() } }.getOrElse { emptyList() }
    }

    override suspend fun roomDirectoryVisibility(roomId: String): RoomDirectoryVisibility? =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.roomDirectoryVisibility(roomId) } }.getOrNull()?.toKotlin()
        }

    override suspend fun setRoomDirectoryVisibility(
        roomId: String,
        visibility: RoomDirectoryVisibility
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.setRoomDirectoryVisibility(roomId, visibility.toFfi()) } }.isSuccess
    }

    override suspend fun publishRoomAlias(roomId: String, alias: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.publishRoomAlias(roomId, alias) } }.getOrDefault(false)
    }

    override suspend fun unpublishRoomAlias(roomId: String, alias: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.unpublishRoomAlias(roomId, alias) } }.getOrDefault(false)
    }

    override suspend fun setRoomCanonicalAlias(roomId: String, alias: String?, altAliases: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.setRoomCanonicalAlias(roomId, alias, altAliases) } }.isSuccess
        }

    override suspend fun roomAliases(roomId: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { withClient { it.roomAliases(roomId) } }.getOrDefault(emptyList())
    }

    override suspend fun roomJoinRule(roomId: String): RoomJoinRule? = withContext(Dispatchers.IO) {
        runCatching { withClient { it.roomJoinRule(roomId) } }.getOrNull()?.toKotlin()
    }

    override suspend fun setRoomJoinRule(roomId: String, rule: RoomJoinRule): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.setRoomJoinRule(roomId, rule.toFfi()) } }.isSuccess
    }

    override suspend fun roomHistoryVisibility(roomId: String): RoomHistoryVisibility? = withContext(Dispatchers.IO) {
        runCatching { withClient { it.roomHistoryVisibility(roomId) } }.getOrNull()?.toKotlin()
    }

    override suspend fun setRoomHistoryVisibility(roomId: String, visibility: RoomHistoryVisibility): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.setRoomHistoryVisibility(roomId, visibility.toFfi()) } }.isSuccess
    }

    override suspend fun roomPowerLevels(roomId: String): RoomPowerLevels? = withContext(Dispatchers.IO) {
        runCatching { withClient { it.roomPowerLevels(roomId) } }.getOrNull()?.let {
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

    override suspend fun canUserBan(roomId: String, userId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.canUserBan(roomId, userId) } }.getOrDefault(false)
    }

    override suspend fun canUserInvite(roomId: String, userId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.canUserInvite(roomId, userId) } }.getOrDefault(false)
    }

    override suspend fun canUserRedactOther(roomId: String, userId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.canUserRedactOther(roomId, userId) } }.getOrDefault(false)
    }

    override suspend fun updatePowerLevelForUser(roomId: String, userId: String, powerLevel: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.updatePowerLevelForUser(roomId, userId, powerLevel) } }.isSuccess
    }

    override suspend fun applyPowerLevelChanges(roomId: String, changes: RoomPowerLevelChanges): Boolean = withContext(Dispatchers.IO) {
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
        }.isSuccess
    }

    override suspend fun reportRoom(roomId: String, reason: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.reportRoom(roomId, reason) } }.isSuccess
    }

    override suspend fun banUser(roomId: String, userId: String, reason: String?): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.banUser(roomId, userId, reason) } }.getOrDefault(false)
        }

    override suspend fun unbanUser(roomId: String, userId: String, reason: String?): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.unbanUser(roomId, userId, reason) } }.getOrDefault(false)
        }

    override suspend fun kickUser(roomId: String, userId: String, reason: String?): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.kickUser(roomId, userId, reason) } }.getOrDefault(false)
        }

    override suspend fun inviteUser(roomId: String, userId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.inviteUser(roomId, userId) } }.getOrDefault(false)
        }

    override suspend fun enableRoomEncryption(roomId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.enableRoomEncryption(roomId) } }.getOrDefault(false)
    }

    override suspend fun roomSuccessor(roomId: String): RoomUpgradeInfo? = withContext(Dispatchers.IO) {
        runCatching { withClient { it.roomSuccessor(roomId) } }.getOrNull()?.let { ffi ->
            RoomUpgradeInfo(roomId = ffi.roomId, reason = ffi.reason)
        }
    }

    override suspend fun roomPredecessor(roomId: String): RoomPredecessorInfo? = withContext(Dispatchers.IO) {
        runCatching { withClient { it.roomPredecessor(roomId) } }.getOrNull()?.let { ffi ->
            RoomPredecessorInfo(roomId = ffi.roomId)
        }
    }

    override suspend fun startLiveLocationShare(
        roomId: String,
        durationMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.startLiveLocation(roomId, durationMs.toULong(), null) } }.isSuccess
    }

    override suspend fun stopLiveLocationShare(roomId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.stopLiveLocation(roomId) } }.isSuccess
        }

    override suspend fun sendLiveLocation(roomId: String, geoUri: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { withClient { it.sendLiveLocation(roomId, geoUri) } }.isSuccess
        }

    override fun observeLiveLocation(
        roomId: String,
        onShares: (List<LiveLocationShare>) -> Unit
    ): ULong {
        return runBlocking(Dispatchers.IO) {
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
    }

    override fun stopObserveLiveLocation(token: ULong) {
        runBlocking(Dispatchers.IO) {
            withClient { it.unobserveLiveLocation(token) }
        }
    }

    override suspend fun sendPoll(
        roomId: String,
        question: String,
        answers: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        val def = mages.PollDefinition(
            question = question,
            answers = answers,
            kind = mages.PollKind.DISCLOSED,
            maxSelections = 1u
        )
        runCatching { withClient { it.sendPollStart(roomId, def) } }.isSuccess
    }

    override suspend fun sendPollResponse(
        roomId: String,
        pollEventId: String,
        answers: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.sendPollResponse(roomId, pollEventId, answers) } }.isSuccess
    }

    override suspend fun sendPollEnd(
        roomId: String,
        pollEventId: String
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching { withClient { it.sendPollEnd(roomId, pollEventId) } }.isSuccess
    }

    override fun seenByForEvent(
        roomId: String,
        eventId: String,
        limit: Int
    ): List<SeenByEntry> {
        return runBlocking(Dispatchers.IO) {
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
    }

    override suspend fun mxcThumbnailToCache(
        mxcUri: String,
        width: Int,
        height: Int,
        crop: Boolean
    ): String = withContext(Dispatchers.IO) {
        withClient {
            it.mxcThumbnailToCache(mxcUri, width.toUInt(), height.toUInt(), crop)
        }
    }

    override suspend fun loadRoomListCache(): List<RoomListEntry> =
        withContext(Dispatchers.IO) {
            withClient { cl ->
                cl.loadRoomListCache().map { it.toKotlinRoomListEntry() }
            }
        }

    override suspend fun searchRoom(
        roomId: String,
        query: String,
        limit: Int,
        offset: Int?
    ): SearchPage = withContext(Dispatchers.IO) {
        withClient {
            it.searchRoom(roomId, query, limit.toUInt(), offset?.toUInt()).toKotlin()
        }
    }

    override suspend fun startElementCall(
        roomId: String,
        intent: CallIntent,
        elementCallUrl: String?,
        parentUrl: String?,  // ADD THIS
        languageTag: String?,
        theme: String?,
        observer: CallWidgetObserver,
    ): CallSession? = withContext(Dispatchers.IO) {
        val ffiIntent = when (intent) {
            CallIntent.StartCall -> mages.ElementCallIntent.START_CALL
            CallIntent.JoinExisting -> mages.ElementCallIntent.JOIN_EXISTING
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

    override fun callWidgetFromWebview(sessionId: ULong, message: String): Boolean {
        return runBlocking(Dispatchers.IO) {
            runCatching { withClient { it.callWidgetFromWebview(sessionId, message) } }
                .getOrDefault(false)
        }
    }

    override fun stopElementCall(sessionId: ULong): Boolean {
        return runBlocking(Dispatchers.IO) {
            runCatching { withClient { it.stopElementCall(sessionId) } }.getOrDefault(false)
        }
    }
}

private fun FfiRoom.toModel() = RoomSummary(id = id, name = name)

private fun mages.MessageEvent.toModel() = MessageEvent(
    itemId = itemId,
    eventId = eventId,
    roomId = roomId,
    sender = sender,
    body = body,
    timestampMs = timestampMs.toLong(),
    sendState = sendState?.toKotlin(),
    txnId = txnId,
    replyToEventId = replyToEventId,
    replyToSender = replyToSender,
    replyToBody = replyToBody,
    attachment = attachment?.toModel(),
    threadRootEventId = threadRootEventId,
    isEdited = isEdited,
    senderAvatarUrl = senderAvatarUrl,
    senderDisplayName = senderDisplayName,
    replyToSenderDisplayName = replyToSenderDisplayName,
    pollData = pollData?.toModel(),
    reactions = reactions.map { ReactionChip(it.key, it.count.toInt(), it.me) }
)

private fun mages.SendState.toKotlin(): SendState = when (this) {
    mages.SendState.SENDING -> SendState.Sending
    mages.SendState.SENT -> SendState.Sent
    mages.SendState.FAILED -> SendState.Failed
    mages.SendState.ENQUEUED -> SendState.Enqueued
    mages.SendState.RETRYING -> SendState.Retrying
}

private fun mages.EncFile.toModel() = EncFile(url = url, json = json)

private fun mages.AttachmentInfo.toModel() = AttachmentInfo(
    kind = when (kind) {
        mages.AttachmentKind.IMAGE -> AttachmentKind.Image
        mages.AttachmentKind.VIDEO -> AttachmentKind.Video
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
    },
    mxcUri = mxcUri,
    mime = mime,
    sizeBytes = sizeBytes?.toULong(),
    width = width?.toUInt(),
    height = height?.toUInt(),
    durationMs = durationMs?.toULong(),
    thumbnailMxcUri = thumbnailMxcUri,
    encrypted = encrypted?.toFfi(),
    thumbnailEncrypted = thumbnailEncrypted?.toFfi(),
)

private fun RoomNotificationMode.toFfi(): FfiRoomNotificationMode = when (this) {
    RoomNotificationMode.AllMessages -> FfiRoomNotificationMode.ALL_MESSAGES
    RoomNotificationMode.MentionsAndKeywordsOnly -> FfiRoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY
    RoomNotificationMode.Mute -> FfiRoomNotificationMode.MUTE
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
    mages.NotificationKind.CALL_RING -> NotificationKind.CallRing
    mages.NotificationKind.CALL_NOTIFY -> NotificationKind.CallNotify
    mages.NotificationKind.CALL_INVITE -> NotificationKind.CallInvite
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
        options = mappedOptions,
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