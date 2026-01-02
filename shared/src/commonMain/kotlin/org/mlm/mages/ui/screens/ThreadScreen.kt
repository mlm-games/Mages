package org.mlm.mages.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
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
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.ReactionChip
import org.mlm.mages.ui.ThreadUiState
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.core.LoadMoreButton
import org.mlm.mages.ui.components.core.StatusBanner
import org.mlm.mages.ui.components.core.BannerType
import org.mlm.mages.ui.components.core.formatDisplayName
import org.mlm.mages.ui.components.message.MessageBubble
import org.mlm.mages.ui.components.sheets.MessageActionSheet
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatTime
import org.mlm.mages.ui.viewmodel.ThreadViewModel

@Composable
fun ThreadRoute(
    viewModel: ThreadViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarManager: SnackbarManager = koinInject()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ThreadViewModel.Event.ShowError -> snackbarManager.showError(event.message)
                is ThreadViewModel.Event.ShowSuccess -> snackbarManager.show(event.message)
            }
        }
    }

    ThreadScreen(
        state = state,
        myUserId = viewModel.myUserId,
        onReact = viewModel::react,
        onBack = onBack,
        onLoadMore = viewModel::loadMore,
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
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    state: ThreadUiState,
    myUserId: String?,
    onReact: (MessageEvent, String) -> Unit,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onSend: () -> Unit,
    onInputChange: (String) -> Unit,
    onStartReply: (MessageEvent) -> Unit,
    onCancelReply: () -> Unit,
    onStartEdit: (MessageEvent) -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: suspend (MessageEvent) -> Boolean,
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
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            totalItems == 0 || lastVisible >= totalItems - 1
        }
    }

    // Auto-scroll when new message appears
    LaunchedEffect(state.replies.lastOrNull()?.itemId, isNearBottom) {
        if (isNearBottom && totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Scaffold(
        topBar = {
            ThreadTopBar(
                messageCount = state.messageCount,
                roomName = state.roomName,
                onBack = onBack
            )
        },
        bottomBar = {
            ThreadComposer(
                input = state.input,
                onInputChange = onInputChange,
                replyTo = state.replyingTo,
                editingEvent = state.editingEvent,
                onCancelReply = onCancelReply,
                onCancelEdit = onCancelEdit,
                onSend = onSend
            )
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
                                listState.animateScrollToItem(totalItems - 1)
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, "Scroll to bottom")
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Latest")
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedVisibility(visible = state.isLoading && !state.hasInitialLoad) {
                LinearProgressIndicator(
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
                            CircularProgressIndicator()
                            Spacer(Modifier.height(Spacing.lg))
                            Text(
                                "Loading thread...",
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
                        contentPadding = PaddingValues(bottom = Spacing.sm)
                    ) {
                        if (state.nextBatch != null) {
                            item(key = "load_more") {
                                LoadMoreButton(
                                    isLoading = state.isLoading,
                                    onClick = onLoadMore,
                                    text = "Load earlier messages"
                                )
                            }
                        }

                        state.rootMessage?.let { root ->
                            item(key = "root_${root.itemId}") {
                                ThreadRootMessage(
                                    state = state,
                                    event = root,
                                    isMine = root.sender == myUserId,
                                    reactionChips = state.reactionChips[root.eventId] ?: emptyList(),
                                    onReact = { emoji -> onReact(root, emoji) },
                                    onReply = { onStartReply(root) },
                                    onLongPress = { sheetEvent = root }
                                )
                            }

                            if (state.replies.isNotEmpty()) {
                                item(key = "divider") {
                                    ThreadDivider(replyCount = state.replies.size)
                                }
                            }
                        }

                        itemsIndexed(
                            items = state.replies,
                            key = { _, ev -> "reply_${ev.itemId}" }
                        ) { index, event ->
                            val prevEvent = state.replies.getOrNull(index - 1)
                            val shouldGroup = prevEvent != null &&
                                    prevEvent.sender == event.sender &&
                                    (event.timestampMs - prevEvent.timestampMs) < 300_000

                            ThreadReplyMessage(
                                event = event,
                                isMine = event.sender == myUserId,
                                reactionChips = state.reactionChips[event.eventId] ?: emptyList(),
                                onReact = { emoji -> onReact(event, emoji) },
                                onLongPress = { sheetEvent = event },
                                grouped = shouldGroup
                            )
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
            onMarkReadHere = { sheetEvent = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
                            "Thread",
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
    reactionChips: List<ReactionChip>,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onLongPress: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
                    "Thread started",
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
                    Text(
                        formatTime(event.timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))

            Surface(
                color = if (isMine)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    event.body,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(Spacing.md),
                    color = if (isMine)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }

            if (reactionChips.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.sm))
                ThreadReactionChipsRow(chips = reactionChips, onReact = onReact)
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
                        "Reply",
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
                "$replyCount ${if (replyCount == 1) "reply" else "replies"}",
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
    reactionChips: List<ReactionChip>,
    onReact: (String) -> Unit,
    onLongPress: () -> Unit,
    grouped: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            MessageBubble(
                isMine = isMine,
                body = event.body,
                sender = if (grouped) null else formatDisplayName(event.sender),
                timestamp = event.timestampMs,
                grouped = grouped,
                reactionChips = reactionChips,
                eventId = event.eventId,
                onReact = onReact,
                onLongPress = onLongPress,
                replyPreview = event.replyToBody,
                replySender = event.replyToSender?.let { formatDisplayName(it) },
                sendState = event.sendState,
                isEdited = event.isEdited
            )
        }
    }
}

@Composable
private fun ThreadReactionChipsRow(
    chips: List<ReactionChip>,
    onReact: (String) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        chips.take(6).forEach { chip ->
            Surface(
                onClick = { onReact(chip.key) },
                color = if (chip.mine)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(chip.key, style = MaterialTheme.typography.bodyMedium)
                    if (chip.count > 1) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${chip.count}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadComposer(
    input: String,
    onInputChange: (String) -> Unit,
    replyTo: MessageEvent?,
    editingEvent: MessageEvent?,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit,
    onSend: () -> Unit
) {
    val isEditing = editingEvent != null
    val hasAction = replyTo != null || isEditing

    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Column {
            // Reply/Edit preview banner
            AnimatedVisibility(visible = hasAction) {
                Surface(
                    color = if (isEditing)
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(
                                    if (isEditing)
                                        MaterialTheme.colorScheme.tertiary
                                    else
                                        MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(2.dp)
                                )
                        )
                        Spacer(Modifier.width(Spacing.md))

                        Icon(
                            if (isEditing) Icons.Default.Edit
                            else Icons.AutoMirrored.Filled.Reply,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isEditing)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(Spacing.sm))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (isEditing) "Editing message"
                                else "Replying to ${formatDisplayName(replyTo?.sender ?: "")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isEditing)
                                    MaterialTheme.colorScheme.tertiary
                                else
                                    MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                (if (isEditing) editingEvent.body else replyTo?.body) ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = if (isEditing) onCancelEdit else onCancelReply,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                if (isEditing) "Cancel edit" else "Cancel reply",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            when {
                                isEditing -> "Edit message…"
                                replyTo != null -> "Reply in thread…"
                                else -> "Add to thread…"
                            }
                        )
                    },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (isEditing)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )

                Spacer(Modifier.width(Spacing.sm))

                FilledIconButton(
                    onClick = onSend,
                    enabled = input.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isEditing)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.primary,
                        contentColor = if (isEditing)
                            MaterialTheme.colorScheme.onTertiary
                        else
                            MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        if (isEditing) Icons.Default.Edit
                        else Icons.AutoMirrored.Filled.Send,
                        if (isEditing) "Save" else "Send"
                    )
                }
            }
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
                color = MaterialTheme.colorScheme.surfaceVariant,
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
                "Thread not found",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "The thread may have been deleted or is still loading",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}