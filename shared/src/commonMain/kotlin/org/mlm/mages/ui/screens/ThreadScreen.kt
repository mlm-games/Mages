package org.mlm.mages.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.mlm.mages.LinkPreview
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.thumbKey
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.ReactionSummary
import org.mlm.mages.ui.MentionProfileUi
import org.mlm.mages.ui.ThreadUiState
import org.mlm.mages.ui.displayPreview
import org.mlm.mages.ui.components.composer.EmoteSuggestion
import org.mlm.mages.ui.components.composer.MessageComposer
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.core.LoadMoreButton
import org.mlm.mages.ui.components.core.StatusBanner
import org.mlm.mages.ui.components.core.BannerType
import org.mlm.mages.ui.components.core.formatDisplayName
import org.mlm.mages.ui.components.location.TimelineLocationItem
import org.mlm.mages.ui.components.message.MessageBubble
import org.mlm.mages.ui.components.message.MessageBubbleRenderContext
import org.mlm.mages.ui.components.message.MessageBubbleVariant
import org.mlm.mages.ui.components.message.ReactionChipsRow
import org.mlm.mages.ui.components.message.toBubbleModel
import org.mlm.mages.ui.components.timeline.TimelineContent
import org.mlm.mages.ui.components.timeline.TimelineEventItem
import org.mlm.mages.ui.components.timeline.toTimelineContent
import org.mlm.mages.ui.components.sheets.MemberActionsSheet
import org.mlm.mages.ui.components.sheets.MessageActionSheet
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.components.snackbar.rememberErrorPoster
import org.mlm.mages.platform.ShareContent
import org.mlm.mages.platform.ShareOutcome
import org.mlm.mages.platform.rememberFileOpener
import org.mlm.mages.platform.rememberShareHandler
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.secondaryClick
import org.mlm.mages.ui.components.viewer.ImageViewerOverlay
import org.mlm.mages.ui.components.viewer.imageViewerItems
import org.mlm.mages.ui.components.viewer.isViewableImage
import org.mlm.mages.ui.components.viewer.loadImageForViewing
import org.mlm.mages.ui.viewmodel.ThreadViewModel
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import io.github.mlmgames.settings.core.SettingsRepository
import org.mlm.mages.settings.AppSettings
import mages.shared.generated.resources.Res
import org.jetbrains.compose.resources.pluralStringResource

@Composable
fun ThreadRoute(
    viewModel: ThreadViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarManager: SnackbarManager = koinInject()
    val postError = rememberErrorPoster(snackbarManager)
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val settings by settingsRepository.flow.collectAsState(initial = AppSettings())
    val openExternal = rememberFileOpener()
    val shareHandler = rememberShareHandler()
    val service: MatrixService = koinInject()

    val copiedLabel = stringResource(Res.string.copied_to_clipboard)
    val shareFailedLabel = stringResource(Res.string.share_failed)
    val downloadFailedLabel = stringResource(Res.string.download_failed)

    LaunchedEffect(Unit) { viewModel.refreshImagePacks() }

    var openedImageKey by remember { mutableStateOf<String?>(null) }
    val viewerItems = remember(state.allMessages, state.thumbByEvent) {
        imageViewerItems(state.allMessages) { event -> event.thumbKey?.let { state.thumbByEvent[it] } }
    }
    val viewerStartIndex = viewerItems.indexOfFirst { it.event.itemId == openedImageKey }

    val messageNotFound = stringResource(Res.string.message_not_found)
    ThreadScreen(
        state = state,
        myUserId = viewModel.myUserId,
        onReact = viewModel::react,
        onBack = onBack,
        onLoadMore = viewModel::loadMore,
        onClearFocus = viewModel::clearFocusedEvent,
        onFocusMissing = { postError(messageNotFound) },
        onSend = {
            scope.launch {
                if (state.editingEvent != null) {
                    viewModel.confirmEdit()
                } else {
                    viewModel.sendMessage(state.input)
                }
            }
        },
        onInputChange = viewModel::setInput,
        onStartReply = viewModel::startReply,
        onCancelReply = viewModel::cancelReply,
        onStartEdit = viewModel::startEdit,
        onCancelEdit = viewModel::cancelEdit,
        onDelete = { ev -> viewModel.delete(ev) },
        onOpenAttachment = { ev -> viewModel.openAttachment(ev) { path, mime -> openExternal(path, mime) } },
        onOpenImage = { ev -> openedImageKey = ev.itemId },
        enterSendsMessage = settings.enterSendsMessage,
        showReactionAvatars = settings.showReactionAvatars,
        canEditLatest = settings.editLatestWithUpArrow && state.editingEvent == null && viewModel.hasEditableLatest(),
        onEditLatest = viewModel::startEditLatestEditable,
        onMentionClick = viewModel::selectMemberForAction,
        onDismissMember = viewModel::clearSelectedMember,
        onStartDm = viewModel::startDmWith,
        onKick = viewModel::kickUser,
        onBan = viewModel::banUser,
        onUnban = viewModel::unbanUser,
        onIgnore = viewModel::ignoreUser,
        onOpenAvatar = { member ->
            viewModel.openAvatarExternally(member) { path, mime -> openExternal(path, mime) }
        },
        emoteSuggestions = viewModel.emoteSuggestions,
        resolveEmotePreview = { thumbnail, mxc -> viewModel.emotePreview(thumbnail, mxc) },
        reactionShortcodes = viewModel.reactionShortcodes,
        onReactionImages = { chips -> viewModel.ensureReactionImages(chips) },
    )

    val openedImage = openedImageKey
    if (openedImage != null) {
        ImageViewerOverlay(
            items = viewerItems,
            startKey = openedImage,
            startIndex = viewerStartIndex.coerceAtLeast(0),
            onDismiss = { openedImageKey = null },
            loadFullMedia = { event -> service.loadImageForViewing(event) },
            onOpenMedia = { path, mime -> openExternal(path, mime) },
            onShareMedia = { path, mime ->
                scope.launch {
                    when (shareHandler(ShareContent(filePaths = listOf(path), mimeTypes = listOf(mime)))) {
                        ShareOutcome.Shared -> Unit
                        ShareOutcome.Copied -> snackbarManager.show(copiedLabel)
                        ShareOutcome.Failed -> snackbarManager.showError(shareFailedLabel)
                    }
                }
            },
            onLoadFailed = { postError(downloadFailedLabel) },
        )
    }
}

@Composable
fun ThreadScreen(
    state: ThreadUiState,
    myUserId: String?,
    onReact: (MessageEvent, String) -> Unit,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onClearFocus: () -> Unit = {},
    onFocusMissing: () -> Unit = {},
    onSend: () -> Unit,
    onInputChange: (String) -> Unit,
    onStartReply: (MessageEvent) -> Unit,
    onCancelReply: () -> Unit,
    onStartEdit: (MessageEvent) -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: suspend (MessageEvent) -> Boolean,
    onOpenAttachment: (MessageEvent) -> Unit = {},
    onOpenImage: (MessageEvent) -> Unit = {},
    enterSendsMessage: Boolean = false,
    canEditLatest: Boolean = false,
    onEditLatest: () -> Unit = {},
    onMentionClick: ((String) -> Unit)? = null,
    onDismissMember: () -> Unit = {},
    onStartDm: (String) -> Unit = { },
    onKick: (String, String?) -> Unit = { _, _ -> },
    onBan: (String, String?) -> Unit = { _, _ -> },
    onUnban: (String, String?) -> Unit = { _, _ -> },
    onIgnore: (String) -> Unit = { },
    onOpenAvatar: (MemberSummary) -> Unit = { },
    showReactionAvatars: Boolean = true,
    emoteSuggestions: List<EmoteSuggestion> = emptyList(),
    resolveEmotePreview: suspend (thumbnailMxcUri: String?, mxcUrl: String) -> String? = { _, _ -> null },
    reactionShortcodes: Map<String, String> = emptyMap(),
    onReactionImages: (List<ReactionSummary>) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var sheetEvent by remember { mutableStateOf<MessageEvent?>(null) }
    val listState = rememberLazyListState()

    // Calculate total items
    val totalItems = remember(state.nextBatch, state.rootMessage, state.replies) {
        var count = 0
        if (state.nextBatch != null) count++
        if (state.rootMessage != null) count++
        if (state.rootMessage != null && state.replies.isNotEmpty()) count++
        count += state.replies.size
        count
    }

    val isNearBottom by remember(listState, totalItems) {
        derivedStateOf {
            val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1
            totalItems == 0 || firstVisible <= 3
        }
    }

    val focusedEventId = state.focusedEventId
    LaunchedEffect(state.replies, state.rootMessage) {
        onReactionImages(state.allMessages.flatMap { it.reactions })
    }
    var focusedLoadAttempts by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusedEventId, state.hasInitialLoad, state.replies.size, state.nextBatch, state.isLoading) {
        val target = focusedEventId ?: return@LaunchedEffect
        if (!state.hasInitialLoad) return@LaunchedEffect
        val replyIndex = state.replies.indexOfFirst { it.eventId == target }
        if (replyIndex >= 0) {
            listState.scrollToItem(state.replies.lastIndex - replyIndex)
            focusedLoadAttempts = 0
            return@LaunchedEffect
        }
        if (state.rootMessage?.eventId == target || state.rootEventId == target) {
            focusedLoadAttempts = 0
            return@LaunchedEffect
        }
        if (state.focusedEventMissing) {
            onFocusMissing()
            onClearFocus()
            focusedLoadAttempts = 0
            return@LaunchedEffect
        }
        if (state.isLoading) return@LaunchedEffect
        if (state.nextBatch != null && focusedLoadAttempts < 20) {
            focusedLoadAttempts++
            onLoadMore()
            return@LaunchedEffect
        }
        if (focusedLoadAttempts >= 20 || state.nextBatch == null) {
            onFocusMissing()
            onClearFocus()
            focusedLoadAttempts = 0
        }
    }

    // Auto-scroll when new message appears
    LaunchedEffect(state.replies.lastOrNull()?.itemId, isNearBottom, focusedEventId) {
        if (isNearBottom && totalItems > 0 && focusedEventId == null) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
        topBar = {
            ThreadTopBar(
                messageCount = state.messageCount,
                roomName = state.roomName,
                onBack = onBack
            )
        },
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
                MessageComposer(
                    value = state.input,
                    enabled = true,
                    isOffline = false,
                    replyingTo = state.replyingTo,
                    editing = state.editingEvent,
                    attachments = emptyList(),
                    isUploadingAttachment = false,
                    onValueChange = onInputChange,
                    onSend = onSend,
                    onCancelReply = onCancelReply,
                    onCancelEdit = onCancelEdit,
                    enterSendsMessage = enterSendsMessage,
                    canEditLatest = canEditLatest,
                    onEditLatest = onEditLatest,
                    roomMembers = state.roomMembers,
                    avatarPathByUserId = state.avatarByUserId,
                    emoteSuggestions = emoteSuggestions,
                    resolveEmotePreview = resolveEmotePreview,
                    isEncryptedRoom = state.isRoomEncrypted,
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !isNearBottom && state.replies.size > 5,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (totalItems > 0) {
                                listState.animateScrollToItem(0)
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(Res.string.scroll_to_bottom))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(Res.string.latest))
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            AnimatedVisibility(visible = state.isLoading && !state.hasInitialLoad) {
                LinearWavyProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            StatusBanner(
                message = state.error,
                type = BannerType.ERROR
            )

            when {
                !state.hasInitialLoad && state.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LoadingIndicator()
                            Spacer(Modifier.height(Spacing.lg))
                            Text(
                                stringResource(Res.string.loading_thread),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                state.rootMessage == null && state.hasInitialLoad -> {
                    EmptyThreadView()
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = Spacing.sm),
                        reverseLayout = true
                    ) {
                        items(
                            count = state.replies.size,
                            key = { i -> "reply_${state.replies[state.replies.lastIndex - i].itemId}" }
                        ) { dslIndex ->
                            val replyIndex = state.replies.lastIndex - dslIndex
                            val event = state.replies[replyIndex]
                            val prevEvent = state.replies.getOrNull(replyIndex - 1)
                            val shouldGroup = prevEvent != null &&
                                    prevEvent.sender == event.sender &&
                                    (event.timestampMs - prevEvent.timestampMs) < 300_000

                            val nextEvent = state.replies.getOrNull(replyIndex + 1)
                            val groupedWithNext = nextEvent != null &&
                                    nextEvent.sender == event.sender &&
                                    (nextEvent.timestampMs - event.timestampMs) < 300_000

                            TimelineEventItem(
                                item = event.toTimelineContent(),
                                bubble = { bubbleItem ->
                                    ThreadReplyMessage(
                                        event = bubbleItem.event,
                                        isMine = bubbleItem.event.sender == myUserId,
                                        reactionSummaries = bubbleItem.event.reactions,
                                        avatarByUserId = state.avatarByUserId,
                                        replyThumbByEvent = state.replyThumbByEvent,
                                        thumbByEvent = state.thumbByEvent,
                                        linkPreviewByEvent = state.linkPreviewByEvent,
                                        linkPreviewImageByEvent = state.linkPreviewImageByEvent,
                                        mentionProfilesByUserId = state.mentionProfilesByUserId,
                                        onMentionClick = onMentionClick,
                                        emotePaths = state.emotePathByMxc,
                                        reactionImagePaths = state.reactionImagePathByMxc,
                                        reactionShortcodes = reactionShortcodes,
                                        onReact = { emoji -> onReact(bubbleItem.event, emoji) },
                                        onLongPress = { sheetEvent = bubbleItem.event },
                                        onOpenAttachment = {
                                            val event = bubbleItem.event
                                            if (event.isViewableImage()) onOpenImage(event)
                                            else onOpenAttachment(event)
                                        },
                                        grouped = shouldGroup,
                                        groupedWithNext = groupedWithNext,
                                        highlighted = state.focusedEventId == bubbleItem.event.eventId,
                                    )
                                },
                                staticLocation = { locItem ->
                                    TimelineLocationItem(
                                        item = locItem,
                                        onClick = {},
                                        onLongClick = { sheetEvent = locItem.event },
                                        senderDisplayName = locItem.event.senderDisplayName,
                                        senderAvatarPath = state.avatarByUserId[locItem.event.sender],
                                    )
                                },
                                liveLocation = { locItem ->
                                    TimelineLocationItem(
                                        item = locItem,
                                        isLive = locItem.event.liveLocation?.isLive == true,
                                        onClick = {},
                                        onLongClick = { sheetEvent = locItem.event },
                                        senderDisplayName = locItem.event.senderDisplayName,
                                        senderAvatarPath = state.avatarByUserId[locItem.event.sender],
                                    )
                                },
                            )
                        }

                        if (state.replies.isNotEmpty()) {
                            item(key = "divider") {
                                ThreadDivider(replyCount = state.replies.size)
                            }
                        }

                        state.rootMessage?.let { root ->
                            item(key = "root_${root.itemId}") {
                                TimelineEventItem(
                                    item = root.toTimelineContent(),
                                    bubble = { bubbleItem ->
                                        ThreadRootMessage(
                                            state = state,
                                            event = bubbleItem.event,
                                            isMine = bubbleItem.event.sender == myUserId,
                                            reactionSummaries = bubbleItem.event.reactions,
                                            reactionShortcodes = reactionShortcodes,
                                            showReactionAvatars = showReactionAvatars,
                                            onReact = { emoji -> onReact(bubbleItem.event, emoji) },
                                            onReply = { onStartReply(bubbleItem.event) },
                                            onLongPress = { sheetEvent = bubbleItem.event }
                                        )
                                    },
                                    staticLocation = { locItem ->
                                        TimelineLocationItem(
                                            item = locItem,
                                            onClick = {},
                                            onLongClick = { sheetEvent = locItem.event },
                                            senderDisplayName = locItem.event.senderDisplayName,
                                            senderAvatarPath = state.avatarByUserId[locItem.event.sender],
                                        )
                                    },
                                    liveLocation = { locItem ->
                                        TimelineLocationItem(
                                            item = locItem,
                                            isLive = locItem.event.liveLocation?.isLive == true,
                                            onClick = {},
                                            onLongClick = { sheetEvent = locItem.event },
                                            senderDisplayName = locItem.event.senderDisplayName,
                                            senderAvatarPath = state.avatarByUserId[locItem.event.sender],
                                        )
                                    },
                                )
                            }
                        }

                        if (state.nextBatch != null) {
                            item(key = "load_more") {
                                LoadMoreButton(
                                    isLoading = state.isLoading,
                                    onClick = onLoadMore,
                                    text = stringResource(Res.string.load_earlier_messages)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    sheetEvent?.let { ev ->
        val isMine = ev.sender == myUserId
        MessageActionSheet(
            event = ev,
            isMine = isMine,
            onDismiss = { sheetEvent = null },
            onReply = {
                onStartReply(ev)
                sheetEvent = null
            },
            onEdit = {
                if (isMine) {
                    run {
                        onStartEdit(ev)
                        sheetEvent = null
                    }
                } else null
            },
            onDelete = {
                if (isMine) {
                    run {
                        scope.launch {
                            onDelete(ev)
                            sheetEvent = null
                        }
                    }
                } else null
            },
            onReact = { emoji -> onReact(ev, emoji) },
            onMarkReadHere = { sheetEvent = null },
            onSelect = { }// viewModel.enterSelectionMode(event.eventId) },
        )
    }

    state.selectedMemberForAction?.let { member ->
        MemberActionsSheet(
            member = member.copy(avatarUrl = state.avatarByUserId[member.userId] ?: member.avatarUrl),
            onDismiss = onDismissMember,
            dmAction = state.selectedMemberDmAction,
            kickAction = state.selectedMemberKickAction,
            banAction = state.selectedMemberBanAction,
            unbanAction = state.selectedMemberUnbanAction,
            onStartDm = { onStartDm(member.userId) },
            onKick = { reason -> onKick(member.userId, reason) },
            onBan = { reason -> onBan(member.userId, reason) },
            onUnban = { reason -> onUnban(member.userId, reason) },
            onIgnore = { onIgnore(member.userId) },
            onAvatarClick = { onOpenAvatar(member) },
            isBanned = member.membership == "ban"
        )
    }
}

@Composable
private fun ThreadTopBar(
    messageCount: Int,
    roomName: String,
    onBack: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Forum,
                                null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(Spacing.md))
                    Column {
                        Text(
                            stringResource(Res.string.thread),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            buildString {
                                append("$messageCount ${if (messageCount == 1) "message" else "messages"}")
                                if (roomName.isNotBlank()) {
                                    append(" • $roomName")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
    }
}

@Composable
private fun ThreadRootMessage(
    state: ThreadUiState,
    event: MessageEvent,
    isMine: Boolean,
    reactionSummaries: List<ReactionSummary>,
    reactionShortcodes: Map<String, String> = emptyMap(),
    showReactionAvatars: Boolean,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onLongPress: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.md)
            .secondaryClick(onLongPress),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp),
        onClick = onLongPress
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(8.dp)
                ) {}
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    stringResource(Res.string.thread_started),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(Spacing.md))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    name = event.sender,
                    avatarPath = state.avatarByUserId[event.sender],
                    size = 36.dp,
                    containerColor = if (isMine)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (isMine)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(Spacing.md))
                Column {
                    Text(
                        formatDisplayName(event.sender),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isMine) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))

            Surface(
                color = if (isMine)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    event.displayPreview(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(Spacing.md),
                    color = if (isMine)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }

            if (reactionSummaries.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.sm))
                ReactionChipsRow(
                    chips = reactionSummaries,
                    maxVisible = 6,
                    avatarPathsByUserId = state.avatarByUserId,
                    imagePaths = state.reactionImagePathByMxc,
                    shortcodes = reactionShortcodes,
                    showAvatars = showReactionAvatars,
                    onClick = onReact,
                    modifier = Modifier.offset(y = (-12).dp).padding(horizontal = Spacing.xs)
                )
            }

            Spacer(Modifier.height(Spacing.sm))
            Surface(
                onClick = onReply,
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Reply,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(Res.string.reply_action),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadDivider(replyCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.padding(horizontal = Spacing.md)
        ) {
            Text(
                pluralStringResource(Res.plurals.reply_count, replyCount, replyCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun ThreadReplyMessage(
    event: MessageEvent,
    isMine: Boolean,
    reactionSummaries: List<ReactionSummary>,
    avatarByUserId: Map<String, String>,
    replyThumbByEvent: Map<String, String>,
    thumbByEvent: Map<String, String>,
    linkPreviewByEvent: Map<String, LinkPreview?>,
    linkPreviewImageByEvent: Map<String, String>,
    mentionProfilesByUserId: Map<String, MentionProfileUi> = emptyMap(),
    onMentionClick: ((String) -> Unit)? = null,
    emotePaths: Map<String, String>,
    reactionImagePaths: Map<String, String>,
    reactionShortcodes: Map<String, String>,
    onReact: (String) -> Unit,
    onLongPress: () -> Unit,
    onOpenAttachment: (() -> Unit)?,
    grouped: Boolean = false,
    groupedWithNext: Boolean = false,
    highlighted: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = Spacing.md, vertical = if (grouped) 2.dp else 4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(24.dp)
                .padding(top = if (grouped) 4.dp else Spacing.lg)
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(if (grouped) 24.dp else 40.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(1.dp)
                    )
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            val bubbleModel = TimelineContent.Bubble(event).toBubbleModel(
                ctx = MessageBubbleRenderContext(
                    isMine = isMine,
                    isDm = false,
                    avatarPath = if (grouped) null else avatarByUserId[event.sender],
                    groupedWithPrev = grouped,
                    groupedWithNext = groupedWithNext,
                    reactions = reactionSummaries,
                    threadCount = null,
                    variant = MessageBubbleVariant.ThreadReply,
                    resolvedPreviewPath = event.thumbKey?.let { thumbByEvent[it] },
                    resolvedReplyPreviewPath = event.replyToEventId?.let { replyThumbByEvent[it] },
                    resolvedLinkPreview = linkPreviewByEvent[event.eventId],
                    resolvedLinkPreviewImage = linkPreviewImageByEvent[event.eventId],
                    resolvedMentions = mentionsOf(event, mentionProfilesByUserId),
                    senderVisible = !grouped,
                    reactionImagePaths = reactionImagePaths,
                    reactionShortcodes = reactionShortcodes,
                )
            ).copy(
                sendState = event.sendState,
                isEdited = event.isEdited,
            )
            MessageBubble(
                model = bubbleModel,
                onMentionClick = onMentionClick,
                onLongPress = onLongPress,
                onReact = onReact,
                onOpenAttachment = onOpenAttachment,
                emotePaths = emotePaths
            )
        }
    }
}

@Composable
private fun EmptyThreadView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(Spacing.xxl)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = CircleShape,
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Default.Forum,
                        null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xl))
            Text(
                stringResource(Res.string.thread_not_found),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(Res.string.the_thread_may_have_been_deleted_or_is_still_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
