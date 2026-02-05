package org.mlm.mages.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.SendState
import org.mlm.mages.platform.*
import org.mlm.mages.ui.components.AttachmentData
import org.mlm.mages.ui.components.RoomUpgradeBanner
import org.mlm.mages.ui.components.attachment.AttachmentPicker
import org.mlm.mages.ui.components.attachment.AttachmentProgress
import org.mlm.mages.ui.components.composer.ActionBanner
import org.mlm.mages.ui.components.composer.MessageComposer
import org.mlm.mages.ui.components.core.*
import org.mlm.mages.ui.components.dialogs.InviteUserDialog
import org.mlm.mages.ui.components.dialogs.ReportContentDialog
import org.mlm.mages.ui.components.message.MessageBubble
import org.mlm.mages.ui.components.message.MessageStatusLine
import org.mlm.mages.ui.components.message.SeenByChip
import org.mlm.mages.ui.components.sheets.*
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatDate
import org.mlm.mages.ui.util.formatTime
import org.mlm.mages.ui.util.formatTypingText
import org.mlm.mages.ui.viewmodel.RoomViewModel
import java.io.File
import java.nio.file.Files

@Suppress("NewApi")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    viewModel: RoomViewModel,
    initialScrollToEventId: String? = null,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    onNavigateToRoom: (roomId: String, name: String) -> Unit,
    onNavigateToThread: (roomId: String, eventId: String, roomName: String) -> Unit,
    onStartCall: () -> Unit,
    onOpenForwardPicker: (sourceRoomId: String, eventIds: List<String>) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val shareHandler = rememberShareHandler()
    var progressText by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var pendingJumpEventId by rememberSaveable(initialScrollToEventId) {
        mutableStateOf(initialScrollToEventId)
    }
    var jumpAttempts by remember { mutableIntStateOf(0) }

    val openExternal = rememberFileOpener()

    val picker = rememberFilePicker { data ->
        if (data != null) viewModel.sendAttachment(data)
        viewModel.hideAttachmentPicker()
    }

    var isDragging by remember { mutableStateOf(false) }
    var sheetEvent by remember { mutableStateOf<MessageEvent?>(null) }

    var didInitialScroll by rememberSaveable { mutableStateOf(false) }


    val events = state.events

    // you always have exactly 1 header item (load_earlier OR start_of_conversation)
    fun listIndexForEventIndex(eventIndex: Int): Int = eventIndex + 1
    fun lastListIndex(): Int = if (events.isEmpty()) 0 else listIndexForEventIndex(events.lastIndex)


    val isNearBottom by remember(listState, events) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            events.isNotEmpty() && lastVisible >= lastListIndex() - 3
        }
    }

    val lastOutgoingIndex = remember(events, state.myUserId) {
        if (state.myUserId == null) -1 else events.indexOfLast { it.sender == state.myUserId }
    }

    val seenByNames = remember(events, lastOutgoingIndex, state.myUserId) {
        if (lastOutgoingIndex >= 0 && state.myUserId != null) {
            events.drop(lastOutgoingIndex + 1)
                .filter { it.sender != state.myUserId }
                .map { it.sender }
                .distinct()
                .map { sender -> sender.substringAfter('@').substringBefore(':').ifBlank { sender } }
        } else emptyList()
    }

    LaunchedEffect(state.hasTimelineSnapshot, state.events.size, pendingJumpEventId) {
        if (!state.hasTimelineSnapshot || state.events.isEmpty()) return@LaunchedEffect
        if (pendingJumpEventId != null) return@LaunchedEffect

        if (!didInitialScroll &&
            listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset == 0
        ) {
            listState.scrollToItem(lastListIndex())
            didInitialScroll = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is RoomViewModel.Event.ShowError -> {
                    progressText = null
                    snackbarHostState.showSnackbar(event.message)
                }
                is RoomViewModel.Event.ShowSuccess -> {
                    progressText = null
                    snackbarHostState.showSnackbar(event.message)
                }
                is RoomViewModel.Event.NavigateToThread -> {
                    onNavigateToThread(event.roomId, event.eventId, event.roomName)
                }
                is RoomViewModel.Event.NavigateToRoom -> {
                    onNavigateToRoom(event.roomId, event.name)
                }
                is RoomViewModel.Event.NavigateBack -> {
                    onBack()
                }
                is RoomViewModel.Event.ShareMessage -> {
                    shareHandler(
                        ShareContent(
                            text = event.text,
                            filePath = event.filePath,
                            mimeType = event.mimeType
                        )
                    )
                }
                is RoomViewModel.Event.JumpToEvent -> {
                    pendingJumpEventId = event.eventId
                }
                is RoomViewModel.Event.ShareContentEvent -> {
                    progressText = null
                    shareHandler(event.content)
                }

                is RoomViewModel.Event.OpenForwardPicker -> {
                    onOpenForwardPicker(event.sourceRoomId, event.eventIds)
                }

                is RoomViewModel.Event.ShowProgress -> {
                    progressText = "${event.label} ${event.current}/${event.total}"
                }
            }
        }
    }

    LaunchedEffect(events.lastOrNull()?.itemId, isNearBottom) {
        val last = events.lastOrNull() ?: return@LaunchedEffect
        if (isNearBottom) viewModel.markReadHere(last)
    }

    LaunchedEffect(events.size) {
        if (isNearBottom && events.isNotEmpty()) {
            listState.animateScrollToItem(lastListIndex())
        }
    }

    LaunchedEffect(
        pendingJumpEventId,
        state.hasTimelineSnapshot,
        state.events.size,
        state.hitStart,
        state.isPaginatingBack
    ) {
        val target = pendingJumpEventId ?: return@LaunchedEffect
        if (!state.hasTimelineSnapshot) return@LaunchedEffect
        if (state.events.isEmpty()) return@LaunchedEffect

        val idx = state.events.indexOfFirst { it.eventId == target }
        if (idx >= 0) {
            listState.scrollToItem(listIndexForEventIndex(idx))
            pendingJumpEventId = null
            jumpAttempts = 0
            return@LaunchedEffect
        }

        // Not found yet → back paginate until we find it, but don’t loop forever
        if (!state.hitStart && !state.isPaginatingBack && jumpAttempts < 30) {
            jumpAttempts++
            viewModel.paginateBack()
        } else if (state.hitStart || jumpAttempts >= 30) {
            pendingJumpEventId = null
            jumpAttempts = 0
            snackbarHostState.showSnackbar("Couldn’t find that message in loaded history.")
        }
    }

    Scaffold(
        topBar = {
            if (state.isSelectionMode) {
                SelectionTopBar(
                    selectedCount = state.selectedEventIds.size,
                    onClearSelection = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAllVisible
                )
            } else {
                RoomTopBar(
                    roomName = state.roomName,
                    roomId = state.roomId,
                    avatarUrl = state.roomAvatarUrl,
                    typingNames = state.typingNames,
                    isOffline = state.isOffline,
                    onBack = onBack,
                    onOpenInfo = onOpenInfo,
                    onOpenNotifications = viewModel::showNotificationSettings,
                    onOpenMembers = viewModel::showMembers,
                    onOpenSearch = viewModel::showRoomSearch,
                    onStartCall = onStartCall,
                    hasActiveCall = state.hasActiveCallForRoom,
                )
            }
        },
        bottomBar = {
            Column {
                if (progressText != null) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(
                            text = progressText!!,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (state.isSelectionMode) {
                    SelectionBottomBar(
                        onShare = viewModel::shareSelected,
                        onForward = viewModel::forwardSelected,
                        onDelete = viewModel::deleteSelected,
                    )
                } else {
                    RoomBottomBar(
                        state = state,
                        onSetInput = viewModel::setInput,
                        onSend = {
                            if (state.editing != null) viewModel.confirmEdit()
                            else viewModel.send()
                        },
                        onCancelReply = viewModel::cancelReply,
                        onCancelEdit = viewModel::cancelEdit,
                        onAttach = viewModel::showAttachmentPicker,
                        onCancelUpload = viewModel::cancelAttachmentUpload,
                    )
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !isNearBottom,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(lastListIndex().coerceAtLeast(0))
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, "Scroll to bottom")
                    Spacer(Modifier.width(8.dp))
                    Text("Jump to bottom")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->

        // File Drop Zone
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .fileDrop(
                    enabled = true,
                    onDragEnter = { isDragging = true },
                    onDragExit = {
                        isDragging = false },
                    onDrop = { paths ->
                        isDragging = false
                        paths.firstOrNull()?.let { path ->
                            try {
                                val file = File(path)
                                if (file.exists()) {
                                    val mime = Files.probeContentType(file.toPath()) ?: "application/octet-stream"
                                    viewModel.sendAttachment(
                                        AttachmentData(
                                            path = path,
                                            fileName = file.name,
                                            mimeType = mime,
                                            sizeBytes = file.length()
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                )
        ) {

            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Room upgrade banner
                RoomUpgradeBanner(
                    successor = state.successor,
                    predecessor = state.predecessor,
                    onNavigateToRoom = { roomId -> onNavigateToRoom(roomId, "Room") }
                )

                // Message list
                Box(modifier = Modifier.weight(1f)) {
                    if (!state.hasTimelineSnapshot) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            reverseLayout = false
                        ) {
                            // Always show load more button at top (even when empty)
                            if (!state.hitStart) {
                                item(key = "load_earlier") {
                                    LoadEarlierButton(
                                        isLoading = state.isPaginatingBack,
                                        onClick = viewModel::paginateBack
                                    )
                                }
                            } else {
                                item(key = "start_of_conversation") {
                                    StartOfConversationChip()
                                }
                            }

                            if (events.isEmpty()) {
                                // Show empty state as a list item so load more stays visible
                                item(key = "empty_state") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 64.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        EmptyState(
                                            icon = Icons.Default.ChatBubbleOutline,
                                            title = "No messages yet",
                                            subtitle = "Send a message to start the conversation"
                                        )
                                    }
                                }
                            } else {
                                itemsIndexed(events, key = { _, e -> e.itemId }) { index, event ->
                                    MessageItem(
                                        event = event,
                                        index = index,
                                        events = events,
                                        state = state,
                                        lastOutgoingIndex = lastOutgoingIndex,
                                        seenByNames = seenByNames,
                                        onLongPress = { sheetEvent = event },
                                        onReact = { emoji -> viewModel.react(event, emoji) },
                                        onOpenAttachment = {
                                            viewModel.openAttachment(event) { path, mime ->
                                                openExternal(path, mime)
                                            }
                                        },
                                        onOpenThread = { viewModel.openThread(event) },
                                        viewModel
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Drag overlay
            if (isDragging) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = "Drop file to send",
                                modifier = Modifier.padding(24.dp),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    }

    //  Sheets & Dialogs 

    if (state.showAttachmentPicker) {
        AttachmentPicker(
            onPickImage = { picker.pick("image/*") },
            onPickVideo = { picker.pick("video/*") },
            onPickDocument = { picker.pick("*/*") },
            onDismiss = viewModel::hideAttachmentPicker,
            onCreatePoll = viewModel::showPollCreator,
            // TODO: Add Live Location sharing after Element X implements it (same org's team)
            // onShareLocation = viewModel::showLiveLocation
        )
    }

    if (state.showPollCreator) {
        PollCreatorSheet(
            onCreatePoll = viewModel::sendPoll,
            onDismiss = viewModel::hidePollCreator
        )
    }

    // if (state.showLiveLocation) {
    //     LiveLocationSheet(
    //         isCurrentlySharing = viewModel.isCurrentlyShareingLocation,
    //         onStartSharing = viewModel::startLiveLocation,
    //         onStopSharing = viewModel::stopLiveLocation,
    //         onDismiss = viewModel::hideLiveLocation
    //     )
    // }

    if (state.showNotificationSettings) {
        RoomNotificationSheet(
            currentMode = state.notificationMode,
            isLoading = state.isLoadingNotificationMode,
            onModeChange = viewModel::setNotificationMode,
            onDismiss = viewModel::hideNotificationSettings
        )
    }

    if (state.showMembers) {
        MemberListSheet(
            members = state.members,
            isLoading = state.isLoadingMembers,
            myUserId = state.myUserId,
            onDismiss = viewModel::hideMembers,
            onMemberClick = viewModel::selectMemberForAction,
            onInvite = viewModel::showInviteDialog
        )
    }

    state.selectedMemberForAction?.let { member ->
        MemberActionsSheet(
            member = member,
            onDismiss = viewModel::clearSelectedMember,
            onStartDm = { viewModel.startDmWith(member.userId) },
            onKick = { reason -> viewModel.kickUser(member.userId, reason) },
            onBan = { reason -> viewModel.banUser(member.userId, reason) },
            onUnban = { reason -> viewModel.unbanUser(member.userId, reason) },
            onIgnore = { viewModel.ignoreUser(member.userId) },
            canModerate = state.canKick || state.canBan,
            isBanned = member.membership == "ban"
        )
    }

    if (state.showInviteDialog) {
        InviteUserDialog(
            onInvite = viewModel::inviteUser,
            onDismiss = viewModel::hideInviteDialog
        )
    }

    sheetEvent?.let { event ->
        val isMine = event.sender == state.myUserId
        val isPinned = event.eventId in state.pinnedEventIds
        MessageActionSheet(
            event = event,
            isMine = isMine,
            canDeleteOthers = state.canRedactOthers,
            canPin = state.canPin,
            isPinned = isPinned,
            onDismiss = { sheetEvent = null },
            onReply = { viewModel.startReply(event); sheetEvent = null },
            onEdit = { viewModel.startEdit(event); sheetEvent = null },
            onDelete = { viewModel.delete(event); sheetEvent = null },
            onPin = { viewModel.pinEvent(event) },
            onUnpin = { viewModel.unpinEvent(event) },
            onReport = { viewModel.showReportDialog(event) },
            onReact = { emoji -> viewModel.react(event, emoji) },
            onMarkReadHere = { viewModel.markReadHere(event); sheetEvent = null },
            onReplyInThread = { viewModel.openThread(event); sheetEvent = null },
            onShare = { viewModel.shareMessage(event) },
            onForward = { viewModel.startForward(event); sheetEvent = null },
            onSelect = { viewModel.enterSelectionMode(event.eventId) },
        )
    }

    if (state.showForwardPicker && state.forwardingEvent != null) {
        RoomPickerSheet(
            event = state.forwardingEvent!!,
            rooms = viewModel.filteredForwardRooms,
            isLoading = state.isLoadingForwardRooms,
            searchQuery = state.forwardSearchQuery,
            onSearchChange = viewModel::setForwardSearch,
            onRoomSelected = viewModel::forwardTo,
            onDismiss = viewModel::cancelForward
        )
    }
    if (state.showRoomSearch) {
        RoomSearchSheet(
            query = state.roomSearchQuery,
            isSearching = state.isRoomSearching,
            results = state.roomSearchResults,
            hasSearched = state.hasRoomSearched,
            onQueryChange = viewModel::setRoomSearchQuery,
            onSearch = { viewModel.performRoomSearch(reset = true) },
            onResultClick = { hit -> viewModel.jumpToSearchResult(hit) },
            onLoadMore = viewModel::loadMoreRoomSearchResults,
            hasMore = state.roomSearchNextOffset != null,
            onDismiss = viewModel::hideRoomSearch
        )
    }

    if (state.pinnedEventIds.isNotEmpty()) {
        PinnedMessageBanner(
            pinnedEventIds = state.pinnedEventIds,
            events = state.allEvents,
            onViewAll = viewModel::showPinnedMessagesSheet,
        )
    }

    if (state.showPinnedMessagesSheet) {
        PinnedMessagesSheet(
            pinnedEventIds = state.pinnedEventIds,
            events = state.allEvents,
            onEventClick = { event -> 
                viewModel.jumpToEvent(event.eventId)
                viewModel.hidePinnedMessagesSheet()
            },
            onUnpin = { viewModel.unpinEvent(it) },
            onDismiss = viewModel::hidePinnedMessagesSheet
        )
    }

    if (state.showReportDialog && state.reportingEvent != null) {
        ReportContentDialog(
            event = state.reportingEvent!!,
            onReport = { reason, blockUser -> 
                viewModel.reportContent(state.reportingEvent!!, reason, blockUser)
            },
            onDismiss = viewModel::hideReportDialog
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomTopBar(
    roomName: String,
    roomId: String,
    avatarUrl: String?,
    typingNames: List<String>,
    isOffline: Boolean,
    hasActiveCall: Boolean,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenMembers: () -> Unit,
    onOpenSearch: () -> Unit,
    onStartCall: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Column {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Avatar(
                                    name = roomName,
                                    avatarPath = avatarUrl,
                                    size = 40.dp
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(roomName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            AnimatedContent(
                                targetState = typingNames.isNotEmpty(),
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "typing"
                            ) { hasTyping ->
                                if (hasTyping) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TypingDots()
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            formatTypingText(typingNames),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            overflow = TextOverflow.Ellipsis,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onStartCall) {
                        if (hasActiveCall) {
                            Icon(Icons.AutoMirrored.Filled.CallMerge, "Join call")
                        } else Icon(Icons.Default.Call, "Start call")
                    }
                    IconButton(onClick = onOpenNotifications) {
                        Icon(Icons.Default.Notifications, "Notifications")
                    }
                    IconButton(onClick = onOpenMembers) {
                        Icon(Icons.Default.People, "Members")
                    }
                    IconButton(onClick = onOpenInfo) {
                        Icon(Icons.Default.Info, "Room info")
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Default.Search, "Search")
                    }
                }
            )

            AnimatedVisibility(visible = isOffline) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Offline - Messages will be queued",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = Spacing.lg)
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomBottomBar(
    state: org.mlm.mages.ui.RoomUiState,
    onSetInput: (String) -> Unit,
    onSend: () -> Unit,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit,
    onAttach: () -> Unit,
    onCancelUpload: () -> Unit,
) {
    Column(
        modifier = Modifier.navigationBarsPadding()
    ) {
        ActionBanner(
            replyingTo = state.replyingTo,
            editing = state.editing,
            onCancelReply = onCancelReply,
            onCancelEdit = onCancelEdit
        )

        if (state.isUploadingAttachment && state.currentAttachment != null) {
            AttachmentProgress(
                fileName = state.currentAttachment.fileName,
                progress = state.attachmentProgress,
                totalSize = state.currentAttachment.sizeBytes,
                onCancel = onCancelUpload
            )
        }

        MessageComposer(
            value = state.input,
            enabled = true,
            isOffline = state.isOffline,
            replyingTo = state.replyingTo,
            editing = state.editing,
            currentAttachment = state.currentAttachment,
            isUploadingAttachment = state.isUploadingAttachment,
            onValueChange = onSetInput,
            onSend = onSend,
            onCancelReply = onCancelReply,
            onCancelEdit = onCancelEdit,
            onAttach = onAttach,
            onCancelUpload = onCancelUpload
        )
    }
}

@Composable
private fun LoadEarlierButton(isLoading: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            OutlinedButton(onClick = onClick) {
                Text("Load earlier messages")
            }
        }
    }
}

@Composable
private fun StartOfConversationChip() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        AssistChip(
            onClick = {},
            enabled = false,
            label = { Text("Beginning of conversation") }
        )
    }
}

@Composable
private fun MessageItem(
    event: MessageEvent,
    index: Int,
    events: List<MessageEvent>,
    state: org.mlm.mages.ui.RoomUiState,
    lastOutgoingIndex: Int,
    seenByNames: List<String>,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit,
    onOpenAttachment: () -> Unit,
    onOpenThread: () -> Unit,
    viewModel: RoomViewModel
) {
    val timestamp = event.timestampMs

    val eventDate = formatDate(timestamp)
    val prevDate = events.getOrNull(index - 1)?.let { formatDate(it.timestampMs) }

    // Date header
    if (prevDate != eventDate) {
        DateHeader(eventDate)
    }

    // Unread divider
    val lastReadTs = state.lastReadTs
    val myId = state.myUserId
    val isFromMe = myId != null && event.sender == myId

    if (!isFromMe) {
        if (lastReadTs != null) {
            val prev = events.getOrNull(index - 1)
            val prevIsFromMe = prev != null && myId != null && prev.sender == myId
            val prevTs = prev?.timestampMs

            val justCrossed = timestamp > lastReadTs &&
                    (prev == null || prevIsFromMe || (prevTs != null && prevTs <= lastReadTs))

            if (justCrossed) {
                UnreadDivider()
            }
        } else {
            val prev = events.getOrNull(index - 1)
            val prevIsFromOther = prev != null && (myId == null || prev.sender != myId)
            if (!prevIsFromOther) {
                UnreadDivider()
            }
        }
    }

    // Message bubble
    val chips = state.reactionChips[event.eventId] ?: emptyList()
    val prevEvent = events.getOrNull(index - 1)
    val shouldGroup = prevEvent != null &&
            prevEvent.sender == event.sender &&
            prevDate == eventDate

    val isMine = event.sender == state.myUserId

    val isSelected = state.isSelectionMode && event.eventId in state.selectedEventIds

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (state.isSelectionMode) {
                        viewModel.toggleSelected(event.eventId)
                    }
                },
                onLongClick = {
                    if (state.isSelectionMode) viewModel.toggleSelected(event.eventId)
                    else onLongPress()
                }
            )
            .then(
                if (isSelected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                else Modifier
            )
    ) {

        MessageBubble(
            isMine = isMine,
            body = event.body,
            sender = event.senderDisplayName,
            timestamp = timestamp,
            grouped = shouldGroup,
            reactionChips = chips,
            eventId = event.eventId,
            onLongPress = onLongPress,
            onReact = onReact,
            lastReadByOthersTs = state.lastIncomingFromOthersTs,
            thumbPath = state.thumbByEvent[event.eventId],
            attachmentKind = event.attachment?.kind,
            durationMs = event.attachment?.durationMs,
            onOpenAttachment = onOpenAttachment,
            replyPreview = event.replyToBody,
            replySender = event.replyToSenderDisplayName,
            sendState = event.sendState,
            isEdited = event.isEdited,
            poll = event.pollData,
            onVote = { optionId ->
                event.pollData?.let { p -> viewModel.votePoll(event.eventId, p, optionId) }
            },
            onEndPoll = {
                viewModel.endPoll(event.eventId)
            }
        )
    }

    val threadCount = state.threadCount[event.eventId]

    if (event.eventId.isNotBlank() && threadCount != null && threadCount > 0) {
        Row(
            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            TextButton(onClick = onOpenThread) {
                Icon(
                    imageVector = Icons.Default.Forum,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (threadCount == 1) "1 reply" else "$threadCount replies",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }

    Spacer(Modifier.height(2.dp))

    // Read / send status for last outgoing message
    if (index == lastOutgoingIndex && lastOutgoingIndex >= 0) {
        val lastOutgoing = events.getOrNull(lastOutgoingIndex)
        if (lastOutgoing != null) {
            val statusText = when (lastOutgoing.sendState) {
                SendState.Sending, SendState.Retrying -> "Sending…"
                SendState.Enqueued -> "Queued"
                SendState.Failed -> "Failed to send"
                SendState.Sent -> {
                    if (state.lastOutgoingRead) {
                        "Seen ${formatTime(lastOutgoing.timestampMs)}"
                    } else {
                        "Delivered"
                    }
                }
                null -> {
                    // sendState is null - check if we have an eventId
                    if (lastOutgoing.eventId.isBlank()) {
                        "Sending…"
                    } else if (state.lastOutgoingRead) {
                        "Seen ${formatTime(lastOutgoing.timestampMs)}"
                    } else {
                        "Delivered"
                    }
                }
            }
            MessageStatusLine(text = statusText, isMine = true)
        }
        if (!state.isDm && seenByNames.isNotEmpty()) {
            SeenByChip(names = seenByNames)
        }
    }
}

@Composable
private fun DateHeader(date: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.lg, horizontal = Spacing.xxl),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.padding(horizontal = Spacing.md)
        ) {
            Text(
                text = date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = 4.dp)
            )
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
        )
    }
}

@Composable
private fun SelectionBottomBar(
    onShare: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = onShare) {
                Icon(Icons.Default.Share, null)
                Spacer(Modifier.width(6.dp))
                Text("Share")
            }
            TextButton(onClick = onForward) {
                Icon(Icons.AutoMirrored.Filled.Forward, null)
                Spacer(Modifier.width(6.dp))
                Text("Forward")
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null)
                Spacer(Modifier.width(6.dp))
                Text("Delete")
            }
        }
    }
}