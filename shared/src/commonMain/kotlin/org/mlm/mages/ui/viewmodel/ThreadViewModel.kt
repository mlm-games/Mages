package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.viewModelScope
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mages.shared.generated.resources.*
import org.koin.core.component.inject
import org.mlm.mages.LinkPreview
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.ReplyPreviewKind
import org.mlm.mages.StickerInfo
import org.mlm.mages.thumbKey
import org.mlm.mages.withBridgedThumb
import org.mlm.mages.matrix.ImagePackSummary
import org.mlm.mages.matrix.LINK_PREVIEW_IMAGE_PX
import org.mlm.mages.matrix.PollData
import org.mlm.mages.matrix.TimelineDiff
import org.mlm.mages.matrix.allowsLinkPreviews
import org.mlm.mages.matrix.allowsMediaPreviews
import org.mlm.mages.emoji.RecentEmojiStore
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.storage.UserProfile
import org.mlm.mages.ui.MentionProfileUi
import org.mlm.mages.ui.AttachmentUploadStage
import org.mlm.mages.ui.isEditableBy
import org.mlm.mages.ui.ThreadUiState
import org.mlm.mages.ui.toUi
import org.mlm.mages.ui.components.AttachmentData
import org.mlm.mages.ui.components.composer.EmoteSuggestion
import org.mlm.mages.ui.components.composer.OutgoingText
import org.mlm.mages.ui.components.composer.SpoilerPlaceholder
import org.mlm.mages.ui.components.composer.composerToPlainBody
import org.mlm.mages.ui.components.composer.editComposerText
import org.mlm.mages.ui.components.composer.isMessageTooLong
import org.mlm.mages.ui.components.composer.parseComposerMarkdown
import org.mlm.mages.ui.components.composer.emoteSuggestionsFrom
import org.mlm.mages.ui.components.core.emoteMxcUrisFrom
import org.mlm.mages.ui.components.core.firstLinkIn
import org.mlm.mages.ui.components.message.mxcReactionKeys
import org.mlm.mages.ui.components.message.reactionShortcodeFor
import org.mlm.mages.ui.components.message.reactionShortcodesFrom
import org.mlm.mages.ui.util.previewThumbnail
import org.mlm.mages.ui.util.downloadNameHint
import org.mlm.mages.matrix.ReactionSummary
import org.mlm.mages.platform.Notifier
import org.mlm.mages.platform.isTgsSticker
import org.mlm.mages.platform.deleteDirectory
import org.mlm.mages.platform.voiceMessageMimeType
import org.mlm.mages.platform.voiceMessageExtension
import org.mlm.mages.AttachmentKind
import kotlin.getValue
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.getString
import mages.shared.generated.resources.Res

class ThreadViewModel(
    protected override val service: MatrixService,
    private val roomId: String,
    private val rootEventId: String,
    roomName: String = "",
    focusedEventId: String? = null,
) : MemberActionViewModel<ThreadUiState>(
    ThreadUiState(
        roomId = roomId,
        rootEventId = rootEventId,
        roomName = roomName,
        focusedEventId = focusedEventId,
        isLoading = true,
    )
) {

    sealed class Event {
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
        data class NavigateToRoom(val roomId: String, val title: String) : Event()
        data class OpenForwardPicker(val roomId: String, val eventIds: List<String>) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    override val myUserId: String? = service.port.whoami()

    override val memberRoomId: String get() = roomId

    override fun memberFallbackEvents(): List<MessageEvent> = currentState.allMessages

    override suspend fun sendError(message: String) {
        _events.send(Event.ShowError(message))
    }

    override suspend fun sendSuccess(message: String) {
        _events.send(Event.ShowSuccess(message))
    }

    override suspend fun sendNavigateToRoom(roomId: String, title: String) {
        _events.send(Event.NavigateToRoom(roomId, title))
    }

    private var rootTimelineJob: Job? = null
    private var threadTimelineJob: Job? = null
    private var uploadJob: Job? = null
    private var uploadTxnId: String? = null

    private val replyThumbnailFetchInFlight = mutableSetOf<String>()
    private var lastMarkedReadEventId: String? = null
    private var lastMarkedReadTs = 0L
    private var threadStartProbeInFlight = false
    private var threadStartReached = false

    private val settingsRepo: SettingsRepository<AppSettings> by inject()

    private val recentEmoji: RecentEmojiStore by inject()

    private val prefs = settingsRepo.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    init {
        Notifier.clearThreadNotification(roomId, rootEventId)
        preloadRoomMembers()
        launch {
            val profile = runSafe { service.port.roomProfile(roomId) } ?: return@launch
            updateState { copy(isDm = profile.isDm) }
            if (profile.name.isNotBlank() &&
                (currentState.roomName.isBlank() || currentState.roomName == roomId)
            ) {
                updateState { copy(roomName = profile.name) }
            }
        }
        loadSendActionState()
        loadImagePacks()
        loadRoomEncryption()
        loadRootMessage()
        observeRootTimeline()
        observeThreadTimeline()
    }

    private fun loadSendActionState() {
        launch {
            val actionState = runSafe { service.port.roomActionState(roomId) } ?: return@launch
            updateState { copy(sendMessageAction = actionState.sendMessage.toUi()) }
        }
    }

    private fun preloadRoomMembers() {
        launch {
            val members = runSafe { service.port.listMembers(roomId) }.orEmpty()
            if (members.isNotEmpty()) {
                updateState { copy(roomMembers = members) }

                val resolved = members
                    .mapNotNull { member ->
                        val avatarUrl = member.avatarUrl ?: return@mapNotNull null
                        member.userId to avatarUrl
                    }
                    .associate { (userId, avatarUrl) ->
                        userId to service.avatars.resolve(avatarUrl, px = 64, crop = true)
                    }
                    .filterValues { it != null }
                    .mapValues { it.value!! }

                if (resolved.isNotEmpty()) {
                    updateState { copy(avatarByUserId = avatarByUserId + resolved) }
                }
            }
        }
    }

    fun refreshImagePacks() = loadImagePacks()

    private fun loadImagePacks() {
        launch {
            val packs = runSafe { service.port.listImagePacks(roomId) }.orEmpty()
            updateState { copy(imagePacks = packs) }
        }
    }

    private fun loadRoomEncryption() {
        launch {
            val encrypted = runSafe { service.port.roomProfile(roomId) }?.isEncrypted == true
            updateState { copy(isRoomEncrypted = encrypted) }
        }
    }

    private var emoteCacheKey: List<ImagePackSummary>? = null
    private var emoteCache: List<EmoteSuggestion> = emptyList()

    /** Emotes from every pack visible here; the pack name disambiguates duplicates. */
    val emoteSuggestions: List<EmoteSuggestion>
        get() {
            val packs = currentState.imagePacks
            if (packs === emoteCacheKey) return emoteCache
            val resolved = emoteSuggestionsFrom(packs)
            emoteCacheKey = packs
            emoteCache = resolved
            return resolved
        }

    /** Caches an emote referenced by a message in this thread for inline display. */
    suspend fun emotePreview(
        thumbnailMxcUri: String?,
        mxcUrl: String,
        mime: String? = null,
        full: Boolean = false,
    ): String? {
        val original = full || isTgsSticker(mime, mxcUrl)
        val source = if (original) mxcUrl else thumbnailMxcUri ?: mxcUrl
        val key = if (original) "full:$source" else source
        currentState.emotePathByMxc[key]?.let { return it }
        val path = if (original) {
            runSafe {
                service.downloadStickerToCache(StickerInfo(mxcUri = source, mime = mime)).getOrNull()
            }
        } else {
            runSafe { service.port.mxcThumbnailToCache(key, 128, 128, false) }
        } ?: return null
        updateState { copy(emotePathByMxc = emotePathByMxc + (key to path)) }
        return path
    }

    private fun observeRootTimeline() {
        rootTimelineJob?.cancel()
        rootTimelineJob = viewModelScope.launch {
            service.timelineDiffs(roomId).collect { diff ->
                applyRootDiff(diff)
            }
        }
    }

    private fun applyRootDiff(diff: TimelineDiff<MessageEvent>) {
        when (diff) {
            is TimelineDiff.Reset -> diff.items.firstOrNull { it.eventId == rootEventId }?.let(::updateRoot)
            is TimelineDiff.Append -> diff.items.firstOrNull { it.eventId == rootEventId }?.let(::updateRoot)
            is TimelineDiff.Prepend -> if (diff.item.eventId == rootEventId) updateRoot(diff.item)
            is TimelineDiff.Insert -> if (diff.item.eventId == rootEventId) updateRoot(diff.item)
            is TimelineDiff.UpdateByItemId -> if (diff.item.eventId == rootEventId) updateRoot(diff.item)
            is TimelineDiff.RemoveByItemId, is TimelineDiff.Clear -> Unit
        }
    }

    private fun updateRoot(event: MessageEvent) {
        val bridged = event.withBridgedThumb(currentState.thumbByEvent)
        updateState {
            copy(
                rootMessage = event,
                thumbByEvent = bridged ?: thumbByEvent,
            )
        }
        prefetchReplies(listOf(event))
    }

    private fun loadRootMessage() {
        launch {
            val root = runSafe { service.port.eventDetails(roomId, rootEventId) }
            if (root != null) {
                updateRoot(root)
            } else if (currentState.rootMessage == null) {
                updateState { copy(error = getString(Res.string.failed_to_load_thread)) }
            }
        }
    }

    private fun observeThreadTimeline() {
        threadTimelineJob?.cancel()
        threadTimelineJob = viewModelScope.launch {
            service.threadTimelineDiffs(roomId, rootEventId)
                .catch { e ->
                    val message = currentState.error ?: e.message
                        ?: getString(Res.string.failed_to_load_thread)
                    updateState {
                        copy(
                            isLoading = false,
                            hasMore = false,
                            hasInitialLoad = true,
                            error = message,
                        )
                    }
                }
                .collect { diff -> applyThreadDiff(diff) }
        }
    }

    private fun applyThreadDiff(diff: TimelineDiff<MessageEvent>) {
        when (diff) {
            is TimelineDiff.Reset -> replaceReplies(diff.items)
            is TimelineDiff.Append -> appendReplies(diff.items)
            is TimelineDiff.Prepend -> prependReplies(listOf(diff.item))
            is TimelineDiff.Insert -> upsertReply(diff.item)
            is TimelineDiff.UpdateByItemId -> upsertReply(diff.item)
            is TimelineDiff.RemoveByItemId -> removeReply(diff.itemId)
            is TimelineDiff.Clear -> {
                threadStartReached = false
                updateState {
                    copy(
                        replies = emptyList(),
                        hasInitialLoad = false,
                        hasMore = true,
                        isLoading = false,
                        error = null,
                    )
                }
            }
        }
    }

    private fun replaceReplies(events: List<MessageEvent>) {
        val replies = events
            .filter { it.eventId != rootEventId }
            .distinctBy { it.itemId }
            .sortedForThread()
        prefetchReplies(events)
        updateState {
            copy(
                replies = replies,
                hasMore = !threadStartReached,
                hasInitialLoad = true,
                isLoading = false,
                error = null,
            )
        }
        markLatestRead(events)
        probeThreadStart()
    }

    private fun appendReplies(events: List<MessageEvent>) {
        if (events.isEmpty()) return
        prefetchReplies(events)
        updateState {
            val merged = (events + replies)
                .filter { it.eventId != rootEventId }
                .distinctBy { it.itemId }
                .sortedForThread()
            copy(replies = merged, isLoading = false)
        }
        markLatestRead(events)
    }

    private fun prependReplies(events: List<MessageEvent>) {
        if (events.isEmpty()) return
        prefetchReplies(events)
        updateState {
            val merged = (events + replies)
                .filter { it.eventId != rootEventId }
                .distinctBy { it.itemId }
                .sortedForThread()
            copy(replies = merged, isLoading = false)
        }
    }

    private fun upsertReply(event: MessageEvent) {
        if (event.eventId == rootEventId) {
            updateRoot(event)
            return
        }
        prefetchReplies(listOf(event))
        updateState {
            val idx = replies.indexOfFirst { it.itemId == event.itemId }
            val updated = if (idx >= 0) {
                replies.toMutableList().apply { this[idx] = event }
            } else {
                replies + event
            }
            copy(replies = updated.distinctBy { it.itemId }.sortedForThread())
        }
    }

    private fun removeReply(itemId: String) {
        updateState { copy(replies = replies.filterNot { it.itemId == itemId }) }
    }

    private fun prefetchReplies(events: List<MessageEvent>) {
        prefetchSenderAvatars(events)
        prefetchReactionUserAvatars(events)
        prefetchReplyThumbnails(events)
        prefetchLinkPreviews(events)
        prefetchMentionProfiles(events)
        prefetchAudioForEvents(events)
    }

    private fun probeThreadStart() {
        if (threadStartReached ||
            threadStartProbeInFlight ||
            !currentState.hasMore ||
            currentState.replies.size >= 20
        ) return
        threadStartProbeInFlight = true
        launch {
            val hitStart = runSafe { service.paginateThread(roomId, rootEventId, 20) }
                ?.getOrNull() == true
            threadStartProbeInFlight = false
            if (hitStart) {
                threadStartReached = true
                updateState { copy(hasMore = false) }
            }
        }
    }

    private fun markLatestRead(events: List<MessageEvent>) {
        val latest = events
            .filter { it.eventId.isNotBlank() && it.threadRootEventId == rootEventId }
            .maxByOrNull { it.timestampMs }
            ?: return
        if (latest.eventId == lastMarkedReadEventId) return
        if (latest.timestampMs < lastMarkedReadTs) return
        lastMarkedReadEventId = latest.eventId
        launch {
            val result = service.markThreadReadAt(
                roomId = roomId,
                threadRootEventId = rootEventId,
                eventId = latest.eventId,
                sendPublicReceipt = prefs.value.sendReadReceipts
            )
            if (result.isFailure) {
                lastMarkedReadEventId = null
            } else {
                lastMarkedReadTs = maxOf(lastMarkedReadTs, latest.timestampMs)
            }
        }
    }

    fun retryLoad() {
        threadStartReached = false
        threadStartProbeInFlight = false
        updateState {
            copy(
                replies = emptyList(),
                isLoading = true,
                error = null,
                hasInitialLoad = false,
                hasMore = true,
            )
        }
        loadRootMessage()
        observeThreadTimeline()
    }

    /**
     * Load more (older) messages in the thread.
     */
    fun loadMore() {
        if (!currentState.hasMore || currentState.isLoading) {
            if (!currentState.hasMore && currentState.focusedEventId != null) {
                updateState { copy(focusedEventMissing = true) }
            }
            return
        }

        updateState { copy(isLoading = true) }
        launch(onError = { e ->
            updateState { copy(isLoading = false) }
            launch { _events.send(Event.ShowError(e.message ?: getString(Res.string.failed_to_load_more_messages))) }
        }) {
            val result = service.paginateThread(roomId, rootEventId, 50)
            val hitStart = result.getOrNull() == true
            if (result.isSuccess && hitStart) threadStartReached = true
            updateState {
                copy(
                    isLoading = false,
                    hasMore = if (result.isSuccess) !hitStart && !threadStartReached else hasMore,
                    focusedEventMissing = hitStart &&
                            focusedEventId != null &&
                            rootMessage?.eventId != focusedEventId &&
                            replies.none { it.eventId == focusedEventId },
                )
            }
            if (result.isFailure) {
                _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.failed_to_load_more_messages))))
            }
        }
    }

    fun clearFocusedEvent() {
        updateState { copy(focusedEventId = null, focusedEventMissing = false) }
    }

    /**
     * React to a message with an emoji.
     */
    fun react(event: MessageEvent, emoji: String) {
        if (event.eventId.isBlank()) return
        recentEmoji.record(emoji)
        launch {
            runSafe {
                service.port.react(
                    roomId = roomId,
                    eventId = event.eventId,
                    key = emoji,
                    shortcode = reactionShortcodeFor(emoji),
                    threadRootEventId = rootEventId,
                )
            }
        }
    }

    /** MSC4027's shortcode for an mxc reaction key, when it is a known pack image. */
    fun reactionShortcodeFor(key: String): String? =
        currentState.imagePacks.reactionShortcodeFor(key)

    val reactionShortcodes: Map<String, String>
        get() = reactionShortcodesFrom(currentState.imagePacks)

    private val reactionImageFetchInFlight = mutableSetOf<String>()

    fun ensureReactionImages(chips: List<ReactionSummary>) {
        val missing = mxcReactionKeys(chips)
            .filterNot { currentState.reactionImagePathByMxc.containsKey(it) }
            .filter { reactionImageFetchInFlight.add(it) }
        if (missing.isEmpty()) return

        launch {
            val resolved = missing
                .mapNotNull { mxc ->
                    runSafe { service.port.mxcThumbnailToCache(mxc, 64, 64, false) }
                        ?.let { mxc to it }
                }
                .toMap()
            missing.forEach { reactionImageFetchInFlight.remove(it) }
            if (resolved.isEmpty()) return@launch
            updateState { copy(reactionImagePathByMxc = reactionImagePathByMxc + resolved) }
        }
    }

    fun enterSelectionMode(eventId: String) {
        if (eventId.isBlank()) return
        updateState { copy(isSelectionMode = true, selectedEventIds = setOf(eventId)) }
    }

    fun toggleSelected(eventId: String) {
        if (eventId.isBlank()) return
        updateState {
            val next = if (eventId in selectedEventIds) {
                selectedEventIds - eventId
            } else {
                selectedEventIds + eventId
            }
            copy(selectedEventIds = next, isSelectionMode = next.isNotEmpty())
        }
    }

    fun clearSelection() {
        updateState { copy(isSelectionMode = false, selectedEventIds = emptySet()) }
    }

    fun selectAllVisible() {
        val ids = currentState.allMessages
            .mapNotNull { it.eventId.takeIf { id -> id.isNotBlank() } }
            .toSet()
        updateState { copy(isSelectionMode = ids.isNotEmpty(), selectedEventIds = ids) }
    }

    fun forwardSelected() {
        val ids = currentState.selectedEventIds.toList()
        if (ids.isEmpty()) return
        launch {
            _events.send(Event.OpenForwardPicker(roomId, ids))
            clearSelection()
        }
    }

    fun forwardEvent(event: MessageEvent) {
        if (event.eventId.isBlank()) return
        launch { _events.send(Event.OpenForwardPicker(roomId, listOf(event.eventId))) }
    }

    fun deleteSelected() {
        val selected = currentState.allMessages
            .filter { it.eventId.isNotBlank() && it.eventId in currentState.selectedEventIds }
        if (selected.isEmpty()) {
            clearSelection()
            return
        }

        launch {
            var deleted = 0
            selected.forEach { event ->
                val result = runSafe { service.port.redact(roomId, event.eventId, null) }
                if (result?.isSuccess == true) deleted++
            }
            clearSelection()
            if (deleted == selected.size) {
                sendSuccess(getString(Res.string.deleted_n_messages, deleted))
            } else {
                sendError(getString(Res.string.deleted_n_of_m_messages, deleted, selected.size))
            }
        }
    }

    fun votePoll(pollEventId: String, poll: PollData, optionId: String) {
        if (pollEventId.isBlank()) return
        launch {
            val currentSelections = poll.mySelections.toSet()
            val newSelections = if (poll.maxSelections <= 1L) {
                if (currentSelections.contains(optionId)) emptyList() else listOf(optionId)
            } else {
                if (currentSelections.contains(optionId)) {
                    currentSelections - optionId
                } else if (currentSelections.size >= poll.maxSelections) {
                    sendError(getString(Res.string.max_selections_options, poll.maxSelections))
                    return@launch
                } else {
                    currentSelections + optionId
                }
            }.toList()

            val result = service.port.sendPollResponse(roomId, pollEventId, newSelections)
            if (result.isFailure) {
                sendError(getString(Res.string.failed_to_submit_vote))
            }
        }
    }

    fun endPoll(pollEventId: String) {
        if (pollEventId.isBlank()) return
        launch {
            val result = service.port.sendPollEnd(roomId, pollEventId)
            if (result.isFailure) {
                sendError(getString(Res.string.failed_to_end_poll))
            } else {
                sendSuccess(getString(Res.string.poll_ended))
            }
        }
    }

    fun markReadHere(event: MessageEvent) {
        if (event.eventId.isBlank()) return
        launch {
            val result = service.markThreadReadAt(
                roomId = roomId,
                threadRootEventId = rootEventId,
                eventId = event.eventId,
                sendPublicReceipt = prefs.value.sendReadReceipts,
            )
            if (result.isSuccess) lastMarkedReadEventId = event.eventId
        }
    }

    fun showStaticLocation(lat: Double, lon: Double) = updateState {
        copy(
            showStaticLocationViewer = true,
            staticLocationViewerLat = lat,
            staticLocationViewerLon = lon,
        )
    }

    fun hideStaticLocation() = updateState { copy(showStaticLocationViewer = false) }

    fun retry(event: MessageEvent) {
        val txnId = event.txnId
        if (txnId.isNullOrBlank()) {
            launch { sendError(getString(Res.string.this_message_cannot_be_retried)) }
            return
        }
        launch {
            val result = service.retryByTxn(roomId, txnId)
            if (result.isFailure) {
                sendError(result.toUserMessage(getString(Res.string.retry_failed)))
            }
        }
    }

    fun showAttachmentPicker() = updateState { copy(showAttachmentPicker = true) }

    fun hideAttachmentPicker() = updateState { copy(showAttachmentPicker = false) }

    fun attachFile(data: AttachmentData) {
        updateState {
            copy(
                attachments = attachments + data,
                showAttachmentPicker = false
            )
        }
    }

    fun removeAttachment(index: Int) {
        updateState {
            copy(attachments = attachments.toMutableList().apply { removeAt(index) })
        }
    }

    fun send() {
        if (currentState.isUploadingAttachment) return
        if (currentState.editingEvent != null) {
            launch { confirmEdit() }
            return
        }

        val snapshot = currentState
        val pending = snapshot.attachments
        if (pending.isEmpty()) {
            launch { sendMessage(snapshot.input) }
            return
        }

        val text = snapshot.input
        val replyTo = snapshot.replyingTo
        updateState { copy(input = "", replyingTo = null, attachments = emptyList()) }

        uploadJob = launch {
            val outgoing = text.trim().takeIf { it.isNotBlank() }?.toOutgoingText()
            if (outgoing != null &&
                isMessageTooLong(outgoing.body, outgoing.formattedBody, snapshot.isRoomEncrypted)
            ) {
                updateState {
                    copy(
                        input = if (input.isBlank()) text else input,
                        attachments = pending,
                        replyingTo = replyTo,
                    )
                }
                _events.send(Event.ShowError(getString(Res.string.message_too_long)))
                return@launch
            }

            val canUseCaption = outgoing != null && pending.size == 1
            val sent = sendAttachmentsInternal(
                items = pending,
                caption = outgoing?.body.takeIf { canUseCaption },
                formattedCaption = outgoing?.formattedBody.takeIf { canUseCaption },
                replyToEventId = replyTo?.eventId,
            )
            if (!sent) return@launch

            if (outgoing != null && !canUseCaption) {
                updateState { copy(replyingTo = replyTo) }
                sendMessage(text)
            }
        }
    }

    private suspend fun sendAttachmentsInternal(
        items: List<AttachmentData>,
        caption: String? = null,
        formattedCaption: String? = null,
        replyToEventId: String? = null,
    ): Boolean {
        items.forEachIndexed { index, data ->
            val isLast = index == items.lastIndex

            updateState {
                copy(
                    isUploadingAttachment = true,
                    attachmentProgress = 0f,
                    attachmentUploadStage = AttachmentUploadStage.Preparing,
                    uploadingFileName = "${data.fileName} (${index + 1}/${items.size})",
                )
            }

            val txn = Uuid.random().toString()
            uploadTxnId = txn
            val result = service.sendAttachmentFromPath(
                roomId = roomId,
                path = data.path,
                mime = data.mimeType,
                filename = data.fileName,
                caption = if (isLast) caption else null,
                formattedCaption = if (isLast) formattedCaption else null,
                replyToEventId = if (isLast) replyToEventId else null,
                threadRootEventId = rootEventId,
                txnId = txn,
            ) { sent, totalBytes ->
                val denom = (totalBytes ?: data.sizeBytes).coerceAtLeast(1L).toFloat()
                val progress = (sent.toFloat() / denom).coerceIn(0f, 1f)
                val stage = when {
                    sent <= 0L || progress <= 0f -> AttachmentUploadStage.Preparing
                    progress >= 1f -> AttachmentUploadStage.Sending
                    else -> AttachmentUploadStage.Uploading
                }
                updateState {
                    copy(
                        attachmentProgress = progress,
                        attachmentUploadStage = stage,
                    )
                }
            }

            if (result.isFailure) {
                updateState {
                    copy(
                        isUploadingAttachment = false,
                        attachmentProgress = 0f,
                        attachmentUploadStage = null,
                        uploadingFileName = null,
                        attachments = items.drop(index + 1),
                    )
                }
                uploadTxnId = null
                _events.send(
                    Event.ShowError(
                        result.toUserMessage(getString(Res.string.upload_failed_named, data.fileName))
                    )
                )
                return false
            }
        }

        updateState {
            copy(
                isUploadingAttachment = false,
                attachmentProgress = 0f,
                attachmentUploadStage = null,
                uploadingFileName = null,
            )
        }
        uploadTxnId = null
        return true
    }

    fun cancelAttachmentUpload() {
        val txnId = uploadTxnId
        uploadTxnId = null
        uploadJob?.cancel()
        uploadJob = null
        if (!txnId.isNullOrBlank()) {
            launch { service.cancelSend(roomId, txnId) }
        }
        updateState {
            copy(
                isUploadingAttachment = false,
                attachmentProgress = 0f,
                attachmentUploadStage = null,
                uploadingFileName = null,
                attachments = emptyList(),
            )
        }
    }

    fun onVoiceRecordingComplete(filePath: String, durationMs: Long, waveform: List<Float>) {
        updateState {
            copy(
                isRecordingVoice = false,
                voiceRecordingPath = filePath,
                voiceRecordingDurationMs = durationMs,
                voiceRecordingWaveform = waveform,
                showVoicePreview = true,
            )
        }
    }

    fun sendVoiceRecording() {
        val path = currentState.voiceRecordingPath ?: return
        val durationMs = currentState.voiceRecordingDurationMs.takeIf { it > 0 }
        val waveform = currentState.voiceRecordingWaveform.takeIf { it.isNotEmpty() }
        val replyTo = currentState.replyingTo

        updateState {
            copy(
                showVoicePreview = false,
                voiceRecordingPath = null,
                voiceRecordingDurationMs = 0L,
                voiceRecordingWaveform = emptyList(),
                replyingTo = null,
            )
        }

        launch {
            val result = service.sendAttachmentFromPath(
                roomId = roomId,
                path = path,
                mime = voiceMessageMimeType,
                filename = "voice_message.$voiceMessageExtension",
                replyToEventId = replyTo?.eventId,
                threadRootEventId = rootEventId,
                voiceDurationMs = durationMs,
                voiceWaveform = waveform,
                isVoice = true,
            ) { _, _ -> }

            if (result.isFailure) {
                updateState {
                    if (replyingTo == null) copy(replyingTo = replyTo) else this
                }
                _events.send(
                    Event.ShowError(
                        result.toUserMessage(getString(Res.string.voice_message_upload_failed))
                    )
                )
            }
        }
    }

    fun cancelVoicePreview() {
        currentState.voiceRecordingPath?.let {
            runCatching { deleteDirectory(it) }
        }
        updateState {
            copy(
                showVoicePreview = false,
                voiceRecordingPath = null,
                voiceRecordingDurationMs = 0L,
                voiceRecordingWaveform = emptyList(),
            )
        }
    }

    fun reRecordVoice() {
        currentState.voiceRecordingPath?.let {
            runCatching { deleteDirectory(it) }
        }
        updateState {
            copy(
                showVoicePreview = false,
                voiceRecordingPath = null,
                voiceRecordingDurationMs = 0L,
                voiceRecordingWaveform = emptyList(),
                isRecordingVoice = true,
            )
        }
    }

    fun startVoiceRecording() = updateState { copy(isRecordingVoice = true) }

    fun cancelVoiceRecording() {
        updateState {
            copy(
                isRecordingVoice = false,
                voiceRecordingPath = null,
                voiceRecordingDurationMs = 0L,
                voiceRecordingWaveform = emptyList(),
            )
        }
    }

    /**
     * Start replying to a message.
     */
    fun startReply(event: MessageEvent) {
        if (event.eventId.isBlank()) return
        updateState { copy(replyingTo = event) }
    }

    /**
     * Cancel the current reply.
     */
    fun cancelReply() {
        updateState { copy(replyingTo = null) }
    }

    /**
     * Start editing a message.
     */
    fun startEdit(event: MessageEvent) {
        updateState { copy(editingEvent = event, input = event.editComposerText()) }
    }

    private fun MessageEvent.isThreadEditable(): Boolean =
        isEditableBy(myUserId) && attachment == null && pollData == null

    fun hasEditableLatest(): Boolean = currentState.allMessages.any { it.isThreadEditable() }

    fun startEditLatestEditable() {
        val event = currentState.allMessages.lastOrNull { it.isThreadEditable() } ?: return
        startEdit(event)
    }

    /**
     * Cancel the current edit.
     */
    fun cancelEdit() {
        updateState { copy(editingEvent = null, input = "") }
    }

    /**
     * Update the input text.
     */
    fun setInput(value: String) {
        updateState { copy(input = value) }
        if (!prefs.value.sendTypingIndicators) return
    }

    private fun mediaPreviewsAllowed(): Boolean =
        prefs.value.mediaPreviews.allowsMediaPreviews(isPrivateRoom = currentState.isDm)

    private fun linkPreviewsAllowed(): Boolean =
        prefs.value.linkPreviews.allowsLinkPreviews(currentState.isRoomEncrypted)

    private fun prefetchReplyThumbnails(events: List<MessageEvent>) {
        if (!mediaPreviewsAllowed()) return
        events.forEach {
            prefetchReplyThumbnail(it)
            prefetchEmotes(it)
            ensureThumbnail(it)
        }
    }

    private fun prefetchAudioForEvents(events: List<MessageEvent>) {
        if (!mediaPreviewsAllowed()) return
        events.takeLast(8).forEach { event ->
            val attachment = event.attachment ?: return@forEach
            if (attachment.kind != AttachmentKind.Audio) return@forEach
            if (event.eventId.isBlank()) return@forEach
            if (currentState.audioFileByEvent.containsKey(event.eventId)) return@forEach
            launch {
                val nameHint = "voice_${event.eventId}.$voiceMessageExtension"
                service.port.downloadAttachmentToCache(attachment, nameHint)
                    .onSuccess { path ->
                        updateState {
                            copy(
                                audioFileByEvent = audioFileByEvent + (event.eventId to path),
                                waveformByEvent = attachment.waveform?.let {
                                    waveformByEvent + (event.eventId to it)
                                } ?: waveformByEvent
                            )
                        }
                    }
            }
        }
    }

    private fun prefetchLinkPreviews(events: List<MessageEvent>) {
        if (!linkPreviewsAllowed()) return
        events.forEach { ensureLinkPreview(it) }
    }

    private fun prefetchMentionProfiles(events: List<MessageEvent>) {
        val known = currentState.mentionProfilesByUserId
        val wanted = LinkedHashSet<String>()
        val seen = LinkedHashMap<String, UserProfile>()
        events.forEach { event ->
            val senderName = event.senderDisplayName
            if (!senderName.isNullOrBlank()) {
                seen[event.sender] = UserProfile(senderName, event.senderAvatarUrl)
            }
            event.mentionedUserIds.forEach { userId ->
                if (userId.isNotBlank() && userId !in known) wanted += userId
            }
        }
        if (wanted.isEmpty()) return

        launch {
            service.profiles.rememberAll(seen + currentState.roomMembers
                .filter { it.userId in wanted }
                .mapNotNull { member ->
                    member.displayName?.let { member.userId to UserProfile(it, member.avatarUrl) }
                }
                .toMap())

            val resolved = LinkedHashMap<String, MentionProfileUi>()
            wanted.take(24).forEach { userId ->
                val profile = withTimeoutOrNull(8_000) {
                    runCatching { service.profiles.resolve(userId) }.getOrNull()
                }
                val name = profile?.displayName?.takeIf { it.isNotBlank() } ?: userId
                val avatarUrl = profile?.avatarUrl
                val avatarPath = if (avatarUrl != null && mediaPreviewsAllowed()) {
                    withTimeoutOrNull(8_000) {
                        runCatching { service.avatars.resolve(avatarUrl, px = 64, crop = true) }.getOrNull()
                    }
                } else {
                    null
                }
                resolved[userId] = MentionProfileUi(userId, name, avatarPath)
            }
            if (resolved.isEmpty()) return@launch
            updateState { copy(mentionProfilesByUserId = mentionProfilesByUserId + resolved) }
        }
    }

    fun ensureLinkPreview(event: MessageEvent) {
        if (!linkPreviewsAllowed()) return
        if (event.isRedacted || event.eventId.isBlank()) return
        if (currentState.linkPreviewByEvent.containsKey(event.eventId)) return
        if (!linkPreviewFetchInFlight.add(event.eventId)) return

        val link = firstLinkIn(event.body, event.formattedBody)
        if (link == null) {
            rememberLinkPreview(event.eventId, null)
            linkPreviewFetchInFlight.remove(event.eventId)
            return
        }

        launch {
            try {
                val preview = service.linkPreviews.resolve(link)
                rememberLinkPreview(event.eventId, preview)

                val mxc = preview?.imageMxcUri ?: return@launch
                if (!mediaPreviewsAllowed()) return@launch
                val path = service.avatars.resolve(mxc, px = LINK_PREVIEW_IMAGE_PX, crop = false)
                    ?: return@launch
                updateState {
                    copy(linkPreviewImageByEvent = linkPreviewImageByEvent + (event.eventId to path))
                }
            } finally {
                linkPreviewFetchInFlight.remove(event.eventId)
            }
        }
    }

    private fun rememberLinkPreview(eventId: String, preview: LinkPreview?) = updateState {
        copy(linkPreviewByEvent = linkPreviewByEvent + (eventId to preview))
    }

    private val thumbnailFetchInFlight = mutableSetOf<String>()
    private val linkPreviewFetchInFlight = mutableSetOf<String>()

    fun showMediaPreview(event: MessageEvent) {
        if (mediaPreviewsAllowed()) return
        if (event.eventId.isNotBlank() && event.eventId !in currentState.revealedPreviewEventIds) {
            updateState { copy(revealedPreviewEventIds = revealedPreviewEventIds + event.eventId) }
        }
        ensureThumbnail(event)
    }

    fun ensureThumbnail(event: MessageEvent) {
        if (!mediaPreviewsAllowed() && event.eventId !in currentState.revealedPreviewEventIds) return
        val key = event.thumbKey ?: return
        if (currentState.thumbByEvent.containsKey(key)) return
        val bridged = event.withBridgedThumb(currentState.thumbByEvent)
        if (bridged != null) {
            updateState { copy(thumbByEvent = bridged) }
            return
        }
        if (key in thumbnailFetchInFlight) return

        val attachment = event.attachment
        val sticker = event.sticker
        if (event.eventId.isBlank() && attachment?.kind == AttachmentKind.Video) return
        val hasValidMedia = when {
            attachment != null ->
                attachment.kind == AttachmentKind.Image ||
                    attachment.kind == AttachmentKind.Video ||
                    attachment.thumbnailMxcUri != null
            sticker != null -> true
            else -> false
        }
        if (!hasValidMedia) return
        if (!thumbnailFetchInFlight.add(key)) return

        launch {
            try {
                val path = attachment?.let { service.previewThumbnail(it).getOrNull() }
                    ?: sticker?.let { service.port.downloadStickerToCache(it).getOrNull() }
                if (!path.isNullOrBlank()) {
                    updateState { copy(thumbByEvent = thumbByEvent + (key to path)) }
                }
            } finally {
                thumbnailFetchInFlight.remove(key)
            }
        }
    }

    fun openAttachment(event: MessageEvent, onOpen: (String, String?) -> Unit) {
        launch {
            val attachment = event.attachment
            val sticker = event.sticker
            val mime: String?
            val result: Result<String>

            when {
                attachment != null -> {
                    mime = attachment.mime
                    result = service.port.downloadAttachmentToCache(
                        attachment,
                        downloadNameHint(event, attachment.fileName, attachment.mime, "file")
                    )
                }
                sticker != null -> {
                    mime = sticker.mime
                    result = service.downloadStickerToCache(
                        sticker,
                        downloadNameHint(event, null, sticker.mime, "sticker")
                    )
                }
                else -> return@launch
            }

            result
                .onSuccess { path ->
                    if (path.isBlank()) {
                        _events.send(Event.ShowError(getString(Res.string.downloaded_file_is_missing_or_empty)))
                    } else {
                        onOpen(path, mime)
                    }
                }
                .onFailure { t ->
                    _events.send(Event.ShowError(t.message ?: getString(Res.string.download_failed)))
                }
        }
    }

    private val emoteFetchInFlight = mutableSetOf<String>()

    private companion object {
        /** Custom emotes render at 32dp, so this covers high-density screens. */
        const val EMOTE_PX = 128
    }

    private fun prefetchEmotes(event: MessageEvent) {
        val missing = emoteMxcUrisFrom(event.formattedBody)
            .filterNot { currentState.emotePathByMxc.containsKey(it) }
        if (missing.isEmpty()) return

        launch {
            val resolved = missing
                .filter { emoteFetchInFlight.add(it) }
                .mapNotNull { mxc ->
                    runSafe { service.port.mxcThumbnailToCache(mxc, EMOTE_PX, EMOTE_PX, false) }
                        ?.let { mxc to it }
                }
                .toMap()
            missing.forEach { emoteFetchInFlight.remove(it) }
            if (resolved.isEmpty()) return@launch
            updateState { copy(emotePathByMxc = emotePathByMxc + resolved) }
        }
    }

    private fun prefetchReplyThumbnail(event: MessageEvent, retryAttempt: Int = 0) {
        if (!mediaPreviewsAllowed()) return
        if (event.threadReplyIsFallback) return
        val replyId = event.replyToEventId?.takeIf { it.isNotBlank() } ?: return
        val preview = event.replyPreview ?: return
        if (preview.kind != ReplyPreviewKind.Image &&
            preview.kind != ReplyPreviewKind.Video &&
            preview.kind != ReplyPreviewKind.Sticker
        ) return
        if (currentState.replyThumbByEvent.containsKey(replyId)) return

        val inFlightKey = "reply:$replyId"
        if (!replyThumbnailFetchInFlight.add(inFlightKey)) return

        val attachment = preview.attachment
        val sticker = preview.sticker
        launch {
            try {
                val result = attachment?.let { service.thumbnailToCache(it, 320, 320, true) }
                    ?: sticker?.let { service.port.downloadStickerToCache(it) }
                val path = result?.getOrNull()?.takeIf { it.isNotBlank() }
                if (path != null) {
                    updateState {
                        copy(replyThumbByEvent = replyThumbByEvent + (replyId to path))
                    }
                } else if (retryAttempt < 2) {
                    launch {
                        delay(1_000L * (retryAttempt + 1))
                        prefetchReplyThumbnail(event, retryAttempt + 1)
                    }
                }
            } finally {
                replyThumbnailFetchInFlight.remove(inFlightKey)
            }
        }
    }

    private fun prefetchSenderAvatars(events: List<MessageEvent>) {
        val byUser = events
            .asReversed()
            .mapNotNull { event ->
                val avatarUrl = event.senderAvatarUrl ?: return@mapNotNull null
                event.sender to avatarUrl
            }
            .distinctBy { it.first }

        if (byUser.isEmpty()) return

        launch {
            val resolved = byUser.associate { (userId, avatarUrl) ->
                userId to service.avatars.resolve(avatarUrl, px = 64, crop = true)
            }.filterValues { it != null }.mapValues { it.value!! }

            if (resolved.isNotEmpty()) {
                updateState { copy(avatarByUserId = avatarByUserId + resolved) }
            }
        }
    }

    private fun prefetchReactionUserAvatars(events: List<MessageEvent>) {
        val userIds = events
            .flatMap { event -> event.reactions.flatMap { it.userIds } }
            .distinct()
            .filter { it !in currentState.avatarByUserId }

        if (userIds.isEmpty()) return

        val members = currentState.roomMembers

        launch {
            val memberAvatars = members
                .filter { it.userId in userIds }
                .mapNotNull { member ->
                    val avatarUrl = member.avatarUrl ?: return@mapNotNull null
                    val path = service.avatars.resolve(avatarUrl, px = 64, crop = true)
                    if (path != null) member.userId to path else null
                }
                .toMap()

            val missingUserIds = userIds.filter { it !in memberAvatars }
            if (missingUserIds.isNotEmpty()) {
                val profileAvatars = missingUserIds.mapNotNull { userId ->
                    val profile = runCatching { service.port.getUserProfile(userId) }.getOrNull()
                    val avatarUrl = profile?.avatarUrl ?: return@mapNotNull null
                    val path = service.avatars.resolve(avatarUrl, px = 64, crop = true)
                    if (path != null) userId to path else null
                }.toMap()

                val allResolved = memberAvatars + profileAvatars
                if (allResolved.isNotEmpty()) {
                    updateState { copy(avatarByUserId = avatarByUserId + allResolved) }
                }
            } else if (memberAvatars.isNotEmpty()) {
                updateState { copy(avatarByUserId = avatarByUserId + memberAvatars) }
            }
        }
    }

    /**
     * Confirm an edit operation.
     */
    suspend fun confirmEdit(): Boolean {
        val editEvent = currentState.editingEvent ?: return false
        val newBody = currentState.input.trim()
        if (newBody.isBlank()) return false

        val outgoing = newBody.toOutgoingText()
        if (isMessageTooLong(outgoing?.body.orEmpty(), outgoing?.formattedBody, currentState.isRoomEncrypted)) {
            _events.send(Event.ShowError(getString(Res.string.message_too_long)))
            return false
        }
        val result = runSafe {
            service.edit(roomId, editEvent.eventId, outgoing?.body.orEmpty(), outgoing?.formattedBody)
        }

        if (result?.isSuccess == true) {
            updateState { copy(editingEvent = null, input = "") }
        } else {
            _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.failed_to_edit_message))))
        }

        return result?.isSuccess == true
    }

    /**
     * Delete a message.
     */
    suspend fun delete(event: MessageEvent): Boolean {
        if (event.eventId.isBlank()) {
            val txnId = event.txnId
            if (txnId.isNullOrBlank()) return false
            val cancelResult = service.cancelSend(roomId, txnId)
            if (cancelResult.isFailure) {
                _events.send(Event.ShowError(cancelResult.toUserMessage(getString(Res.string.failed_to_delete_message))))
            }
            return cancelResult.isSuccess
        }

        val result = runSafe { service.redact(roomId, event.eventId, null) }
        if (result?.isSuccess != true) {
            _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.failed_to_delete_message))))
        }
        return result?.isSuccess == true
    }

    /**
     * Send a new message to the thread.
     */
    suspend fun sendMessage(text: String): Boolean {
        val body = text.trim()
        if (body.isBlank()) return false

        val outgoing = body.toOutgoingText()
        if (isMessageTooLong(outgoing?.body.orEmpty(), outgoing?.formattedBody, currentState.isRoomEncrypted)) {
            _events.send(Event.ShowError(getString(Res.string.message_too_long)))
            return false
        }

        val replyToId = currentState.replyingTo?.eventId
        val replyingTo = currentState.replyingTo

        // Clear input immediately for better UX
        updateState {
            copy(
                replyingTo = null,
                input = ""
            )
        }

        // Send to server - message will appear via timeline diff
        val result = runSafe {
            service.port.sendThreadText(
                roomId,
                rootEventId,
                outgoing?.body.orEmpty(),
                replyToId,
                outgoing?.formattedBody
            )
        }

        if (result?.isSuccess != true) {
            updateState {
                val restoreDraft = input.isBlank()
                copy(
                    input = if (restoreDraft) text else input,
                    replyingTo = if (restoreDraft) replyingTo else this.replyingTo,
                )
            }
            _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.failed_to_send_message))))
        }

        return result?.isSuccess == true
    }

    private suspend fun String.toOutgoingText(): OutgoingText? =
        parseComposerMarkdown(this, currentState.imagePacks.flatMap { it.images })?.let { parsed ->
            val placeholder = if (parsed.hasSpoilers) SpoilerPlaceholder.upload(service.port) else null
            OutgoingText(composerToPlainBody(parsed, placeholder), parsed.formattedBody)
        }

    override fun onCleared() {
        super.onCleared()
        rootTimelineJob?.cancel()
        threadTimelineJob?.cancel()
    }
}

private fun List<MessageEvent>.sortedForThread(): List<MessageEvent> =
    sortedWith(
        compareBy<MessageEvent>(
            { !it.txnId.isNullOrBlank() },
            { if (it.txnId.isNullOrBlank()) it.timestampMs else 0L },
        )
    )
