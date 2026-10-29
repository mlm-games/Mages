package org.mlm.mages.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import co.touchlab.kermit.Logger
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.RoomSwipeAction
import org.mlm.mages.matrix.allowsMediaPreviews
import org.mlm.mages.ui.util.dragToScrollIfEnabled
import org.mlm.mages.RoomSummary
import org.mlm.mages.ui.RoomTypeFilter
import org.mlm.mages.ui.components.common.RoomListItem
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.core.SectionHeader
import org.mlm.mages.ui.components.core.ShimmerList
import org.mlm.mages.ui.components.core.StatusBanner
import org.mlm.mages.ui.components.core.BannerType
import org.mlm.mages.ui.components.sheets.RoomActionsSheet
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.theme.Limits
import org.mlm.mages.ui.viewmodel.RoomsViewModel
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource
import mages.shared.generated.resources.*
import org.mlm.mages.ui.components.common.InviteListItem
import org.mlm.mages.ui.components.common.SwipeActionRow
import org.mlm.mages.ui.components.dialogs.DeclineInviteDialog
import mages.shared.generated.resources.Res

@Composable
fun RoomsScreen(
    viewModel: RoomsViewModel = koinViewModel(),
    onOpenSecurity: () -> Unit,
    onOpenStartChat: () -> Unit,
    onOpenSpaces: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAccountSwitcher: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val settings by settingsRepository.flow.collectAsState(initial = AppSettings())
    val drafts = remember(settings.roomDraftsJson) { decodeRoomDrafts(settings.roomDraftsJson) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var selectedRoom by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showLeaveConfirm by remember { mutableStateOf(false) }

    val hasAnyRooms = state.favouriteItems.isNotEmpty() ||
            state.normalItems.isNotEmpty() ||
            state.lowPriorityItems.isNotEmpty() ||
            state.inviteItems.isNotEmpty()



    val isInvitesFilter = state.typeFilter == RoomTypeFilter.Invites

    val firstFavouriteId = state.favouriteItems.firstOrNull()?.roomId
    val firstNormalId = state.normalItems.firstOrNull()?.roomId


    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .collect { layoutInfo ->
                val visible = layoutInfo.visibleItemsInfo
                if (visible.isNotEmpty()) {
                    val range = visible.first().index..visible.last().index
                    viewModel.updateVisibleRange(range, 60)
                }
            }
    }

    val showScrollToTopFab by remember(listState, state) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 2 &&
                    (state.favouriteItems.firstOrNull()?.let { (state.unread[it.roomId] ?: 0) > 0 } == true ||
                            state.normalItems.firstOrNull()?.let { (state.unread[it.roomId] ?: 0) > 0 } == true)
        }
    }

    Scaffold(
        topBar = {
            if (state.selectionMode) {
                RoomSelectionTopBar(
                    selectedCount = state.selectedRoomIds.size,
                    onClose = viewModel::clearRoomSelection,
                    onMarkRead = viewModel::bulkMarkRead,
                    onMarkUnread = viewModel::bulkMarkUnread,
                    onFavourite = { viewModel.bulkSetFavourite(true) },
                    onUnfavourite = { viewModel.bulkSetFavourite(false) },
                    onLowPriority = { viewModel.bulkSetLowPriority(true) },
                    onRemoveLowPriority = { viewModel.bulkSetLowPriority(false) },
                    onMute = { viewModel.bulkSetMuted(true) },
                    onUnmute = { viewModel.bulkSetMuted(false) },
                    onSelectAll = viewModel::selectAllVisibleRooms,
                    onLeave = { showLeaveConfirm = true },
                )
            } else {
                RoomsTopBar(
                    offlineBanner = state.offlineBanner,
                    syncBanner = state.syncBanner,
                    isLoading = state.isLoading && state.allItems.isNotEmpty(),
                    searchQuery = state.roomSearchQuery,
                    unreadOnly = state.unreadOnly,
                    typeFilter = state.typeFilter,
                    onSearchChange = viewModel::setSearchQuery,
                    onToggleUnreadOnly = viewModel::toggleUnreadOnly,
                    onSetTypeFilter = viewModel::setTypeFilter,
                    unreadChatCount = state.unreadChatCount,
                    unreadFavouritesCount = state.unreadFavouritesCount,
                    unreadGroupsCount = state.unreadGroupsCount,
                    unreadDmsCount = state.unreadDmsCount,
                    spacesUnreadCount = state.spacesUnreadCount,
                    showSwitchAccountButton = settings.showSwitchAccountButton,
                    onOpenSpaces = onOpenSpaces,
                    onOpenSecurity = onOpenSecurity,
                    onOpenStartChat = onOpenStartChat,
                    inviteCount = state.inviteItems.size,
                    onOpenSearch = onOpenSearch,
                    onOpenAccountSwitcher = onOpenAccountSwitcher,
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = showScrollToTopFab,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(Res.string.scroll_to_top))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.new_activity))
                }
            }
        }
    ) { innerPadding ->
        val listModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when {
            state.isLoading && state.allItems.isEmpty() -> {
                ShimmerList(modifier = listModifier)
            }

            !hasAnyRooms && state.offlineBanner != null -> {
                EmptyState(
                    icon = Icons.Default.CloudOff,
                    title = state.offlineBanner?.let { stringResource(it) } ?: stringResource(Res.string.offline),
                    subtitle = stringResource(Res.string.connect_to_internet),
                    modifier = Modifier.padding(innerPadding),
                    action = {
                        Button(onClick = viewModel::refresh) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(Spacing.sm))
                            Text(stringResource(Res.string.retry))
                        }
                    }
                )
            }

            !hasAnyRooms && state.allItems.isNotEmpty() &&
                    state.roomSearchQuery.isBlank() &&
                    !state.unreadOnly &&
                    state.typeFilter == RoomTypeFilter.All -> {
                EmptyState(
                    icon = Icons.Default.Workspaces,
                    title = stringResource(Res.string.rooms_hidden_in_spaces),
                    subtitle = stringResource(Res.string.rooms_hidden_in_spaces_subtitle),
                    modifier = Modifier.padding(innerPadding),
                    action = {
                        Button(onClick = onOpenSpaces) {
                            Icon(Icons.Default.Workspaces, null)
                            Spacer(Modifier.width(Spacing.sm))
                            Text(stringResource(Res.string.spaces))
                        }
                    }
                )
            }

            !hasAnyRooms -> {
                EmptyState(
                    icon = Icons.Default.MeetingRoom,
                    title = stringResource(Res.string.no_rooms_found),
                    subtitle = if (state.roomSearchQuery.isBlank())
                        stringResource(Res.string.join_a_room)
                    else
                        stringResource(Res.string.no_rooms_match, state.roomSearchQuery),
                    modifier = Modifier.padding(innerPadding),
                    action = if (state.roomSearchQuery.isBlank()) {
                        {
                            Button(onClick = onOpenStartChat) {
                                Icon(Icons.Default.Search, null)
                                Spacer(Modifier.width(Spacing.sm))
                                Text(stringResource(Res.string.discover_rooms))
                            }
                        }
                    } else null
                )
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = listModifier.dragToScrollIfEnabled(listState),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    if (state.favouriteItems.isNotEmpty()) {
                        item(key = "header_favourites") {
                            SectionHeader(
                                title = stringResource(Res.string.favourites),
                                count = state.favouriteItems.size,
                                icon = Icons.Default.Star
                            )
                        }
                        itemsIndexed(
                            state.favouriteItems,
                            key = { _, item -> "fav_${item.roomId}" }
                        ) { index, item ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = Spacing.lg),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                            val resolved = state.roomAvatarPath[item.roomId] ?: item.avatarUrl
                            SwipeActionRow(
                                swipeRightAction = if (state.selectionMode) RoomSwipeAction.Nothing else settings.swipeRightAction,
                                swipeLeftAction = if (state.selectionMode) RoomSwipeAction.Nothing else settings.swipeLeftAction,
                                onAction = { viewModel.onListSwipeAction(it, item.roomId) }
                            ) {
                                RoomListItem(
                                    item = item.copy(avatarUrl = resolved),
                                    onClick = {
                                        if (state.selectionMode) viewModel.toggleRoomSelection(item.roomId)
                                        else viewModel.openRoom(RoomSummary(item.roomId, item.name))
                                    },
                                    onLongClick = if (state.selectionMode) {
                                        null
                                    } else {
                                        { selectedRoom = item.name to item.roomId }
                                    },
                                    selectionMode = state.selectionMode,
                                    selected = item.roomId in state.selectedRoomIds,
                                    showSilentUnreadCount = settings.showSilentUnreadCount,
                                    draft = drafts[item.roomId],
                                    allowMediaPreviews = settings.mediaPreviews.allowsMediaPreviews(item.isDm)
                                )
                            }
                        }
                    }

                    if (state.normalItems.isNotEmpty()) {
                        if (state.favouriteItems.isNotEmpty()) {
                            item(key = "header_rooms") {
                                SectionHeader(
                                    title = stringResource(Res.string.rooms),
                                    count = state.normalItems.size,
                                    icon = Icons.Default.ChatBubble
                                )
                            }
                        }
                        itemsIndexed(
                            state.normalItems,
                            key = { _, item -> "room_${item.roomId}" }
                        ) { index, item ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = Spacing.lg),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                            val resolved = state.roomAvatarPath[item.roomId] ?: item.avatarUrl
                            SwipeActionRow(
                                swipeRightAction = if (state.selectionMode) RoomSwipeAction.Nothing else settings.swipeRightAction,
                                swipeLeftAction = if (state.selectionMode) RoomSwipeAction.Nothing else settings.swipeLeftAction,
                                onAction = { viewModel.onListSwipeAction(it, item.roomId) }
                            ) {
                                RoomListItem(
                                    item = item.copy(avatarUrl = resolved),
                                    onClick = {
                                        if (state.selectionMode) viewModel.toggleRoomSelection(item.roomId)
                                        else viewModel.openRoom(RoomSummary(item.roomId, item.name))
                                    },
                                    onLongClick = if (state.selectionMode) {
                                        null
                                    } else {
                                        { selectedRoom = item.name to item.roomId }
                                    },
                                    selectionMode = state.selectionMode,
                                    selected = item.roomId in state.selectedRoomIds,
                                    showSilentUnreadCount = settings.showSilentUnreadCount,
                                    draft = drafts[item.roomId],
                                    allowMediaPreviews = settings.mediaPreviews.allowsMediaPreviews(item.isDm)
                                )
                            }
                        }
                    }

                    if (state.lowPriorityItems.isNotEmpty()) {
                        item(key = "header_low_priority") {
                            SectionHeader(
                                title = stringResource(Res.string.low_priority),
                                count = state.lowPriorityItems.size,
                                icon = Icons.Default.ArrowDownward
                            )
                        }
                        itemsIndexed(
                            state.lowPriorityItems,
                            key = { _, item -> "low_${item.roomId}" }
                        ) { index, item ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = Spacing.lg),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                            val resolved = state.roomAvatarPath[item.roomId] ?: item.avatarUrl
                            SwipeActionRow(
                                swipeRightAction = if (state.selectionMode) RoomSwipeAction.Nothing else settings.swipeRightAction,
                                swipeLeftAction = if (state.selectionMode) RoomSwipeAction.Nothing else settings.swipeLeftAction,
                                onAction = { viewModel.onListSwipeAction(it, item.roomId) },
                                modifier = Modifier.alpha(0.6f)
                            ) {
                                RoomListItem(
                                    item = item.copy(avatarUrl = resolved),
                                    onClick = {
                                        if (state.selectionMode) viewModel.toggleRoomSelection(item.roomId)
                                        else viewModel.openRoom(RoomSummary(item.roomId, item.name))
                                    },
                                    onLongClick = if (state.selectionMode) {
                                        null
                                    } else {
                                        { selectedRoom = item.name to item.roomId }
                                    },
                                    selectionMode = state.selectionMode,
                                    selected = item.roomId in state.selectedRoomIds,
                                    showSilentUnreadCount = settings.showSilentUnreadCount,
                                    draft = drafts[item.roomId],
                                    allowMediaPreviews = settings.mediaPreviews.allowsMediaPreviews(item.isDm)
                                )
                            }
                        }
                    }

                    if (state.inviteItems.isNotEmpty()) {
                        item(key = "header_invites") {
                            SectionHeader(
                                title = stringResource(Res.string.invites),
                                count = state.inviteItems.size,
                                icon = Icons.Default.Mail
                            )
                        }
                        itemsIndexed(
                            state.inviteItems,
                            key = { _, item -> "invite_${item.roomId}" }
                        ) { index, item ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = Spacing.lg),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                            InviteListItem(
                                item = item,
                                onAccept = { viewModel.acceptInvite(item.roomId) },
                                onDecline = { viewModel.showDeclineInvite(item.roomId) }
                            )
                        }
                    }
                }
            }
        }
    }

    selectedRoom?.let { (name, roomId) ->
        RoomActionsSheet(
            roomName = name,
            roomId = roomId,
            isFavourite = roomId in state.favourites,
            isLowPriority = roomId in state.lowPriority,
            onDismiss = { selectedRoom = null },
            onMarkRead = { viewModel.markRead(roomId) },
            onToggleFavourite = { viewModel.toggleFavourite(roomId, roomId in state.favourites) },
            onToggleLowPriority = { viewModel.toggleLowPriority(roomId, roomId in state.lowPriority) },
            onSelect = { viewModel.startRoomSelection(roomId) },
        )
    }

    state.declineInviteRoomId?.let { roomId ->
        DeclineInviteDialog(
            roomName = state.declineInviteRoomName,
            inviterName = state.declineInviteInviterName,
            isLoading = state.isDecliningInvite,
            onDecline = viewModel::declineInvite,
            onDismiss = viewModel::hideDeclineInvite,
        )
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(stringResource(Res.string.leave_rooms_confirm_title)) },
            text = { Text(stringResource(Res.string.leave_rooms_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirm = false
                        viewModel.bulkLeaveRooms()
                    },
                ) {
                    Text(stringResource(Res.string.leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RoomSelectionTopBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onFavourite: () -> Unit,
    onUnfavourite: () -> Unit,
    onLowPriority: () -> Unit,
    onRemoveLowPriority: () -> Unit,
    onMute: () -> Unit,
    onUnmute: () -> Unit,
    onSelectAll: () -> Unit,
    onLeave: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    @Composable
    fun menuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { Icon(icon, contentDescription = null) },
            onClick = {
                menuExpanded = false
                onClick()
            },
        )
    }

    TopAppBar(
        title = {
            Text(
                stringResource(Res.string.selected_count, selectedCount),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, stringResource(Res.string.close))
            }
        },
        actions = {
            IconButton(onClick = onMarkRead) {
                Icon(Icons.Default.DoneAll, stringResource(Res.string.mark_read))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(Res.string.more_options))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    menuItem(Icons.Default.MarkEmailUnread, stringResource(Res.string.mark_as_unread), onMarkUnread)
                    menuItem(Icons.Default.Star, stringResource(Res.string.add_to_favorites), onFavourite)
                    menuItem(Icons.Default.StarBorder, stringResource(Res.string.remove_from_favorites), onUnfavourite)
                    menuItem(Icons.Default.ArrowDownward, stringResource(Res.string.low_priority), onLowPriority)
                    menuItem(Icons.Default.Remove, stringResource(Res.string.remove_from_low_priority), onRemoveLowPriority)
                    menuItem(Icons.Default.NotificationsOff, stringResource(Res.string.mute), onMute)
                    menuItem(Icons.Default.Notifications, stringResource(Res.string.unmute), onUnmute)
                    menuItem(Icons.Default.SelectAll, stringResource(Res.string.select_all), onSelectAll)
                    menuItem(Icons.AutoMirrored.Filled.Logout, stringResource(Res.string.leave_rooms), onLeave)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
    )
}

@Composable
private fun RoomsTopBar(
    offlineBanner: StringResource?,
    syncBanner: String?,
    isLoading: Boolean,
    searchQuery: String,
    unreadOnly: Boolean,
    typeFilter: RoomTypeFilter,
    inviteCount: Int,
    unreadChatCount: Int,
    unreadFavouritesCount: Int,
    unreadGroupsCount: Int,
    unreadDmsCount: Int,
    spacesUnreadCount: Int,
    showSwitchAccountButton: Boolean,
    onSearchChange: (String) -> Unit,
    onToggleUnreadOnly: () -> Unit,
    onSetTypeFilter: (RoomTypeFilter) -> Unit,
    onOpenSpaces: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenStartChat: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAccountSwitcher: () -> Unit
) {
    Column {
        TopAppBar(
            title = {
                Text(stringResource(Res.string.rooms), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            actions = {
                IconButton(onClick = onOpenSpaces) {
                    Box {
                        Icon(Icons.Default.Workspaces, stringResource(Res.string.spaces))
                        if (spacesUnreadCount > 0) {
                            Badge(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                            ) {
                                Text(
                                    if (spacesUnreadCount > Limits.unreadBadgeCap) "${Limits.unreadBadgeCap}+" else spacesUnreadCount.toString(),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = onOpenSecurity) {
                    Icon(Icons.Default.Settings, stringResource(Res.string.settings))
                }
                IconButton(onClick = onOpenStartChat) {
                    Icon(Icons.Default.Add, stringResource(Res.string.find_users_and_public_rooms))
                }
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Default.Search, stringResource(Res.string.search_messages))
                }
                if (showSwitchAccountButton) {
                    IconButton(onClick = onOpenAccountSwitcher) {
                        Icon(Icons.Default.SwitchAccount, stringResource(Res.string.account))
                    }
                }
            }
        )

        // Connection banners
        StatusBanner(
            message = offlineBanner?.let { stringResource(it) },
            type = BannerType.OFFLINE
        )

        if (offlineBanner == null) {
            StatusBanner(
                message = syncBanner,
                type = BannerType.LOADING
            )
        }

        AnimatedVisibility(visible = isLoading) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            placeholder = { Text(stringResource(Res.string.search_rooms)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, stringResource(Res.string.clear_search))
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large
        )

        // Filters
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            FilterChip(
                selected = unreadOnly,
                onClick = onToggleUnreadOnly,
                label = {
                    Text(
                        text = withCount(
                            stringResource(Res.string.unread_only),
                            unreadChatCount
                        )
                    )
                },
                leadingIcon = if (unreadOnly) {
                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                } else null
            )
            FilterChip(
                selected = typeFilter == RoomTypeFilter.Groups,
                onClick = { onSetTypeFilter(RoomTypeFilter.Groups) },
                label = { Text(withCount(stringResource(Res.string.groups), unreadGroupsCount, unreadOnly)) },
                leadingIcon = if (typeFilter == RoomTypeFilter.Groups) {
                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                } else null
            )
            FilterChip(
                selected = typeFilter == RoomTypeFilter.Dms,
                onClick = { onSetTypeFilter(RoomTypeFilter.Dms) },
                label = { Text(withCount(stringResource(Res.string.dms), unreadDmsCount, unreadOnly)) },
                leadingIcon = if (typeFilter == RoomTypeFilter.Dms) {
                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                } else null
            )
            FilterChip(
                selected = typeFilter == RoomTypeFilter.Invites,
                onClick = { onSetTypeFilter(RoomTypeFilter.Invites) },
                label = {
                    Text(
                        text = if (inviteCount > 0) {
                            "${stringResource(Res.string.invites)} ($inviteCount)"
                        } else {
                            stringResource(Res.string.invites)
                        }
                    )
                },
                leadingIcon = if (typeFilter == RoomTypeFilter.Invites) {
                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                } else null
            )
            FilterChip(
                selected = typeFilter == RoomTypeFilter.Favourites,
                onClick = { onSetTypeFilter(RoomTypeFilter.Favourites) },
                label = { Text(withCount(stringResource(Res.string.favourites), unreadFavouritesCount, unreadOnly)) },
                leadingIcon = if (typeFilter == RoomTypeFilter.Favourites) {
                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                } else null
            )
        }
    }
}

private fun withCount(label: String, count: Int, showCount : Boolean = true): String {
    if (count <= 0 || !showCount) return label
    return "$label ($count)"
}

private val draftsJson = Json { ignoreUnknownKeys = true }

private fun decodeRoomDrafts(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    return try {
        draftsJson.decodeFromString<Map<String, String>>(raw)
            .mapValues { (_, text) -> text.replace('\n', ' ').trim() }
            .filterValues { it.isNotBlank() }
    } catch (e: Exception) {
        Logger.w { "RoomsScreen: corrupt roomDraftsJson, ignoring: ${e.message}" }
        emptyMap()
    }
}
