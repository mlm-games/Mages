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
import org.koin.core.component.inject
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.TimelineDiff
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.ThreadUiState
import kotlin.getValue

class ThreadViewModel(
    private val service: MatrixService,
    private val roomId: String,
    private val rootEventId: String,
    roomName: String = ""
) : BaseViewModel<ThreadUiState>(
    ThreadUiState(
        roomId = roomId,
        rootEventId = rootEventId,
        roomName = roomName
    )
) {

    sealed class Event {
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val myUserId: String? = service.port.whoami()

    private var timelineJob: Job? = null

    // Track all events we've seen for this thread (for deduplication)
    private val seenItemIds = mutableSetOf<String>()

    private val settingsRepo: SettingsRepository<AppSettings> by inject()

    private val prefs = settingsRepo.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())


    init {
        observeTimeline()
        // Load initial thread data after a short delay to let timeline sync
        launch {
            delay(300)
            if (!currentState.hasInitialLoad) {
                loadInitialThread()
            }
        }
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

            copy(
                rootMessage = updatedRoot,
                replies = mergedReplies,
                hasInitialLoad = true,
                isLoading = false,
                error = null
            )
        }

        // Fetch reactions for new events
        val eventIds = newEvents.mapNotNull { it.eventId.takeIf { id -> id.isNotBlank() } }
        if (eventIds.isNotEmpty()) {
            launch { fetchReactionsBatch(eventIds) }
        }
    }

    /**
     * Update a single event in place.
     */
    private fun updateSingleEvent(event: MessageEvent) {
        seenItemIds.add(event.itemId)

        updateState {
            when {
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
        }

        // Refresh reactions if event has an eventId
        if (event.eventId.isNotBlank()) {
            launch { refreshReactionsFor(event.eventId) }
        }
    }

    /**
     * Upsert a single event (update if exists, insert if not).
     */
    private fun upsertSingleEvent(event: MessageEvent) {
        seenItemIds.add(event.itemId)

        updateState {
            when {
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
        }

        if (event.eventId.isNotBlank()) {
            launch { refreshReactionsFor(event.eventId) }
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
            updateState { copy(isLoading = false, error = it.message ?: "Failed to load thread") }
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
                    hasInitialLoad = true
                )
            }

            // Fetch reactions for all messages
            val eventIds = allMessages.mapNotNull { it.eventId.takeIf { id -> id.isNotBlank() } }
            if (eventIds.isNotEmpty()) {
                fetchReactionsBatch(eventIds)
            }
        }
    }

    /**
     * Load more (older) messages in the thread.
     */
    fun loadMore() {
        val token = currentState.nextBatch ?: return
        if (currentState.isLoading) return

        launch(onError = {
            updateState { copy(isLoading = false) }
            launch { _events.send(Event.ShowError("Failed to load more messages")) }
        }) {
            updateState { copy(isLoading = true) }

            val page = service.port.threadReplies(
                roomId = roomId,
                rootEventId = rootEventId,
                from = token,
                limit = 50,
                forward = true
            )

            val newReplies = page.messages.filter { it.eventId != rootEventId }

            // Track new items
            newReplies.forEach { seenItemIds.add(it.itemId) }

            updateState {
                val merged = (newReplies + replies)
                    .distinctBy { it.itemId }
                    .sortedBy { it.timestampMs }
                copy(
                    replies = merged,
                    nextBatch = page.nextBatch,
                    isLoading = false
                )
            }

            // Fetch reactions for new messages
            val eventIds = newReplies.mapNotNull { it.eventId.takeIf { id -> id.isNotBlank() } }
            if (eventIds.isNotEmpty()) {
                fetchReactionsBatch(eventIds)
            }
        }
    }

    /**
     * React to a message with an emoji.
     */
    fun react(event: MessageEvent, emoji: String) {
        if (event.eventId.isBlank()) return
        launch {
            runSafe { service.port.react(roomId, event.eventId, emoji) }
            delay(300)
            refreshReactionsFor(event.eventId)
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

    /**
     * Confirm an edit operation.
     */
    suspend fun confirmEdit(): Boolean {
        val editEvent = currentState.editingEvent ?: return false
        val newBody = currentState.input.trim()
        if (newBody.isBlank()) return false

        val ok = runSafe { service.edit(roomId, editEvent.eventId, newBody) } ?: false

        if (ok) {
            updateState { copy(editingEvent = null, input = "") }
        } else {
            _events.send(Event.ShowError("Failed to edit message"))
        }

        return ok
    }

    /**
     * Delete a message.
     */
    suspend fun delete(event: MessageEvent): Boolean {
        if (event.eventId.isBlank()) return false

        val ok = runSafe { service.redact(roomId, event.eventId, null) } ?: false
        if (!ok) {
            _events.send(Event.ShowError("Failed to delete message"))
        }
        return ok
    }

    /**
     * Send a new message to the thread.
     */
    suspend fun sendMessage(text: String): Boolean {
        val body = text.trim()
        if (body.isBlank()) return false

        val replyToId = currentState.replyingTo?.eventId

        // Clear input immediately for better UX
        updateState {
            copy(
                replyingTo = null,
                input = ""
            )
        }

        // Send to server - message will appear via timeline diff
        val ok = runSafe {
            service.port.sendThreadText(roomId, rootEventId, body, replyToId)
        } ?: false

        if (!ok) {
            _events.send(Event.ShowError("Failed to send message"))
        }

        return ok
    }

    /**
     * Fetch reactions for multiple events in a batch.
     */
    private suspend fun fetchReactionsBatch(eventIds: List<String>) {
        val results = runSafe {
            service.port.reactionsBatch(roomId, eventIds)
        } ?: return

        updateState {
            copy(
                reactionChips = reactionChips.toMutableMap().apply {
                    results.forEach { (eventId, chips) ->
                        if (chips.isNotEmpty()) put(eventId, chips) else remove(eventId)
                    }
                }
            )
        }
    }

    /**
     * Refresh reactions for a single event.
     */
    private suspend fun refreshReactionsFor(eventId: String) {
        if (eventId.isBlank()) return
        val chips = runSafe { service.port.reactions(roomId, eventId) } ?: emptyList()
        updateState {
            copy(
                reactionChips = reactionChips.toMutableMap().apply {
                    if (chips.isNotEmpty()) put(eventId, chips) else remove(eventId)
                }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        timelineJob?.cancel()
        seenItemIds.clear()
    }
}