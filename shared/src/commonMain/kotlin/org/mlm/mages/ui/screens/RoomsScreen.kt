package org.mlm.mages.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.mlm.mages.RoomSummary
import org.mlm.mages.ui.components.common.RoomListItem
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.core.SectionHeader
import org.mlm.mages.ui.components.core.ShimmerList
import org.mlm.mages.ui.components.core.StatusBanner
import org.mlm.mages.ui.components.core.BannerType
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.RoomsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(
    viewModel: RoomsViewModel = koinViewModel(),
    onOpenSecurity: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenInvites: () -> Unit,
    onOpenCreateRoom: () -> Unit,
    onOpenSpaces: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val hasAnyRooms = state.favouriteItems.isNotEmpty() ||
            state.normalItems.isNotEmpty() ||
            state.lowPriorityItems.isNotEmpty()

    val firstFavouriteId = state.favouriteItems.firstOrNull()?.roomId
    val firstNormalId = state.normalItems.firstOrNull()?.roomId

    LaunchedEffect(firstFavouriteId, firstNormalId) {
        if ((firstFavouriteId != null || firstNormalId != null) && listState.firstVisibleItemIndex > 0) {
            listState.animateScrollToItem(0)
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
            RoomsTopBar(
                offlineBanner = state.offlineBanner,
                syncBanner = state.syncBanner,
                isLoading = state.isLoading && state.allItems.isNotEmpty(),
                searchQuery = state.roomSearchQuery,
                unreadOnly = state.unreadOnly,
                onSearchChange = viewModel::setSearchQuery,
                onToggleUnreadOnly = viewModel::toggleUnreadOnly,
                onOpenSpaces = onOpenSpaces,
                onOpenSecurity = onOpenSecurity,
                onOpenDiscover = onOpenDiscover,
                onOpenInvites = onOpenInvites,
                onOpenCreateRoom = onOpenCreateRoom,
                onOpenSearch = onOpenSearch

            )
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
                    Icon(Icons.Default.KeyboardArrowUp, "Scroll to top")
                    Spacer(Modifier.width(8.dp))
                    Text("New activity")
                }
            }
        }
    ) { innerPadding ->
        when {
            state.isLoading && state.allItems.isEmpty() -> {
                ShimmerList(modifier = Modifier.fillMaxSize().padding(innerPadding))
            }

            !hasAnyRooms && state.offlineBanner != null -> {
                EmptyState(
                    icon = Icons.Default.CloudOff,
                    title = state.offlineBanner ?: "Offline",
                    subtitle = "Connect to the internet to load rooms. If you've opened this account before, your last room list will appear here instantly.",
                    modifier = Modifier.padding(innerPadding),
                    action = {
                        Button(onClick = viewModel::refresh) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(Spacing.sm))
                            Text("Retry")
                        }
                    }
                )
            }

            !hasAnyRooms -> {
                EmptyState(
                    icon = Icons.Default.MeetingRoom,
                    title = "No rooms found",
                    subtitle = if (state.roomSearchQuery.isBlank())
                        "Join a room to start chatting"
                    else
                        "No rooms match \"${state.roomSearchQuery}\"",
                    modifier = Modifier.padding(innerPadding),
                    action = if (state.roomSearchQuery.isBlank()) {
                        {
                            Button(onClick = onOpenDiscover) {
                                Icon(Icons.Default.Search, null)
                                Spacer(Modifier.width(Spacing.sm))
                                Text("Discover Rooms")
                            }
                        }
                    } else null
                )
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    if (state.favouriteItems.isNotEmpty()) {
                        item(key = "header_favourites") {
                            SectionHeader(
                                title = "Favourites",
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
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                            }
                            val resolved = state.roomAvatarPath[item.roomId] ?: item.avatarUrl
                            RoomListItem(
                                item = item.copy(avatarUrl = resolved),
                                onClick = { viewModel.openRoom(RoomSummary(item.roomId, item.name)) }
                            )
                        }
                    }

                    if (state.normalItems.isNotEmpty()) {
                        if (state.favouriteItems.isNotEmpty()) {
                            item(key = "header_rooms") {
                                SectionHeader(
                                    title = "Rooms",
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
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                            }
                            val resolved = state.roomAvatarPath[item.roomId] ?: item.avatarUrl
                            RoomListItem(
                                item = item.copy(avatarUrl = resolved),
                                onClick = { viewModel.openRoom(RoomSummary(item.roomId, item.name)) }
                            )
                        }
                    }

                    if (state.lowPriorityItems.isNotEmpty()) {
                        item(key = "header_low_priority") {
                            SectionHeader(
                                title = "Low Priority",
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
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                            }
                            val resolved = state.roomAvatarPath[item.roomId] ?: item.avatarUrl
                            RoomListItem(
                                item = item.copy(avatarUrl = resolved),
                                onClick = { viewModel.openRoom(RoomSummary(item.roomId, item.name)) },
                                modifier = Modifier.alpha(0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomsTopBar(
    offlineBanner: String?,
    syncBanner: String?,
    isLoading: Boolean,
    searchQuery: String,
    unreadOnly: Boolean,
    onSearchChange: (String) -> Unit,
    onToggleUnreadOnly: () -> Unit,
    onOpenSpaces: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenInvites: () -> Unit,
    onOpenCreateRoom: () -> Unit,
    onOpenSearch: () -> Unit
) {
    Column {
        TopAppBar(
            title = { Text("Rooms", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = {
                IconButton(onClick = onOpenSpaces) {
                    Icon(Icons.Default.Workspaces, "Spaces")
                }
                IconButton(onClick = onOpenSecurity) {
                    Icon(Icons.Default.Security, "Security")
                }
                IconButton(onClick = onOpenDiscover) {
                    Icon(Icons.Default.Explore, "Discover")
                }
                IconButton(onClick = onOpenInvites) {
                    Icon(Icons.Default.Mail, "Invites")
                }
                IconButton(onClick = onOpenCreateRoom) {
                    Icon(Icons.Default.Add, "New Room")
                }
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Default.Search, "Search messages")
                }
            }
        )

        // Connection banners
        StatusBanner(
            message = offlineBanner,
            type = BannerType.OFFLINE
        )

        if (offlineBanner == null) {
            StatusBanner(
                message = syncBanner,
                type = BannerType.LOADING
            )
        }

        AnimatedVisibility(visible = isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            placeholder = { Text("Search rooms...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, "Clear search")
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
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            FilterChip(
                selected = unreadOnly,
                onClick = onToggleUnreadOnly,
                label = { Text("Unread only") },
                leadingIcon = if (unreadOnly) {
                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                } else null
            )
        }
    }
}