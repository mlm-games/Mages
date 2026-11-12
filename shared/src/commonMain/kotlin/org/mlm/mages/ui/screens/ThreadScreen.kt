package org.mlm.mages.ui.screens

import org.jetbrains.compose.resources.painterResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.mlm.mages.ui.util.dragToScrollIfEnabled
import org.mlm.mages.ui.util.isSameMessageGroup
import org.mlm.mages.LinkPreview
import org.mlm.mages.MatrixService
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.SendState
import org.mlm.mages.matrix.allowsMediaPreviews
import org.mlm.mages.thumbKey
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.PollData
import org.mlm.mages.matrix.ReactionSummary
import org.mlm.mages.ui.MentionProfileUi
import org.mlm.mages.ui.ThreadUiState
import org.mlm.mages.ui.displayPreview
import org.mlm.mages.ui.components.composer.ActionBanner
import org.mlm.mages.ui.components.composer.EmoteSuggestion
import org.mlm.mages.ui.components.composer.MessageComposer
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.core.LoadMoreButton
import org.mlm.mages.ui.components.core.StatusBanner
import org.mlm.mages.ui.components.core.BannerType
import org.mlm.mages.ui.components.core.formatDisplayName
import org.mlm.mages.ui.components.location.LiveLocationMapViewer
import org.mlm.mages.ui.components.location.LocationViewerMode
import org.mlm.mages.ui.components.location.TimelineLocationItem
import org.mlm.mages.ui.components.location.parseGeoUri
import org.mlm.mages.ui.components.message.MessageBubble
import org.mlm.mages.ui.components.message.MessageBubbleRenderContext
import org.mlm.mages.ui.components.message.MessageBubbleVariant
import org.mlm.mages.ui.components.message.MessageStatusLine
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
import org.mlm.mages.ui.components.viewer.AvatarViewerHost
import org.mlm.mages.ui.components.viewer.ImageViewerOverlay
import org.mlm.mages.ui.components.viewer.ImageViewerItem
import org.mlm.mages.ui.components.viewer.imageViewerItems
import org.mlm.mages.ui.components.viewer.isViewableImage
import org.mlm.mages.ui.components.viewer.loadImageForViewing
import org.mlm.mages.ui.components.viewer.rememberAvatarViewerState
import org.mlm.mages.ui.viewmodel.ThreadViewModel
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import io.github.mlmgames.settings.core.SettingsRepository
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.MessageMenuAction
import org.mlm.mages.settings.parseMessageMenuOrder
import org.mlm.mages.settings.parseQuickReactions
import org.mlm.mages.settings.defaultQuickReactions

@Composable
fun ThreadRoute(
    viewModel: ThreadViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarManager: SnackbarManager = koinInject()
    val postError = rememberErrorPoster(snackbarManager)
    val openExternal = rememberFileOpener()
    val shareHandler = rememberShareHandler()
    val avatarViewer = rememberAvatarViewerState()
    val service: MatrixService = koinInject()
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val settings by settingsRepository.flow.collectAsState(initial = AppSettings())

    val copiedLabel = stringResource(Res.string.copied_to_clipboard)
    val shareFailedLabel = stringResource(Res.string.share_failed)
    val downloadFailedLabel = stringResource(Res.string.download_failed)

    LaunchedEffect(Unit) { viewModel.refreshImagePacks() }

    var openedImageKey by remember { mutableStateOf<String?>(null) }
    var openedImageItems by remember { mutableStateOf<List<ImageViewerItem>?>(null) }
    val liveViewerItems = remember(state.allMessages, state.thumbByEvent) {
        imageViewerItems(state.allMessages) { event -> event.thumbKey?.let { state.thumbByEvent[it] } }
    }
    val viewerItems = openedImageItems ?: liveViewerItems
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
        onOpenImage = { ev ->
            openedImageItems = imageViewerItems(state.allMessages) { e ->
                e.thumbKey?.let { state.thumbByEvent[it] }
            }
            openedImageKey = ev.itemId
        },
        enterSendsMessage = settings.enterSendsMessage,
        showReactionAvatars = settings.showReactionAvatars,
        canEditLatest = settings.editLatestWithUpArrow && state.editingEvent == null && viewModel.hasEditableLatest(),
        onEditLatest = viewModel::startEditLatestEditable,
        onMentionClick = viewModel::selectMemberForAction,
        onDismissMember = viewModel::clearSelectedMember,
        onEnterSelectionMode = viewModel::enterSelectionMode,
        onToggleSelection = viewModel::toggleSelected,
        onClearSelection = viewModel::clearSelection,
        onSelectAll = viewModel::selectAllVisible,
        onForwardSelected = viewModel::forwardSelected,
        onDeleteSelected = viewModel::deleteSelected,
        onForwardEvent = { event -> viewModel.forwardEvent(event) },
        onMarkReadHere = viewModel::markReadHere,
        onRetryMessage = viewModel::retry,
        onRetryLoad = viewModel::retryLoad,
        onVotePoll = viewModel::votePoll,
        onEndPoll = viewModel::endPoll,
        onShowStaticLocation = viewModel::showStaticLocation,
        onStartDm = viewModel::startDmWith,
        onKick = viewModel::kickUser,
        onBan = viewModel::banUser,
        onUnban = viewModel::unbanUser,
        onIgnore = viewModel::ignoreUser,
        onOpenAvatar = { member ->
            viewModel.openAvatarExternally(member) { path, _ ->
                avatarViewer.show(path, member.displayName ?: member.userId)
            }
        },
        emoteSuggestions = viewModel.emoteSuggestions,
        resolveEmotePreview = { thumbnail, mxc, mime, full ->
            viewModel.emotePreview(thumbnail, mxc, mime, full)
        },
        menuOrder = parseMessageMenuOrder(settings.messageMenuOrder),
        quickReactions = parseQuickReactions(settings.quickReactions),
        reactionShortcodes = viewModel.reactionShortcodes,
        onReactionImages = { chips -> viewModel.ensureReactionImages(chips) },
        previewBlocked = !settings.mediaPreviews.allowsMediaPreviews(state.isDm),
        onShowPreview = { viewModel.showMediaPreview(it) },
    )

    val openedImage = openedImageKey
    if (openedImage != null) {
        ImageViewerOverlay(
            items = viewerItems,
            startKey = openedImage,
            startIndex = viewerStartIndex.coerceAtLeast(0),
            onDismiss = {
                openedImageKey = null
                openedImageItems = null
            },
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

    AvatarViewerHost(avatarViewer)

    if (state.showStaticLocationViewer) {
        LiveLocationMapViewer(
            shares = emptyMap(),
            avatarPathByUserId = state.avatarByUserId,
            displayNameByUserId = state.allMessages.asReversed().associate {
                it.sender to (it.senderDisplayName ?: it.sender)
            },
            onDismiss = viewModel::hideStaticLocation,
            mode = LocationViewerMode.ViewStatic(
                lat = state.staticLocationViewerLat,
                lon = state.staticLocationViewerLon,
            ),
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
    onEnterSelectionMode: (String) -> Unit = {},
    onToggleSelection: (String) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onForwardSelected: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    onForwardEvent: (MessageEvent) -> Unit = {},
    onMarkReadHere: (MessageEvent) -> Unit = {},
    onRetryMessage: (MessageEvent) -> Unit = {},
    onRetryLoad: () -> Unit = {},
    onVotePoll: (String, PollData, String) -> Unit = { _, _, _ -> },
    onEndPoll: (String) -> Unit = {},
    onShowStaticLocation: (Double, Double) -> Unit = { _, _ -> },
    onDismissMember: () -> Unit = {},
    onStartDm: (String) -> Unit = { },
    onKick: (String, String?) -> Unit = { _, _ -> },
    onBan: (String, String?) -> Unit = { _, _ -> },
    onUnban: (String, String?) -> Unit = { _, _ -> },
    onIgnore: (String) -> Unit = { },
    onOpenAvatar: (MemberSummary) -> Unit = { },
    showReactionAvatars: Boolean = true,
    emoteSuggestions: List<EmoteSuggestion> = emptyList(),
    resolveEmotePreview: suspend (thumbnailMxcUri: String?, mxcUrl: String, mime: String?, full: Boolean) -> String? = { _, _, _, _ -> null },
    reactionShortcodes: Map<String, String> = emptyMap(),
    onReactionImages: (List<ReactionSummary>) -> Unit = {},
    menuOrder: List<MessageMenuAction> = MessageMenuAction.entries,
    quickReactions: List<String> = defaultQuickReactions,
    previewBlocked: Boolean = false,
    onShowPreview: ((MessageEvent) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var sheetItemId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Calculate total items
    val totalItems = remember(state.nextBatch, state.rootMessage, state.replies) {
        var count = 0
        if (state.nextBatch != null) count++
        if (state.rootMessage != null) count++
        count += state.replies.size
        count
    }

    val nearBottomOffset = with(LocalDensity.current) { 8.dp.roundToPx() }

    val isNearBottom by remember(listState, totalItems, nearBottomOffset) {
        derivedStateOf {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            totalItems == 0 || first == null ||
                (if (first.index == 0) first.offset >= -nearBottomOffset else first.index <= 3)
        }
    }

    val focusedEventId = state.focusedEventId
    LaunchedEffect(state.replies, state.rootMessage) {
        onReactionImages(state.allMessages.flatMap { it.reactions })
    }
    var focusedLoadAttempts by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusedEventId) { focusedLoadAttempts = 0 }
    LaunchedEffect(focusedEventId, state.hasInitialLoad, state.replies.size, state.nextBatch, state.isLoading) {
        val target = focusedEventId ?: return@LaunchedEffect
        if (!state.hasInitialLoad) return@LaunchedEffect
        val replyIndex = state.replies.indexOfFirst { it.eventId == target }
        if (replyIndex >= 0) {
            listState.scrollToItem(state.replies.lastIndex - replyIndex)
            focusedLoadAttempts = 0
            onClearFocus()
            return@LaunchedEffect
        }
        if (state.rootMessage != null &&
            (state.rootMessage.eventId == target || state.rootEventId == target)
        ) {
            listState.scrollToItem(state.replies.size)
            focusedLoadAttempts = 0
            onClearFocus()
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

    val shouldFollowNewest = isNearBottom && totalItems > 0 && focusedEventId == null
    LaunchedEffect(state.replies.size, state.replies.lastOrNull()?.itemId) {
        if (shouldFollowNewest) listState.requestScrollToItem(0)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
        topBar = {
            if (state.isSelectionMode) {
                SelectionTopBar(
                    selectedCount = state.selectedEventIds.size,
                    onClearSelection = onClearSelection,
                    onSelectAll = onSelectAll,
                )
            } else {
                ThreadTopBar(
                    roomName = state.roomName,
                    onBack = onBack
                )
            }
        },
        bottomBar = {
            if (state.isSelectionMode) {
                ThreadSelectionBottomBar(
                    onForward = onForwardSelected,
                    onDelete = onDeleteSelected,
                )
            } else {
                Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
                    ActionBanner(
                        replyingTo = state.replyingTo,
                        editing = state.editingEvent,
                        onCancelReply = onCancelReply,
                        onCancelEdit = onCancelEdit,
                        resolvedPreviewPath = (state.editingEvent ?: state.replyingTo)?.thumbKey?.let { state.thumbByEvent[it] },
                    )
                    MessageComposer(
                        value = state.input,
                        enabled = state.sendMessageAction.isEnabled,
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
                    Icon(painterResource(Res.drawable.ic_keyboard_arrow_down), stringResource(Res.string.scroll_to_bottom))
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
            AnimatedVisibility(visible = state.isLoading && state.hasInitialLoad) {
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
                !state.hasInitialLoad &&
                        state.rootMessage == null &&
                        state.replies.isEmpty() &&
                        state.error != null -> {
                    EmptyThreadView(hasError = true, onRetry = onRetryLoad)
                }

                !state.hasInitialLoad && state.rootMessage == null && state.replies.isEmpty() -> {
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

                state.rootMessage == null && state.replies.isEmpty() && state.hasInitialLoad -> {
                    EmptyThreadView(
                        hasError = state.error != null,
                        onRetry = onRetryLoad,
                    )
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .dragToScrollIfEnabled(listState, reverse = true),
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
                            val shouldGroup = prevEvent != null && isSameMessageGroup(prevEvent, event)

                            val nextEvent = state.replies.getOrNull(replyIndex + 1)
                            val groupedWithNext = nextEvent != null && isSameMessageGroup(event, nextEvent)

                            TimelineEventItem(
                                item = event.toTimelineContent(),
                                bubble = { bubbleItem ->
                                    ThreadReplyMessage(
                                        event = bubbleItem.event,
                                        isMine = bubbleItem.event.sender == myUserId,
                                        isDm = state.isDm,
                                        reactionSummaries = bubbleItem.event.reactions,
                                        avatarByUserId = state.avatarByUserId,
                                        replyThumbByEvent = state.replyThumbByEvent,
                                        thumbByEvent = state.thumbByEvent,
                                        audioFileByEvent = state.audioFileByEvent,
                                        waveformByEvent = state.waveformByEvent,
                                        linkPreviewByEvent = state.linkPreviewByEvent,
                                        linkPreviewImageByEvent = state.linkPreviewImageByEvent,
                                        mentionProfilesByUserId = state.mentionProfilesByUserId,
                                        showReactionAvatars = showReactionAvatars,
                                        isSelected = bubbleItem.event.eventId in state.selectedEventIds,
                                        onMentionClick = onMentionClick,
                                        onClick = if (state.isSelectionMode) {
                                            { onToggleSelection(bubbleItem.event.eventId) }
                                        } else {
                                            null
                                        },
                                        isSelectionMode = state.isSelectionMode,
                                        onReplyPreviewClick = if (state.isSelectionMode) {
                                            null
                                        } else bubbleItem.event.replyToEventId?.let { targetId ->
                                            {
                                                val targetIndex = state.replies.indexOfFirst { it.eventId == targetId }
                                                if (targetIndex >= 0) {
                                                    scope.launch {
                                                        listState.animateScrollToItem(state.replies.lastIndex - targetIndex)
                                                    }
                                                } else if (state.rootMessage?.eventId == targetId) {
                                                    scope.launch {
                                                        listState.animateScrollToItem(state.replies.size)
                                                    }
                                                }
                                            }
                                        },
                                        onVote = { optionId ->
                                            bubbleItem.event.pollData?.let {
                                                onVotePoll(bubbleItem.event.eventId, it, optionId)
                                            }
                                        },
                                        onEndPoll = { onEndPoll(bubbleItem.event.eventId) },
                                        onSenderClick = if (bubbleItem.event.sender != myUserId &&
                                            !state.isSelectionMode
                                        ) {
                                            { onMentionClick?.invoke(bubbleItem.event.sender) }
                                        } else {
                                            null
                                        },
                                        emotePaths = state.emotePathByMxc,
                                        reactionImagePaths = state.reactionImagePathByMxc,
                                        reactionShortcodes = reactionShortcodes,
                                        onReact = { emoji -> onReact(bubbleItem.event, emoji) },
                                        onLongPress = {
                                            if (state.isSelectionMode) {
                                                onToggleSelection(bubbleItem.event.eventId)
                                            } else {
                                                sheetItemId = bubbleItem.event.itemId
                                            }
                                        },
                                        onOpenAttachment = {
                                            val event = bubbleItem.event
                                            if (event.isViewableImage()) onOpenImage(event)
                                            else onOpenAttachment(event)
                                        },
                                        grouped = shouldGroup,
                                        groupedWithNext = groupedWithNext,
                                        highlighted = state.focusedEventId == bubbleItem.event.eventId,
                                        previewBlocked = previewBlocked,
                                        onShowPreview = { onShowPreview?.invoke(bubbleItem.event) },
                                    )
                                },
                                staticLocation = { locItem ->
                                    TimelineLocationItem(
                                        item = locItem,
                                        onClick = {
                                            if (state.isSelectionMode) {
                                                onToggleSelection(locItem.event.eventId)
                                            } else {
                                                parseGeoUri(
                                                    locItem.event.liveLocation?.geoUri ?: locItem.event.body
                                                )?.let { (lat, lon) -> onShowStaticLocation(lat, lon) }
                                            }
                                        },
                                        onLongClick = {
                                            if (state.isSelectionMode) {
                                                onToggleSelection(locItem.event.eventId)
                                            } else {
                                                sheetItemId = locItem.event.itemId
                                            }
                                        },
                                        senderDisplayName = locItem.event.senderDisplayName,
                                        senderAvatarPath = state.avatarByUserId[locItem.event.sender],
                                    )
                                },
                                liveLocation = { locItem ->
                                    TimelineLocationItem(
                                        item = locItem,
                                        isLive = locItem.event.liveLocation?.isLive == true,
                                        onClick = {
                                            if (state.isSelectionMode) {
                                                onToggleSelection(locItem.event.eventId)
                                            } else {
                                                parseGeoUri(
                                                    locItem.event.liveLocation?.geoUri ?: locItem.event.body
                                                )?.let { (lat, lon) -> onShowStaticLocation(lat, lon) }
                                            }
                                        },
                                        onLongClick = {
                                            if (state.isSelectionMode) {
                                                onToggleSelection(locItem.event.eventId)
                                            } else {
                                                sheetItemId = locItem.event.itemId
                                            }
                                        },
                                        senderDisplayName = locItem.event.senderDisplayName,
                                        senderAvatarPath = state.avatarByUserId[locItem.event.sender],
                                    )
                                },
                            )
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
                                            reactionShortcodes = reactionShortcodes,
                                            showReactionAvatars = showReactionAvatars,
                                            onMentionClick = onMentionClick,
                                            onClick = if (state.isSelectionMode) {
                                                {
                                                    onToggleSelection(bubbleItem.event.eventId)
                                                }
                                            } else {
                                                null
                                            },
                                            highlighted = state.isSelectionMode &&
                                                bubbleItem.event.eventId in state.selectedEventIds,
                                            previewBlocked = previewBlocked,
                                            onOpenAttachment = onOpenAttachment,
                                            onOpenImage = onOpenImage,
                                            onShowPreview = { onShowPreview?.invoke(bubbleItem.event) },
                                            onReplyPreviewClick = if (state.isSelectionMode) {
                                                null
                                            } else bubbleItem.event.replyToEventId?.let { targetId ->
                                                {
                                                    val targetIndex = state.replies.indexOfFirst { it.eventId == targetId }
                                                    if (targetIndex >= 0) {
                                                        scope.launch {
                                                            listState.animateScrollToItem(state.replies.lastIndex - targetIndex)
                                                        }
                                                    } else if (state.rootMessage?.eventId == targetId) {
                                                        scope.launch {
                                                            listState.animateScrollToItem(state.replies.size)
                                                        }
                                                    }
                                                }
                                            },
                                            onVote = { optionId ->
                                                bubbleItem.event.pollData?.let {
                                                    onVotePoll(bubbleItem.event.eventId, it, optionId)
                                                }
                                            },
                                            onEndPoll = { onEndPoll(bubbleItem.event.eventId) },
                                            onReact = { emoji -> onReact(bubbleItem.event, emoji) },
                                            onReply = { onStartReply(bubbleItem.event) },
                                            onLongPress = {
                                                if (state.isSelectionMode) {
                                                    onToggleSelection(bubbleItem.event.eventId)
                                                } else {
                                                    sheetItemId = bubbleItem.event.itemId
                                                }
                                            },
                                        )
                                    },
                                    staticLocation = { locItem ->
                                        TimelineLocationItem(
                                            item = locItem,
                                            onClick = {
                                                if (state.isSelectionMode) {
                                                    onToggleSelection(locItem.event.eventId)
                                                } else {
                                                    parseGeoUri(
                                                        locItem.event.liveLocation?.geoUri ?: locItem.event.body
                                                    )?.let { (lat, lon) -> onShowStaticLocation(lat, lon) }
                                                }
                                            },
                                            onLongClick = {
                                                if (state.isSelectionMode) {
                                                    onToggleSelection(locItem.event.eventId)
                                                } else {
                                                    sheetItemId = locItem.event.itemId
                                                }
                                            },
                                            senderDisplayName = locItem.event.senderDisplayName,
                                            senderAvatarPath = state.avatarByUserId[locItem.event.sender],
                                        )
                                    },
                                    liveLocation = { locItem ->
                                        TimelineLocationItem(
                                            item = locItem,
                                            isLive = locItem.event.liveLocation?.isLive == true,
                                            onClick = {
                                                if (state.isSelectionMode) {
                                                    onToggleSelection(locItem.event.eventId)
                                                } else {
                                                    parseGeoUri(
                                                        locItem.event.liveLocation?.geoUri ?: locItem.event.body
                                                    )?.let { (lat, lon) -> onShowStaticLocation(lat, lon) }
                                                }
                                            },
                                            onLongClick = {
                                                if (state.isSelectionMode) {
                                                    onToggleSelection(locItem.event.eventId)
                                                } else {
                                                    sheetItemId = locItem.event.itemId
                                                }
                                            },
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

    val sheetEvent = sheetItemId?.let { id -> state.allMessages.firstOrNull { it.itemId == id } }
    if (sheetEvent != null) {
        val ev = sheetEvent
        val isMine = ev.sender == myUserId
        MessageActionSheet(
            event = ev,
            isMine = isMine,
            menuOrder = menuOrder,
            quickReactions = quickReactions,
            reactionImagePacks = state.imagePacks,
            resolveReactionPreview = resolveEmotePreview,
            isEncryptedRoom = state.isRoomEncrypted,
            onDismiss = { sheetItemId = null },
            onReply = {
                onStartReply(ev)
                sheetItemId = null
            },
            onEdit = {
                if (isMine) {
                    onStartEdit(ev)
                    sheetItemId = null
                }
            },
            onDelete = {
                if (isMine) {
                    scope.launch {
                        onDelete(ev)
                        sheetItemId = null
                    }
                }
            },
            onCancelSend = {
                if (isMine) {
                    scope.launch {
                        onDelete(ev)
                        sheetItemId = null
                    }
                }
            },
            onRetry = if (isMine) {
                {
                    onRetryMessage(ev)
                    sheetItemId = null
                }
            } else {
                null
            },
            onForward = if (ev.eventId.isNotBlank()) {
                {
                    onForwardEvent(ev)
                    sheetItemId = null
                }
            } else {
                null
            },
            onReact = { emoji -> onReact(ev, emoji) },
            onMarkReadHere = {
                onMarkReadHere(ev)
                sheetItemId = null
            },
            onSelect = {
                if (ev.eventId.isNotBlank()) onEnterSelectionMode(ev.eventId)
                sheetItemId = null
            },
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
                                painterResource(Res.drawable.ic_gesture),
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
                        if (roomName.isNotBlank()) {
                            Text(
                                roomName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = stringResource(Res.string.back))
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
    reactionShortcodes: Map<String, String>,
    showReactionAvatars: Boolean,
    onMentionClick: ((String) -> Unit)?,
    onClick: (() -> Unit)?,
    highlighted: Boolean,
    previewBlocked: Boolean,
    onOpenAttachment: (MessageEvent) -> Unit,
    onOpenImage: (MessageEvent) -> Unit,
    onShowPreview: () -> Unit,
    onReplyPreviewClick: (() -> Unit)?,
    onVote: (String) -> Unit,
    onEndPoll: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onLongPress: () -> Unit
) {
    val senderName = event.senderDisplayName?.takeIf { it.isNotBlank() }
        ?: formatDisplayName(event.sender)
    val inSelectionMode = onClick != null
    val senderClickable = !isMine && onMentionClick != null && !inSelectionMode

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.md)
            .combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = onLongPress
            )
            .secondaryClick(onLongPress),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.lg, top = Spacing.md, end = Spacing.lg)
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .then(
                        if (senderClickable) {
                            Modifier.clickable { onMentionClick?.invoke(event.sender) }
                        } else {
                            Modifier
                        }
                    )
            ) {
                Avatar(
                    name = senderName,
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
                Column(Modifier.weight(1f)) {
                    Text(
                        senderName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isMine) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            val bubbleModel = TimelineContent.Bubble(event).toBubbleModel(
                ctx = MessageBubbleRenderContext(
                    isMine = isMine,
                    isDm = state.isDm,
                    avatarPath = state.avatarByUserId[event.sender],
                    groupedWithPrev = false,
                    groupedWithNext = false,
                    reactions = event.reactions,
                    reactionAvatarsByUserId = state.avatarByUserId,
                    showReactionAvatars = showReactionAvatars,
                    reactionImagePaths = state.reactionImagePathByMxc,
                    reactionShortcodes = reactionShortcodes,
                    variant = MessageBubbleVariant.ThreadRoot,
                    previewBlocked = previewBlocked,
                    resolvedPreviewPath = event.thumbKey?.let { state.thumbByEvent[it] },
                    resolvedReplyPreviewPath = event.replyToEventId?.let {
                        state.replyThumbByEvent[it] ?: state.thumbByEvent[it]
                    },
                    resolvedAudioPath = state.audioFileByEvent[event.eventId],
                    resolvedAudioWaveform = state.waveformByEvent[event.eventId].orEmpty(),
                    resolvedLinkPreview = state.linkPreviewByEvent[event.eventId],
                    resolvedLinkPreviewImage = state.linkPreviewImageByEvent[event.eventId],
                    resolvedMentions = mentionsOf(event, state.mentionProfilesByUserId),
                    senderVisible = false,
                )
            )
            Box {
                MessageBubble(
                    model = bubbleModel,
                    onMentionClick = onMentionClick,
                    onClick = onClick,
                    onLongPress = onLongPress,
                    onReact = onReact,
                    onOpenAttachment = {
                        if (event.isViewableImage()) onOpenImage(event) else onOpenAttachment(event)
                    },
                    onShowPreview = onShowPreview,
                    onReplyPreviewClick = onReplyPreviewClick,
                    onVote = onVote,
                    onEndPoll = onEndPoll,
                    emotePaths = state.emotePathByMxc,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (inSelectionMode) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(event.eventId) {
                                detectTapGestures { onClick?.invoke() }
                            }
                    )
                }
            }

            if (!inSelectionMode) {
                Spacer(Modifier.height(Spacing.sm))
                Surface(
                    onClick = onReply,
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = Spacing.md, bottom = Spacing.sm)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_reply),
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
}

@Composable
private fun ThreadReplyMessage(
    event: MessageEvent,
    isMine: Boolean,
    isDm: Boolean,
    reactionSummaries: List<ReactionSummary>,
    avatarByUserId: Map<String, String>,
    replyThumbByEvent: Map<String, String>,
    thumbByEvent: Map<String, String>,
    audioFileByEvent: Map<String, String>,
    waveformByEvent: Map<String, List<Float>>,
    linkPreviewByEvent: Map<String, LinkPreview?>,
    linkPreviewImageByEvent: Map<String, String>,
    showReactionAvatars: Boolean,
    mentionProfilesByUserId: Map<String, MentionProfileUi> = emptyMap(),
    onMentionClick: ((String) -> Unit)? = null,
    onClick: (() -> Unit)?,
    isSelectionMode: Boolean,
    onReplyPreviewClick: (() -> Unit)? = null,
    onVote: (String) -> Unit,
    onEndPoll: () -> Unit,
    onSenderClick: (() -> Unit)?,
    emotePaths: Map<String, String>,
    reactionImagePaths: Map<String, String>,
    reactionShortcodes: Map<String, String>,
    onReact: (String) -> Unit,
    onLongPress: () -> Unit,
    onOpenAttachment: (() -> Unit)?,
    grouped: Boolean = false,
    groupedWithNext: Boolean = false,
    highlighted: Boolean = false,
    isSelected: Boolean = false,
    previewBlocked: Boolean = false,
    onShowPreview: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    highlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else -> MaterialTheme.colorScheme.surface
                }
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
                    isDm = isDm,
                    avatarPath = if (grouped) null else avatarByUserId[event.sender],
                    groupedWithPrev = grouped,
                    groupedWithNext = groupedWithNext,
                    reactions = reactionSummaries,
                    reactionAvatarsByUserId = avatarByUserId,
                    showReactionAvatars = showReactionAvatars,
                    threadCount = null,
                    variant = MessageBubbleVariant.ThreadReply,
                    previewBlocked = previewBlocked,
                    resolvedPreviewPath = event.thumbKey?.let { thumbByEvent[it] },
                    resolvedReplyPreviewPath = event.replyToEventId?.let {
                        replyThumbByEvent[it] ?: thumbByEvent[it]
                    },
                    resolvedAudioPath = audioFileByEvent[event.eventId],
                    resolvedAudioWaveform = waveformByEvent[event.eventId].orEmpty(),
                    resolvedLinkPreview = linkPreviewByEvent[event.eventId],
                    resolvedLinkPreviewImage = linkPreviewImageByEvent[event.eventId],
                    resolvedMentions = mentionsOf(event, mentionProfilesByUserId),
                    senderVisible = !grouped,
                    reactionImagePaths = reactionImagePaths,
                    reactionShortcodes = reactionShortcodes,
                )
            ).copy(
                sendState = event.sendState,
                editSendState = event.editSendState,
                redactionSendState = event.redactionSendState,
                isEdited = event.isEdited,
                poll = event.pollData,
            )
            Box {
                MessageBubble(
                    model = bubbleModel,
                    onMentionClick = onMentionClick,
                    onClick = onClick,
                    onLongPress = onLongPress,
                    onReact = onReact,
                    onOpenAttachment = onOpenAttachment,
                    onShowPreview = onShowPreview,
                    onVote = onVote,
                    onEndPoll = onEndPoll,
                    onReplyPreviewClick = onReplyPreviewClick,
                    onSenderClick = onSenderClick,
                    emotePaths = emotePaths
                )
                if (isSelectionMode) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(event.eventId) {
                                detectTapGestures { onClick?.invoke() }
                            }
                    )
                }
            }
            if (isMine) {
                when {
                    event.editSendState == SendState.Sending ||
                            event.editSendState == SendState.Retrying -> {
                        MessageStatusLine(
                            text = stringResource(Res.string.saving_edit),
                            isMine = true
                        )
                    }
                    event.redactionSendState == SendState.Sending ||
                            event.redactionSendState == SendState.Retrying -> {
                        MessageStatusLine(
                            text = stringResource(Res.string.deleting),
                            isMine = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadSelectionBottomBar(
    onForward: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = onForward) {
                Icon(painterResource(Res.drawable.ic_forward), null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.forward))
            }
            TextButton(onClick = onDelete) {
                Icon(painterResource(Res.drawable.ic_delete), null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.delete))
            }
        }
    }
}

@Composable
private fun EmptyThreadView(
    hasError: Boolean,
    onRetry: () -> Unit,
) {
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
                        painterResource(Res.drawable.ic_gesture),
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
                stringResource(
                    if (hasError) Res.string.failed_to_load_thread
                    else Res.string.the_thread_may_have_been_deleted_or_is_still_loading
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (hasError) {
                Spacer(Modifier.height(Spacing.lg))
                Button(onClick = onRetry) {
                    Text(stringResource(Res.string.retry))
                }
            }
        }
    }
}
