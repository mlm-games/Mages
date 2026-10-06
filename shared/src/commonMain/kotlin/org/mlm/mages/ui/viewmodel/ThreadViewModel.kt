package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.viewModelScope
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
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
import org.mlm.mages.thumbKey
import org.mlm.mages.thumbToBridge
import org.mlm.mages.matrix.ImagePackSummary
import org.mlm.mages.matrix.ActionPresentation
import org.mlm.mages.matrix.LINK_PREVIEW_IMAGE_PX
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.TimelineDiff
import org.mlm.mages.matrix.allowsLinkPreviews
import org.mlm.mages.matrix.allowsMediaPreviews
import org.mlm.mages.emoji.RecentEmojiStore
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.storage.UserProfile
import org.mlm.mages.ui.ActionAvailabilityUi
import org.mlm.mages.ui.ActionPresentationUi
import org.mlm.mages.ui.MentionProfileUi
import org.mlm.mages.ui.toUi
import org.mlm.mages.ui.isEditableBy
import org.mlm.mages.ui.ThreadUiState
import org.mlm.mages.ui.components.composer.EmoteSuggestion
import org.mlm.mages.ui.components.composer.OutgoingText
import org.mlm.mages.ui.components.composer.SpoilerPlaceholder
import org.mlm.mages.ui.components.composer.composerToPlainBody
import org.mlm.mages.ui.components.composer.parseComposerMarkdown
import org.mlm.mages.ui.components.composer.emoteSuggestionsFrom
import org.mlm.mages.ui.components.core.emoteMxcUrisFrom
import org.mlm.mages.ui.components.core.firstLinkIn
import org.mlm.mages.ui.components.message.mxcReactionKeys
import org.mlm.mages.ui.components.message.reactionShortcodesFrom
import org.mlm.mages.ui.util.downloadNameHint
import org.mlm.mages.matrix.ReactionSummary
import org.mlm.mages.AttachmentKind
import kotlin.getValue
import org.jetbrains.compose.resources.getString
import mages.shared.generated.resources.Res

class ThreadViewModel(
    private val service: MatrixService,
    private val roomId: String,
    private val rootEventId: String,
    roomName: String = "",
    focusedEventId: String? = null,
) : BaseViewModel<ThreadUiState>(
    ThreadUiState(
        roomId = roomId,
        rootEventId = rootEventId,
        roomName = roomName,
        focusedEventId = focusedEventId,
    )
) {

    sealed class Event {
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
        data class NavigateToRoom(val roomId: String, val title: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val myUserId: String? = service.port.whoami()

    private var timelineJob: Job? = null

    // Track all events we've seen for this thread (for deduplication)
    private val seenItemIds = mutableSetOf<String>()
    private val replyThumbnailFetchInFlight = mutableSetOf<String>()

    private val settingsRepo: SettingsRepository<AppSettings> by inject()

    private val recentEmoji: RecentEmojiStore by inject()

    private val prefs = settingsRepo.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())


    init {
        preloadRoomMembers()
        loadImagePacks()
        loadRoomEncryption()
        observeTimeline()
        // Load initial thread data after a short delay to let timeline sync
        launch {
            delay(300)
            if (!currentState.hasInitialLoad) {
                loadInitialThread()
            }
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
    suspend fun emotePreview(thumbnailMxcUri: String?, mxcUrl: String): String? {
        val key = thumbnailMxcUri ?: mxcUrl
        currentState.emotePathByMxc[key]?.let { return it }
        val path = runSafe { service.port.mxcThumbnailToCache(key, 128, 128, false) } ?: return null
        updateState { copy(emotePathByMxc = emotePathByMxc + (key to path)) }
        return path
    }

    /**
     * Observe the room timeline and extract thread events in real-time.
     */
    private fun observeTimeline() {
        timelineJob?.cancel()
        timelineJob = viewModelScope.launch {
            service.timelineDiffs(roomId).collect { diff ->
                processTimelineDiff(diff)
            }
        }
    }

    private fun processTimelineDiff(diff: TimelineDiff<MessageEvent>) {
        when (diff) {
            is TimelineDiff.Reset -> {
                // Extract all events belonging to this thread
                val threadEvents = diff.items.filter { event ->
                    event.eventId == rootEventId || event.threadRootEventId == rootEventId
                }

                if (threadEvents.isNotEmpty()) {
                    processThreadEvents(threadEvents, isReset = true)
                }
            }

            is TimelineDiff.Append -> {
                val threadEvents = diff.items.filter { event ->
                    event.eventId == rootEventId || event.threadRootEventId == rootEventId
                }

                if (threadEvents.isNotEmpty()) {
                    processThreadEvents(threadEvents, isReset = false)
                }
            }

            is TimelineDiff.Prepend -> {
                val event = diff.item
                if (event.eventId == rootEventId || event.threadRootEventId == rootEventId) {
                    upsertSingleEvent(event)
                }
            }

            is TimelineDiff.UpdateByItemId -> {
                val event = diff.item
                if (event.eventId == rootEventId || event.threadRootEventId == rootEventId) {
                    updateSingleEvent(event)
                }
            }

            is TimelineDiff.UpsertByItemId -> {
                val event = diff.item
                if (event.eventId == rootEventId || event.threadRootEventId == rootEventId) {
                    upsertSingleEvent(event)
                }
            }

            is TimelineDiff.RemoveByItemId -> {
                removeSingleEvent(diff.itemId)
            }

            is TimelineDiff.Clear -> {
                // Timeline cleared - reset our state
                seenItemIds.clear()
                updateState {
                    copy(
                        rootMessage = null,
                        replies = emptyList(),
                        hasInitialLoad = false
                    )
                }
            }
        }
    }

    /**
     * Process a batch of thread events (from Reset or Append).
     */
    private fun processThreadEvents(events: List<MessageEvent>, isReset: Boolean) {
        val newEvents = if (isReset) {
            seenItemIds.clear()
            events
        } else {
            events.filter { it.itemId !in seenItemIds }
        }

        if (newEvents.isEmpty() && !isReset) return

        // Track seen items
        newEvents.forEach { seenItemIds.add(it.itemId) }
        prefetchSenderAvatars(events)
        prefetchReactionUserAvatars(events)
        prefetchReplyThumbnails(events)
        prefetchLinkPreviews(events)
        prefetchMentionProfiles(events)

        val hasOnlyRootSnapshot = isReset && events.none { it.eventId != rootEventId }

        updateState {
            // Find root message
            val newRoot = events.find { it.eventId == rootEventId }
            val updatedRoot = newRoot ?: rootMessage

            // Get all replies (events that are part of thread but not the root)
            val newReplies = events.filter {
                it.eventId != rootEventId && it.threadRootEventId == rootEventId
            }

            // Merge with existing replies
            val mergedReplies = if (isReset) {
                newReplies
            } else {
                (replies + newReplies)
                    .distinctBy { it.itemId }
            }.sortedBy { it.timestampMs }

            var thumbs = thumbByEvent
            events.forEach { ev ->
                thumbToBridge(ev, thumbs)?.let { thumbs = thumbs + (ev.eventId to it) }
            }

            copy(
                rootMessage = updatedRoot,
                replies = mergedReplies,
                thumbByEvent = thumbs,
                hasInitialLoad = !hasOnlyRootSnapshot,
                isLoading = false,
                error = null
            )
        }
    }

    /**
     * Update a single event in place.
     */
    private fun updateSingleEvent(event: MessageEvent) {
        seenItemIds.add(event.itemId)
        prefetchReplyThumbnails(listOf(event))
        prefetchLinkPreviews(listOf(event))

        updateState {
            val bridge = thumbToBridge(event, thumbByEvent)
            val base = when {
                event.eventId == rootEventId -> {
                    copy(rootMessage = event)
                }
                else -> {
                    val idx = replies.indexOfFirst { it.itemId == event.itemId }
                    if (idx >= 0) {
                        copy(replies = replies.toMutableList().apply { this[idx] = event })
                    } else {
                        this
                    }
                }
            }
            if (bridge == null) base
            else base.copy(thumbByEvent = thumbByEvent + (event.eventId to bridge))
        }
    }

    /**
     * Upsert a single event (update if exists, insert if not).
     */
    private fun upsertSingleEvent(event: MessageEvent) {
        seenItemIds.add(event.itemId)
        prefetchReplyThumbnails(listOf(event))
        prefetchLinkPreviews(listOf(event))

        updateState {
            val bridge = thumbToBridge(event, thumbByEvent)
            val base = when {
                event.eventId == rootEventId -> {
                    copy(rootMessage = event)
                }
                else -> {
                    val idx = replies.indexOfFirst { it.itemId == event.itemId }
                    if (idx >= 0) {
                        // Update existing
                        copy(replies = replies.toMutableList().apply { this[idx] = event })
                    } else {
                        val newReplies = (replies + event)
                            .distinctBy { it.itemId }
                            .sortedBy { it.timestampMs }
                        copy(replies = newReplies)
                    }
                }
            }
            if (bridge == null) base
            else base.copy(thumbByEvent = thumbByEvent + (event.eventId to bridge))
        }
    }

    /**
     * Remove an event by itemId.
     */
    private fun removeSingleEvent(itemId: String) {
        seenItemIds.remove(itemId)

        updateState {
            when {
                rootMessage?.itemId == itemId -> {
                    // Root was deleted - show as deleted
                    copy(rootMessage = rootMessage.copy(body = "[deleted]"))
                }
                else -> {
                    copy(replies = replies.filter { it.itemId != itemId })
                }
            }
        }
    }

    /**
     * Load thread via API as fallback/supplement to timeline data.
     */
    private fun loadInitialThread() {
        launch(onError = {
            val failedToLoadThreadFallback = getString(Res.string.failed_to_load_thread)
            updateState { copy(isLoading = false, error = it.message ?: failedToLoadThreadFallback) }
        }) {
            updateState { copy(isLoading = true, error = null) }

            val page = service.port.threadReplies(
                roomId = roomId,
                rootEventId = rootEventId,
                from = null,
                limit = 100,
                forward = true
            )

            val allMessages = page.messages.sortedBy { it.timestampMs }
            val root = allMessages.find { it.eventId == rootEventId }
            val replies = allMessages.filter { it.eventId != rootEventId }

            // Track all seen items
            allMessages.forEach { seenItemIds.add(it.itemId) }
            prefetchReplyThumbnails(allMessages)
            prefetchLinkPreviews(allMessages)

            updateState {
                // Merge with any existing data from timeline
                val mergedRoot = root ?: rootMessage
                val mergedReplies = (this.replies + replies)
                    .distinctBy { it.itemId }
                    .sortedBy { it.timestampMs }

                copy(
                    rootMessage = mergedRoot,
                    replies = mergedReplies,
                    nextBatch = page.nextBatch,
                    isLoading = false,
                    hasInitialLoad = true,
                    focusedEventMissing = focusedEventId != null &&
                            page.nextBatch == null &&
                            mergedRoot?.eventId != focusedEventId &&
                            mergedReplies.none { it.eventId == focusedEventId },
                )
            }
        }
    }

    /**
     * Load more (older) messages in the thread.
     */
    fun loadMore() {
        loadMoreInternal(markFocusedMissingWhenExhausted = currentState.focusedEventId != null)
    }

    fun clearFocusedEvent() {
        updateState { copy(focusedEventId = null, focusedEventMissing = false) }
    }

    private fun loadMoreInternal(markFocusedMissingWhenExhausted: Boolean) {
        val token = currentState.nextBatch
        if (token == null) {
            if (markFocusedMissingWhenExhausted && currentState.focusedEventId != null) {
                updateState { copy(focusedEventMissing = true) }
            }
            return
        }
        if (currentState.isLoading) return

        launch(onError = { e ->
            updateState { copy(isLoading = false) }
            launch { _events.send(Event.ShowError(e.message ?: getString(Res.string.failed_to_load_more_messages))) }
        }) {
            updateState { copy(isLoading = true) }

            val page = service.port.threadReplies(
                roomId = roomId,
                rootEventId = rootEventId,
                from = token,
                limit = 50,
                forward = false
            )

            val newReplies = page.messages.filter { it.eventId != rootEventId }

            // Track new items
            newReplies.forEach { seenItemIds.add(it.itemId) }
            prefetchReplyThumbnails(newReplies)
            prefetchLinkPreviews(newReplies)
        prefetchMentionProfiles(newReplies)

            updateState {
                val merged = (newReplies + replies)
                    .distinctBy { it.itemId }
                    .sortedBy { it.timestampMs }
                copy(
                    replies = merged,
                    nextBatch = page.nextBatch,
                    isLoading = false,
                    focusedEventMissing = markFocusedMissingWhenExhausted &&
                            focusedEventId != null &&
                            page.nextBatch == null &&
                            rootMessage?.eventId != focusedEventId &&
                            merged.none { it.eventId == focusedEventId },
                )
            }
        }
    }

    /**
     * React to a message with an emoji.
     */
    fun react(event: MessageEvent, emoji: String) {
        if (event.eventId.isBlank()) return
        recentEmoji.record(emoji)
        launch {
            runSafe { service.port.react(roomId, event.eventId, emoji, reactionShortcodeFor(emoji)) }
        }
    }

    /** MSC4027's shortcode for an mxc reaction key, when it is a known pack image. */
    fun reactionShortcodeFor(key: String): String? {
        if (!key.startsWith("mxc://")) return null
        return currentState.imagePacks
            .flatMap { it.images }
            .firstOrNull { it.mxcUrl == key }
            ?.shortcode
    }

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

    /**
     * Start replying to a message.
     */
    fun startReply(event: MessageEvent) {
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
        updateState { copy(editingEvent = event, input = event.body) }
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
        prefs.value.mediaPreviews.allowsMediaPreviews(isPrivateRoom = null)

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

    private fun prefetchLinkPreviews(events: List<MessageEvent>) {
        if (!linkPreviewsAllowed()) return
        events.forEach { ensureLinkPreview(it) }
    }

    /**
     * Offers the actions for [userId] rather than acting on them, so a tap on a mention pill picks
     * what to do instead of falling straight into a conversation.
     *
     * The member list is dropped for a room too large to hold, so the sender of a visible message
     * or a mentioned user is taken from the thread rather than from it.
     */
    fun selectMemberForAction(userId: String) {
        if (userId.isBlank() || userId == myUserId) return
        val member = knownMember(userId) ?: return
        clearSelectedMember()
        updateState { copy(selectedMemberForAction = member) }
        launch {
            val checking = getString(Res.string.checking_whether_you_can_start_a_conversation)
            if (currentState.selectedMemberForAction?.userId != userId) return@launch
            updateState {
                copy(
                    selectedMemberDmAction = ActionAvailabilityUi(
                        presentation = ActionPresentationUi.Disabled,
                        reason = checking,
                    ),
                )
            }
            refreshSelectedMemberActionState(userId)
        }
    }

    private fun knownMember(userId: String): MemberSummary? {
        currentState.roomMembers.firstOrNull { it.userId == userId }?.let { return it }
        currentState.mentionProfilesByUserId[userId]?.let {
            return MemberSummary(userId = it.userId, displayName = it.displayName)
        }
        val threadEvents = currentState.replies + listOfNotNull(currentState.rootMessage)
        val sender = threadEvents.lastOrNull { it.sender == userId } ?: return null
        val displayName = sender.senderDisplayName?.takeIf { it.isNotBlank() } ?: return null
        return MemberSummary(userId = userId, displayName = displayName)
    }

    private suspend fun refreshSelectedMemberActionState(userId: String) {
        val actionState = runSafe { service.port.memberActionState(roomId, userId) }
        if (currentState.selectedMemberForAction?.userId != userId) return
        updateState {
            copy(
                selectedMemberDmAction = actionState?.directMessage?.toUi() ?: ActionAvailabilityUi(),
                selectedMemberKickAction = actionState?.kick?.toUi() ?: ActionAvailabilityUi(),
                selectedMemberBanAction = actionState?.ban?.toUi() ?: ActionAvailabilityUi(),
                selectedMemberUnbanAction = actionState?.unban?.toUi() ?: ActionAvailabilityUi(),
            )
        }
    }

    fun clearSelectedMember() = updateState {
        copy(
            selectedMemberForAction = null,
            selectedMemberDmAction = ActionAvailabilityUi(),
            selectedMemberKickAction = ActionAvailabilityUi(),
            selectedMemberBanAction = ActionAvailabilityUi(),
            selectedMemberUnbanAction = ActionAvailabilityUi(),
        )
    }

    fun startDmWith(userId: String) {
        launch {
            val actionState = runSafe { service.port.memberActionState(roomId, userId) }
            if (actionState == null) {
                _events.send(Event.ShowError(getString(Res.string.failed_to_check_whether_a_conversation_can_be_started)))
                return@launch
            }

            if (actionState.directMessage.presentation != ActionPresentation.Enabled) {
                _events.send(
                    Event.ShowError(
                        actionState.directMessage.reason
                            ?: getString(Res.string.you_cannot_start_a_conversation_with_this_user)
                    )
                )
                return@launch
            }

            val dmRoomId = runSafe { service.port.ensureDmIfAllowed(roomId, userId) }
            if (dmRoomId == null) {
                _events.send(Event.ShowError(getString(Res.string.failed_to_start_conversation)))
                return@launch
            }

            val profile = runSafe { service.port.roomProfile(dmRoomId) }
            _events.send(Event.NavigateToRoom(dmRoomId, profile?.name ?: userId))
        }
    }

    fun kickUser(userId: String, reason: String?) {
        launch {
            val result = runSafe { service.port.kickUser(roomId, userId, reason) }
            if (result?.isSuccess == true) {
                _events.send(Event.ShowSuccess(getString(Res.string.user_kicked)))
            } else {
                _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.failed_to_kick_user))))
            }
        }
    }

    fun banUser(userId: String, reason: String?) {
        launch {
            val result = runSafe { service.port.banUser(roomId, userId, reason) }
            if (result?.isSuccess == true) {
                _events.send(Event.ShowSuccess(getString(Res.string.user_banned)))
            } else {
                _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.failed_to_ban_user))))
            }
        }
    }

    fun unbanUser(userId: String, reason: String?) {
        launch {
            val result = runSafe { service.port.unbanUser(roomId, userId, reason) }
            if (result?.isSuccess == true) {
                _events.send(Event.ShowSuccess(getString(Res.string.user_unbanned)))
            } else {
                _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.failed_to_unban_user))))
            }
        }
    }

    fun ignoreUser(userId: String) {
        launch {
            val result = runSafe { service.port.ignoreUser(userId) }
            if (result?.isSuccess == true) {
                _events.send(Event.ShowSuccess(getString(Res.string.user_ignored)))
            } else {
                _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.failed_to_ignore_user))))
            }
        }
    }

    fun openAvatarExternally(member: MemberSummary, onOpen: (String, String?) -> Unit) {
        openAvatarForViewing(
            service = service,
            userId = member.userId,
            fallbackAvatarUrl = member.avatarUrl,
            onOpen = onOpen,
            onError = { _events.send(Event.ShowError(getString(Res.string.download_failed))) },
        )
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
            service.profiles.rememberAll(seen + currentState.roomMembers.mapNotNull { member ->
                member.displayName?.let { member.userId to UserProfile(it, member.avatarUrl) }
            }.toMap())

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

    fun ensureThumbnail(event: MessageEvent) {
        if (!mediaPreviewsAllowed()) return
        val key = event.thumbKey ?: return
        if (currentState.thumbByEvent.containsKey(key)) return
        thumbToBridge(event, currentState.thumbByEvent)?.let {
            updateState { copy(thumbByEvent = thumbByEvent + (event.eventId to it)) }
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
                val path = attachment?.let { service.thumbnailToCache(it, 320, 320, true).getOrNull() }
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

        val replyToId = currentState.replyingTo?.eventId
        val replyingTo = currentState.replyingTo
        val latestEventId = if (replyToId == null && currentState.replies.isNotEmpty()) {
            currentState.replies.lastOrNull()?.eventId
        } else {
            null
        }

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
                latestEventId,
                outgoing?.formattedBody
            )
        }

        if (result?.isSuccess != true) {
            updateState { copy(input = text, replyingTo = replyingTo) }
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
        timelineJob?.cancel()
        seenItemIds.clear()
    }
}
