package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.koin.core.component.inject
import org.mlm.mages.*
import org.mlm.mages.matrix.*
import org.mlm.mages.platform.CallWebViewController
import org.mlm.mages.platform.Notifier
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.ForwardableRoom
import org.mlm.mages.ui.ActiveCallUi
import org.mlm.mages.ui.RoomUiState
import org.mlm.mages.ui.components.AttachmentData
import org.mlm.mages.ui.util.mimeToExtension

class RoomViewModel(
    private val service: MatrixService,
    private val savedStateHandle: SavedStateHandle
) : BaseViewModel<RoomUiState>(
    RoomUiState(
        roomId = savedStateHandle.get<String>("roomId") ?: "",
        roomName = savedStateHandle.get<String>("roomName") ?: "",
        roomAvatarUrl = savedStateHandle.get<String>("roomAvatarUrl") ?: "",
    )
) {
    constructor(
        service: MatrixService,
        roomId: String,
        roomName: String
    ) : this(
        service = service,
        savedStateHandle = SavedStateHandle(mapOf("roomId" to roomId, "roomName" to roomName))
    )

    // One-time events
    sealed class Event {
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
        data class NavigateToThread(val roomId: String, val eventId: String, val roomName: String) : Event()
        data class NavigateToRoom(val roomId: String, val name: String) : Event()
        data object NavigateBack : Event()

        data class ShareMessage(
            val text: String?,
            val filePath: String?,
            val mimeType: String?
        ) : Event()

        data class JumpToEvent(val eventId: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var typingToken: ULong? = null
    private var receiptsToken: ULong? = null
    private var ownReceiptToken: ULong? = null
    private var dmPeer: String? = null
    private var uploadJob: Job? = null
    private var typingJob: Job? = null
    private var hasTimelineSnapshot = false
    private var searchJob: Job? = null

    // Track which event IDs we've checked via API for additional thread replies
    // (beyond what's visible in timeline)
    private val checkedThreadRootsViaApi = mutableSetOf<String>()

    private var callWebViewController: CallWebViewController? = null

    private val settingsRepo: SettingsRepository<AppSettings> by inject()

    private val prefs = settingsRepo.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())


    init {
        initialize()
    }

    private fun initialize() {
        updateState { copy(myUserId = service.port.whoami()) }
        observeTimeline()
        observeTyping()
        observeOwnReceipt()
        observeLiveLocation()
        observeReceipts()
        loadNotificationMode()
        loadUpgradeInfo()

        launch {
            dmPeer = runSafe { service.port.dmPeerUserId(currentState.roomId) }
            settingsRepo.update { it.copy(lastOpenedRoomId = currentState.roomId) }

            val profile = runSafe { service.port.roomProfile(currentState.roomId) }
            if (profile != null) {
                updateState { copy(isDm = profile.isDm, roomAvatarUrl = profile.avatarUrl, roomName = profile.name) }

                profile.avatarUrl?.let { url ->
                    if (url.startsWith("mxc://")) {
                        val path = service.avatars.resolve(url, px = 96, crop = true)
                        if (path != null) updateState { copy(roomAvatarUrl = path) }
                    }
                }
            }
        }
    }

    //  UI Sheet Toggles

    fun showAttachmentPicker() = updateState { copy(showAttachmentPicker = true) }
    fun hideAttachmentPicker() = updateState { copy(showAttachmentPicker = false) }

    fun showPollCreator() = updateState { copy(showPollCreator = true, showAttachmentPicker = false) }
    fun hidePollCreator() = updateState { copy(showPollCreator = false) }

    fun showLiveLocation() = updateState { copy(showLiveLocation = true, showAttachmentPicker = false) }
    fun hideLiveLocation() = updateState { copy(showLiveLocation = false) }

    fun showNotificationSettings() = updateState { copy(showNotificationSettings = true) }
    fun hideNotificationSettings() = updateState { copy(showNotificationSettings = false) }

    fun showMembers() {
        updateState { copy(showMembers = true, isLoadingMembers = true) }
        loadMembers()
    }
    fun hideMembers() = updateState { copy(showMembers = false, selectedMemberForAction = null) }

    fun selectMemberForAction(member: MemberSummary) = updateState { copy(selectedMemberForAction = member) }
    fun clearSelectedMember() = updateState { copy(selectedMemberForAction = null) }

    fun showInviteDialog() = updateState { copy(showInviteDialog = true) }
    fun hideInviteDialog() = updateState { copy(showInviteDialog = false) }

    //  Message Input

    fun setInput(value: String) {
        updateState { copy(input = value) }

        // only network typing notices are gated
        if (!prefs.value.sendTypingIndicators) return

        typingJob?.cancel()
        typingJob = launch {
            if (value.isBlank()) {
                runSafe { service.port.setTyping(currentState.roomId, false) }
            } else {
                runSafe { service.port.setTyping(currentState.roomId, true) }
                delay(4000)
                runSafe { service.port.setTyping(currentState.roomId, false) }
            }
        }
    }

    fun send() {
        val s = currentState
        if (s.input.isBlank()) return

        launch {
            val text = s.input.trim()
            val replyTo = s.replyingTo

            val ok = if (replyTo != null) {
                service.reply(s.roomId, replyTo.eventId, text)
            } else {
                service.sendMessage(s.roomId, text)
            }

            if (ok) {
                updateState { copy(input = "", replyingTo = null) }
            } else {
                _events.send(Event.ShowError(if (replyTo != null) "Reply failed" else "Send failed"))
            }
        }
    }

    //  Reply/Edit

    fun startReply(event: MessageEvent) = updateState { copy(replyingTo = event) }
    fun cancelReply() = updateState { copy(replyingTo = null) }

    fun startEdit(event: MessageEvent) = updateState { copy(editing = event, input = event.body) }
    fun cancelEdit() = updateState { copy(editing = null, input = "") }

    fun confirmEdit() {
        val s = currentState
        val target = s.editing ?: return
        val newBody = s.input.trim()
        if (newBody.isBlank()) return

        launch {
            val ok = service.edit(s.roomId, target.eventId, newBody)
            if (ok) {
                updateState {
                    val idx = allEvents.indexOfFirst { it.eventId == target.eventId }
                    if (idx == -1) {
                        copy(editing = null, input = "")
                    } else {
                        val updated = allEvents[idx].copy(body = newBody)
                        val newAll = allEvents.toMutableList().also { it[idx] = updated }
                        copy(
                            allEvents = newAll,
                            events = newAll.withoutThreadReplies().dedupByItemId(),
                            editing = null,
                            input = ""
                        )
                    }
                }
            } else {
                _events.send(Event.ShowError("Edit failed"))
            }
        }
    }

    //  Reactions

    fun react(event: MessageEvent, emoji: String) {
        if (event.eventId.isBlank()) return
        launch {
            runSafe { service.port.react(currentState.roomId, event.eventId, emoji) }
            refreshReactionsFor(event.eventId)
        }
    }

    //  Delete/Retry

    fun delete(event: MessageEvent) {
        if (event.eventId.isBlank()) return
        launch {
            val ok = service.redact(currentState.roomId, event.eventId, null)
            if (!ok) {
                _events.send(Event.ShowError("Delete failed"))
            }
        }
    }

    fun retry(event: MessageEvent) {
        if (event.body.isBlank()) return
        launch {
            val triedPrecise = event.txnId?.let { txn ->
                service.retryByTxn(currentState.roomId, txn)
            } ?: false

            val ok = if (triedPrecise) true else service.sendMessage(currentState.roomId, event.body.trim())
            if (!ok) {
                _events.send(Event.ShowError("Retry failed"))
            }
        }
    }

    //  Pagination

    fun paginateBack() {
        val s = currentState
        if (s.isPaginatingBack || s.hitStart) return

        launch {
            updateState { copy(isPaginatingBack = true) }
            try {
                val hitStart = runSafe { service.paginateBack(s.roomId, 50) } ?: false
                updateState { copy(hitStart = hitStart || this.hitStart) }

                // After pagination, recompute thread counts from timeline
                delay(500) // Give time for timeline diffs to arrive
                recomputeThreadCountsFromTimeline()
            } finally {
                updateState { copy(isPaginatingBack = false) }
            }
        }
    }

    //  Read Receipts

    fun markReadHere(event: MessageEvent) {
        if (event.eventId.isBlank()) return
        if (!prefs.value.sendReadReceipts) return

        launch { service.markReadAt(event.roomId, event.eventId) }
    }

    //  Attachments

    fun sendAttachment(data: AttachmentData) {
        if (currentState.isUploadingAttachment) return

        updateState {
            copy(
                currentAttachment = data,
                isUploadingAttachment = true,
                attachmentProgress = 0f,
                showAttachmentPicker = false
            )
        }

        uploadJob = launch {
            val ok = service.sendAttachmentFromPath(
                roomId = currentState.roomId,
                path = data.path,
                mime = data.mimeType,
                filename = data.fileName
            ) { sent, total ->
                val denom = (total ?: data.sizeBytes).coerceAtLeast(1L).toFloat()
                val p = (sent.toFloat() / denom).coerceIn(0f, 1f)
                updateState { copy(attachmentProgress = p) }
            }

            updateState {
                copy(
                    isUploadingAttachment = false,
                    attachmentProgress = 0f,
                    currentAttachment = null
                )
            }

            if (!ok) {
                _events.send(Event.ShowError("Attachment upload failed"))
            }
        }
    }

    fun cancelAttachmentUpload() {
        uploadJob?.cancel()
        uploadJob = null
        updateState {
            copy(
                isUploadingAttachment = false,
                attachmentProgress = 0f,
                currentAttachment = null
            )
        }
    }

    fun openAttachment(event: MessageEvent, onOpen: (String, String?) -> Unit) {
        val a = event.attachment ?: return
        launch {
            val nameHint = event.body.takeIf { body ->
                body.isNotBlank() &&
                        body.contains('.') &&
                        !body.startsWith("mxc://") &&
                        !body.contains('\n') &&
                        body.length < 256
            } ?: run {
                val ext = mimeToExtension(a.mime)
                val base = event.eventId.ifBlank { "file" }
                "$base.$ext"
            }

            service.port.downloadAttachmentToCache(a, nameHint)
                .onSuccess { path ->
                    val f = java.io.File(path)
                    if (!f.exists() || f.length() == 0L) {
                        _events.send(
                            Event.ShowError("Downloaded file is missing or empty: $path")
                        )
                        return@onSuccess
                    }
                    onOpen(path, a.mime)
                }
                .onFailure { t ->
                    _events.send(
                        Event.ShowError(t.message ?: "Download failed")
                    )
                }
        }
    }

    fun shareMessage(event: MessageEvent) {
        launch {
            val text = event.body.takeIf { it.isNotBlank() }
            val attachment = event.attachment

            if (attachment == null) {
                _events.send(
                    Event.ShareMessage(
                        text = text,
                        filePath = null,
                        mimeType = null
                    )
                )
            } else {
                val nameHint = event.body.takeIf { body ->
                    body.isNotBlank() &&
                            body.contains('.') &&
                            !body.startsWith("mxc://") &&
                            !body.contains('\n') &&
                            body.length < 256
                } ?: run {
                    val ext = mimeToExtension(attachment.mime)
                    val base = event.eventId.ifBlank { "file" }
                    "$base.$ext"
                }

                service.port.downloadAttachmentToCache(attachment, nameHint)
                    .onSuccess { path ->
                        _events.send(
                            Event.ShareMessage(
                                text = null,
                                filePath = path,
                                mimeType = attachment.mime
                            )
                        )
                    }
                    .onFailure { t ->
                        _events.send(
                            Event.ShowError(t.message ?: "Failed to prepare share")
                        )
                    }
            }
        }
    }

    //  Live Location

    fun startLiveLocation(durationMinutes: Int) {
        launch {
            val durationMs = durationMinutes * 60 * 1000L
            val ok = service.port.startLiveLocationShare(currentState.roomId, durationMs)
            if (ok) {
                updateState { copy(showLiveLocation = false) }
                _events.send(Event.ShowSuccess("Location sharing started"))
            } else {
                _events.send(Event.ShowError("Failed to start location sharing"))
            }
        }
    }

    fun stopLiveLocation() {
        launch {
            val ok = service.port.stopLiveLocationShare(currentState.roomId)
            if (ok) {
                updateState { copy(showLiveLocation = false) }
                _events.send(Event.ShowSuccess("Location sharing stopped"))
            } else {
                _events.send(Event.ShowError("Failed to stop location sharing"))
            }
        }
    }

    val isCurrentlyShareingLocation: Boolean
        get() = currentState.liveLocationShares[currentState.myUserId]?.isLive == true

    //  Polls

    fun sendPoll(question: String, answers: List<String>) {
        val q = question.trim()
        val opts = answers.map { it.trim() }.filter { it.isNotBlank() }
        if (q.isBlank() || opts.size < 2) return

        launch {
            val ok = service.port.sendPoll(currentState.roomId, q, opts)
            if (ok) {
                updateState { copy(showPollCreator = false) }
            } else {
                _events.send(Event.ShowError("Failed to create poll"))
            }
        }
    }

    //  Notification Settings

    fun setNotificationMode(mode: RoomNotificationMode) {
        launch {
            val ok = service.port.setRoomNotificationMode(currentState.roomId, mode)
            if (ok) {
                updateState { copy(notificationMode = mode, showNotificationSettings = false) }
                _events.send(Event.ShowSuccess("Notification settings updated"))
            } else {
                _events.send(Event.ShowError("Failed to update notifications"))
            }
        }
    }

    private fun loadNotificationMode() {
        launch {
            updateState { copy(isLoadingNotificationMode = true) }
            val mode = runSafe { service.port.roomNotificationMode(currentState.roomId) }
            updateState {
                copy(
                    notificationMode = mode ?: RoomNotificationMode.AllMessages,
                    isLoadingNotificationMode = false
                )
            }
        }
    }

    //  Room Upgrade

    private fun loadUpgradeInfo() {
        launch {
            val successor = runSafe { service.port.roomSuccessor(currentState.roomId) }
            val predecessor = runSafe { service.port.roomPredecessor(currentState.roomId) }
            updateState { copy(successor = successor, predecessor = predecessor) }
        }
    }

    fun navigateToUpgradedRoom() {
        val successor = currentState.successor ?: return
        launch {
            _events.send(Event.NavigateToRoom(successor.roomId, "Upgraded Room"))
        }
    }

    fun navigateToPredecessorRoom() {
        val predecessor = currentState.predecessor ?: return
        launch {
            _events.send(Event.NavigateToRoom(predecessor.roomId, "Previous Room"))
        }
    }

    fun votePoll(pollEventId: String, poll: PollData, optionId: String) {
        launch {
            val currentSelections = poll.mySelections.toSet()
            val newSelections = if (poll.maxSelections == 1L) {
                if (currentSelections.contains(optionId)) emptyList() else listOf(optionId)
            } else {
                if (currentSelections.contains(optionId)) {
                    currentSelections - optionId
                } else {
                    currentSelections + optionId
                }
            }.toList()

            val ok = service.port.sendPollResponse(currentState.roomId, pollEventId, newSelections)
            if (!ok) {
                _events.send(Event.ShowError("Failed to submit vote"))
            }
        }
    }

    fun endPoll(pollEventId: String) {
        launch {
            val ok = service.port.sendPollEnd(currentState.roomId, pollEventId)
            if (!ok) {
                _events.send(Event.ShowError("Failed to end poll"))
            } else {
                _events.send(Event.ShowSuccess("Poll ended"))
            }
        }
    }


    fun showRoomSearch() = updateState { copy(showRoomSearch = true) }
    fun hideRoomSearch() = updateState {
        copy(
            showRoomSearch = false,
            roomSearchQuery = "",
            roomSearchResults = emptyList(),
            roomSearchNextOffset = null,
            hasRoomSearched = false
        )
    }

    fun setRoomSearchQuery(query: String) {
        updateState { copy(roomSearchQuery = query) }

        searchJob?.cancel()
        if (query.length >= 2) {
            searchJob = launch {
                delay(300)
                performRoomSearch(reset = true)
            }
        } else if (query.isEmpty()) {
            updateState { copy(roomSearchResults = emptyList(), hasRoomSearched = false) }
        }
    }

    fun performRoomSearch(reset: Boolean = true) {
        val query = currentState.roomSearchQuery.trim()
        if (query.length < 2) return

        searchJob?.cancel()
        searchJob = launch {
            updateState { copy(isRoomSearching = true) }

            val offset = if (reset) null else currentState.roomSearchNextOffset
            val page = runSafe {
                service.port.searchRoom(
                    roomId = currentState.roomId,
                    query = query,
                    limit = 30,
                    offset = offset
                )
            }

            if (page != null) {
                updateState {
                    copy(
                        isRoomSearching = false,
                        hasRoomSearched = true,
                        roomSearchResults = if (reset) page.hits else roomSearchResults + page.hits,
                        roomSearchNextOffset = page.nextOffset?.toInt()
                    )
                }
            } else {
                updateState { copy(isRoomSearching = false, hasRoomSearched = true) }
            }
        }
    }

    fun loadMoreRoomSearchResults() {
        if (currentState.isRoomSearching || currentState.roomSearchNextOffset == null) return
        performRoomSearch(reset = false)
    }

    fun jumpToSearchResult(hit: SearchHit) {
        hideRoomSearch()
        val eid = hit.eventId
        if (eid.isBlank()) return

        launch {
            _events.send(Event.JumpToEvent(eid))
        }
    }

    //  Members & Moderation

    private fun loadMembers() {
        launch {
            val members = runSafe { service.port.listMembers(currentState.roomId) } ?: emptyList()
            updateState { copy(members = members, isLoadingMembers = false) }

            members.forEach { m ->
                val mxc = m.avatarUrl ?: return@forEach
                if (!mxc.startsWith("mxc://")) return@forEach

                launch {
                    val path = service.avatars.resolve(mxc, px = 64, crop = true) ?: return@launch
                    updateState {
                        copy(
                            members = this.members.map { mm ->
                                if (mm.userId == m.userId) mm.copy(avatarUrl = path) else mm
                            }
                        )
                    }
                }
            }
        }
    }

    fun kickUser(userId: String, reason: String? = null) {
        launch {
            val ok = service.port.kickUser(currentState.roomId, userId, reason)
            if (ok) {
                updateState { copy(selectedMemberForAction = null) }
                loadMembers()
                _events.send(Event.ShowSuccess("User removed from room"))
            } else {
                _events.send(Event.ShowError("Failed to remove user"))
            }
        }
    }

    fun banUser(userId: String, reason: String? = null) {
        launch {
            val ok = service.port.banUser(currentState.roomId, userId, reason)
            if (ok) {
                updateState { copy(selectedMemberForAction = null) }
                loadMembers()
                _events.send(Event.ShowSuccess("User banned"))
            } else {
                _events.send(Event.ShowError("Failed to ban user"))
            }
        }
    }

    fun unbanUser(userId: String, reason: String? = null) {
        launch {
            val ok = service.port.unbanUser(currentState.roomId, userId, reason)
            if (ok) {
                updateState { copy(selectedMemberForAction = null) }
                loadMembers()
                _events.send(Event.ShowSuccess("User unbanned"))
            } else {
                _events.send(Event.ShowError("Failed to unban user"))
            }
        }
    }

    fun inviteUser(userId: String) {
        launch {
            val ok = service.port.inviteUser(currentState.roomId, userId)
            if (ok) {
                updateState { copy(showInviteDialog = false) }
                loadMembers()
                _events.send(Event.ShowSuccess("Invitation sent"))
            } else {
                _events.send(Event.ShowError("Failed to invite user"))
            }
        }
    }

    fun ignoreUser(userId: String) {
        launch {
            val ok = service.port.ignoreUser(userId)
            if (ok) {
                updateState { copy(selectedMemberForAction = null) }
                _events.send(Event.ShowSuccess("User ignored"))
            } else {
                _events.send(Event.ShowError("Failed to ignore user"))
            }
        }
    }

    fun startDmWith(userId: String) {
        launch {
            val dmId = runSafe { service.port.ensureDm(userId) }
            if (dmId != null) {
                updateState { copy(selectedMemberForAction = null, showMembers = false) }
                _events.send(Event.NavigateToRoom(dmId, userId))
            } else {
                _events.send(Event.ShowError("Failed to start conversation"))
            }
        }
    }

    fun startForward(event: MessageEvent) {
        updateState {
            copy(
                forwardingEvent = event,
                showForwardPicker = true,
                isLoadingForwardRooms = true,
                forwardSearchQuery = ""
            )
        }
        loadForwardableRooms()
    }

    fun cancelForward() {
        updateState {
            copy(
                forwardingEvent = null,
                showForwardPicker = false,
                forwardableRooms = emptyList(),
                forwardSearchQuery = ""
            )
        }
    }

    fun setForwardSearch(query: String) {
        updateState { copy(forwardSearchQuery = query) }
    }

    val filteredForwardRooms: List<ForwardableRoom>
        get() {
            val query = currentState.forwardSearchQuery.lowercase()
            return if (query.isBlank()) {
                currentState.forwardableRooms
            } else {
                currentState.forwardableRooms.filter { it.name.lowercase().contains(query) }
            }
        }

    fun forwardTo(targetRoomId: String) {
        val event = currentState.forwardingEvent ?: return

        launch {
            updateState { copy(showForwardPicker = false) }

            val success = forwardMessage(event, targetRoomId)

            if (success) {
                _events.send(Event.ShowSuccess("Message forwarded"))
                val targetName = currentState.forwardableRooms
                    .find { it.roomId == targetRoomId }?.name ?: "Room"
                _events.send(Event.NavigateToRoom(targetRoomId, targetName))
            } else {
                _events.send(Event.ShowError("Failed to forward message"))
            }

            updateState { copy(forwardingEvent = null, forwardableRooms = emptyList()) }
        }
    }

    fun onReturnFromThread(rootEventId: String) {
        // Refresh thread count for this specific root when returning from thread view
        launch {
            fetchThreadCountFromApi(rootEventId)
        }
    }

    private fun postProcessNewEvents(newEvents: List<MessageEvent>) {
        val visible = newEvents.filter { it.threadRootEventId == null }

        // Fetch reactions for recent events
        visible.takeLast(10).forEach { ev ->
            if (ev.eventId.isNotBlank()) {
                launch { refreshReactionsFor(ev.eventId) }
            }
        }

        // Recompute thread counts from all events in timeline
        recomputeThreadCountsFromTimeline()

        prefetchThumbnailsForEvents(visible.takeLast(8))
    }

    /**
     * Compute thread counts by looking at threadRootEventId in allEvents.
     * This counts how many events in the timeline reference each root event.
     */
    private fun recomputeThreadCountsFromTimeline() {
        val threadRootCounts = mutableMapOf<String, Int>()

        // Count all events that have a threadRootEventId
        currentState.allEvents.forEach { event ->
            val rootId = event.threadRootEventId
            if (!rootId.isNullOrBlank()) {
                threadRootCounts[rootId] = (threadRootCounts[rootId] ?: 0) + 1
            }
        }

        if (threadRootCounts.isNotEmpty()) {
            updateState {
                // Merge with existing counts, preferring higher counts
                // (API might have found more than timeline has loaded)
                val merged = threadCount.toMutableMap()
                threadRootCounts.forEach { (eventId, count) ->
                    val existing = merged[eventId] ?: 0
                    merged[eventId] = maxOf(existing, count)
                }
                copy(threadCount = merged)
            }

            // For roots we haven't checked via API yet, fetch to get accurate count
            val uncheckedRoots = threadRootCounts.keys.filter { it !in checkedThreadRootsViaApi }
            if (uncheckedRoots.isNotEmpty()) {
                launch {
                    uncheckedRoots.forEach { rootId ->
                        fetchThreadCountFromApi(rootId)
                    }
                }
            }
        }
    }

    /**
     * Fetch accurate thread count from API for a specific root event.
     */
    private suspend fun fetchThreadCountFromApi(rootEventId: String) {
        if (rootEventId.isBlank()) return
        if (rootEventId in checkedThreadRootsViaApi) return

        checkedThreadRootsViaApi.add(rootEventId)

        val summary = runSafe {
            service.port.threadSummary(
                roomId = currentState.roomId,
                rootEventId = rootEventId,
                perPage = 100,
                maxPages = 5
            )
        }

        if (summary != null && summary.count > 0) {
            updateState {
                copy(
                    threadCount = threadCount.toMutableMap().apply {
                        // Use API count as it's more accurate
                        put(rootEventId, summary.count.toInt())
                    }
                )
            }
        }
    }
    
    fun startCall(intent: CallIntent = CallIntent.StartCall) {
        if (currentState.activeCall != null) return

        launch {
            val roomId = currentState.roomId
            val callUrl = prefs.value.elementCallUrl.trim().ifBlank { null }

            val session = service.startCall(
                roomId = roomId,
                intent = intent,
                elementCallUrl = callUrl,
            ) { messageFromSdk ->
                callWebViewController?.sendToWidget(messageFromSdk)
            }

            if (session != null) {
                updateState {
                    copy(
                        activeCall = ActiveCallUi(
                            sessionId = session.sessionId,
                            widgetUrl = session.widgetUrl
                        )
                    )
                }
            } else {
                _events.send(Event.ShowError("Failed to start call"))
            }
        }
    }

    fun onCallWidgetMessage(message: String) {
        val sid = _state.value.activeCall?.sessionId ?: return
        service.sendCallWidgetMessage(sid, message)
    }

    fun attachCallWebViewController(controller: CallWebViewController?) {
        callWebViewController = controller
    }

    fun endCall() {
        _state.value.activeCall?.sessionId?.let { service.stopCall(it) }
        callWebViewController = null
        _state.update { it.copy(activeCall = null) }
    }

    private suspend fun forwardMessage(event: MessageEvent, targetRoomId: String): Boolean {
        return try {
            val attachment = event.attachment

            if (attachment != null) {
                service.port.sendExistingAttachment(
                    roomId = targetRoomId,
                    attachment = attachment,
                    body = event.body.takeIf { it.isNotBlank() && it != attachment.mxcUri }
                )
            } else {
                service.sendMessage(targetRoomId, event.body)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun loadForwardableRooms() {
        launch {
            val rooms = runSafe {
                service.port.listRooms()
            }?.filter {
                it.id != currentState.roomId
            }?.map { room ->
                ForwardableRoom(
                    roomId = room.id,
                    name = room.name,
                    avatarUrl = room.avatarUrl,
                    isDm = room.isDm,
                    lastActivity = 0L
                )
            }?.sortedByDescending { it.lastActivity } ?: emptyList()

            updateState {
                copy(forwardableRooms = rooms, isLoadingForwardRooms = false)
            }
        }
    }

    //  Thread Navigation

    fun openThread(event: MessageEvent) {
        if (event.eventId.isBlank()) {
            launch { _events.send(Event.ShowError("Cannot open thread for unsent message")) }
            return
        }
        launch {
            _events.send(Event.NavigateToThread(currentState.roomId, event.eventId, currentState.roomName))
        }
    }

    //  Private Helpers

    private fun observeTimeline() {
        Notifier.setCurrentRoom(currentState.roomId)

        viewModelScope.launch(Dispatchers.Default) {
            service.timelineDiffs(currentState.roomId)
                .buffer(capacity = Channel.BUFFERED)
                .collect { diff -> processDiff(diff) }
        }
    }

    private fun processDiff(diff: TimelineDiff<MessageEvent>) {
        when (diff) {
            is TimelineDiff.Reset -> {
                hasTimelineSnapshot = true
                val all = diff.items
                updateState {
                    copy(
                        allEvents = all,
                        events = all.withoutThreadReplies().dedupByItemId(),
                        hasTimelineSnapshot = true,
                    )
                }
                postProcessNewEvents(diff.items)
            }

            is TimelineDiff.Clear -> {
                updateState {
                    copy(
                        allEvents = emptyList(),
                        events = emptyList(),
                        hasTimelineSnapshot = false,
                    )
                }
            }

            is TimelineDiff.Append -> {
                updateState {
                    val newAll = allEvents + diff.items
                    copy(
                        allEvents = newAll,
                        events = newAll.withoutThreadReplies().dedupByItemId(),
                        hasTimelineSnapshot = hasTimelineSnapshot,
                    )
                }
                postProcessNewEvents(diff.items)
            }

            is TimelineDiff.UpdateByItemId -> {
                updateState {
                    val index = allEvents.indexOfFirst { it.itemId == diff.itemId }
                    if (index == -1) {
                        this
                    } else {
                        val mutable = allEvents.toMutableList()
                        mutable[index] = diff.item
                        val newAll = mutable.toList()
                        copy(
                            allEvents = newAll,
                            events = newAll.withoutThreadReplies().dedupByItemId(),
                            hasTimelineSnapshot = hasTimelineSnapshot,
                        )
                    }
                }
                postProcessNewEvents(listOf(diff.item))
            }

            is TimelineDiff.RemoveByItemId -> {
                updateState {
                    val newAll = allEvents.filter { it.itemId != diff.itemId }
                    if (newAll.size == allEvents.size) {
                        this
                    } else {
                        copy(
                            allEvents = newAll,
                            events = newAll.withoutThreadReplies().dedupByItemId(),
                            hasTimelineSnapshot = hasTimelineSnapshot,
                        )
                    }
                }
            }

            is TimelineDiff.UpsertByItemId -> {
                updateState {
                    val index = allEvents.indexOfFirst { it.itemId == diff.itemId }
                    val newAll = if (index == -1) {
                        val insertIndex = allEvents.indexOfFirst { it.timestampMs > diff.item.timestampMs }
                        if (insertIndex == -1) {
                            allEvents + diff.item
                        } else {
                            allEvents.toMutableList().apply { add(insertIndex, diff.item) }
                        }
                    } else {
                        allEvents.toMutableList().apply { this[index] = diff.item }
                    }
                    copy(
                        allEvents = newAll,
                        events = newAll.withoutThreadReplies().dedupByItemId(),
                        hasTimelineSnapshot = hasTimelineSnapshot,
                    )
                }
                postProcessNewEvents(listOf(diff.item))
            }
        }

        recomputeDerived()
    }

    private fun observeTyping() {
        typingToken?.let { service.stopTypingObserver(it) }
        typingToken = service.observeTyping(currentState.roomId) { names ->
            updateState { copy(typingNames = names) }
        }
    }

    private fun observeReceipts() {
        receiptsToken?.let { service.port.stopReceiptsObserver(it) }
        receiptsToken = service.port.observeReceipts(currentState.roomId, object : ReceiptsObserver {
            override fun onChanged() {
                recomputeReadStatuses()
            }
        })
    }

    private fun observeOwnReceipt() {
        launch {
            runSafe { service.port.ownLastRead(currentState.roomId) }
                ?.let { (_, ts) -> updateState { copy(lastReadTs = ts) } }
        }

        ownReceiptToken?.let { service.port.stopReceiptsObserver(it) }
        ownReceiptToken = service.port.observeOwnReceipt(currentState.roomId, object : ReceiptsObserver {
            override fun onChanged() {
                launch {
                    runSafe { service.port.ownLastRead(currentState.roomId) }
                        ?.let { (_, ts) -> updateState { copy(lastReadTs = ts) } }
                }
            }
        })
    }

    private fun observeLiveLocation() {
        val token = service.port.observeLiveLocation(currentState.roomId) { shares ->
            updateState { copy(liveLocationShares = shares.associateBy { it.userId }) }

            val active = shares.filter { it.isLive }.map { it.userId }.toSet()
            if (active.isNotEmpty()) {
                ensureAvatarsForUsers(active)
            }
        }
        updateState { copy(liveLocationSubToken = token) }
    }

    private fun ensureAvatarsForUsers(userIds: Set<String>) {
        launch {
            if (currentState.members.isEmpty()) {
                val members = runSafe { service.port.listMembers(currentState.roomId) } ?: emptyList()
                updateState { copy(members = members) }
            }

            val targets = currentState.members.filter { it.userId in userIds }
            targets.forEach { m ->
                val mxc = m.avatarUrl ?: return@forEach
                if (!mxc.startsWith("mxc://")) return@forEach

                val path = service.avatars.resolve(mxc, px = 48, crop = true) ?: return@forEach
                updateState {
                    copy(
                        members = this.members.map { mm ->
                            if (mm.userId == m.userId) mm.copy(avatarUrl = path) else mm
                        }
                    )
                }
            }
        }
    }

    private fun recomputeDerived() {
        val s = currentState
        val me = s.myUserId ?: return

        if (s.events.isEmpty()) {
            updateState { copy(lastIncomingFromOthersTs = null, lastOutgoingRead = false) }
            return
        }

        val lastIncoming = s.events.asSequence().filter { it.sender != me }.maxOfOrNull { it.timestampMs }
        updateState { copy(lastIncomingFromOthersTs = lastIncoming) }

        if (s.isDm) recomputeReadStatuses()
    }

    private fun recomputeReadStatuses() {
        val s = currentState
        if (!s.isDm) return

        val me = s.myUserId ?: return
        val lastOutgoing = s.events.lastOrNull { it.sender == me } ?: run {
            updateState { copy(lastOutgoingRead = false) }
            return
        }

        val peer = dmPeer ?: return
        launch {
            val read = runSafe {
                service.port.isEventReadBy(s.roomId, lastOutgoing.eventId, peer)
            } ?: false
            updateState { copy(lastOutgoingRead = read) }
        }
    }

    private fun prefetchThumbnailsForEvents(events: List<MessageEvent>) {
        events.forEach { ev ->
            val a = ev.attachment ?: return@forEach
            if (a.kind != AttachmentKind.Image && a.kind != AttachmentKind.Video && a.thumbnailMxcUri == null) return@forEach
            if (currentState.thumbByEvent.containsKey(ev.eventId)) return@forEach
            if (ev.eventId.isBlank()) return@forEach

            launch {
                service.thumbnailToCache(a, 320, 320, true).onSuccess { path ->
                    updateState {
                        copy(thumbByEvent = thumbByEvent + (ev.eventId to path))
                    }
                }
            }
        }
    }

    private suspend fun refreshReactionsFor(eventId: String) {
        if (eventId.isBlank()) return
        val chips = runSafe { service.port.reactions(currentState.roomId, eventId) } ?: emptyList()
        updateState {
            copy(reactionChips = reactionChips.toMutableMap().apply {
                if (chips.isNotEmpty()) put(eventId, chips) else remove(eventId)
            })
        }
    }

    private fun List<MessageEvent>.withoutThreadReplies(): List<MessageEvent> =
        this.filter { it.threadRootEventId == null }

    private fun List<MessageEvent>.dedupByItemId(): List<MessageEvent> =
        distinctBy { it.itemId }

    override fun onCleared() {
        super.onCleared()
        checkedThreadRootsViaApi.clear()
        Notifier.setCurrentRoom(null)
        typingToken?.let { service.stopTypingObserver(it) }
        receiptsToken?.let { service.port.stopReceiptsObserver(it) }
        ownReceiptToken?.let { service.port.stopReceiptsObserver(it) }
        currentState.liveLocationSubToken?.let { service.port.stopObserveLiveLocation(it) }
    }
}