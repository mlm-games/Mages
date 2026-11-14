package org.mlm.mages.ui.screens

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import org.mlm.mages.ui.theme.Spacing
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.DirectoryUser
import org.mlm.mages.matrix.PublicRoom
import org.mlm.mages.matrix.PublicRoomTypeFilter
import org.mlm.mages.ui.components.core.StatusBanner
import org.mlm.mages.ui.components.core.BannerType
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.viewmodel.DirectJoinPreview
import org.mlm.mages.ui.viewmodel.DiscoverUi
import org.mlm.mages.ui.viewmodel.DiscoverViewModel
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.*

@Composable
fun DiscoverRoute(
    viewModel: DiscoverViewModel,
    onClose: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    DiscoverScreen(
        state = state,
        onQuery = viewModel::setQuery,
        onClose = onClose,
        onOpenUser = { u -> viewModel.openUser(u) },
        onOpenJoinPreview = { r -> viewModel.openJoinPreview(r) },
        onOpenJoinPreviewTarget = { target -> viewModel.openJoinPreview(target) },
        onRoomTypeFilterChange = viewModel::setRoomTypeFilter,
        onLoadMore = viewModel::loadMoreRooms
    )
}

@Composable
fun DiscoverScreen(
    state: DiscoverUi,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    onOpenUser: (DirectoryUser) -> Unit,
    onOpenJoinPreview: (PublicRoom) -> Unit,
    onOpenJoinPreviewTarget: (String) -> Unit,
    onRoomTypeFilterChange: (PublicRoomTypeFilter?) -> Unit,
    onLoadMore: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.discover)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = stringResource(Res.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        stringResource(
                            if (state.roomTypeFilter == PublicRoomTypeFilter.Spaces) {
                                Res.string.search_spaces
                            } else {
                                Res.string.search_users_or_rooms
                            }
                        )
                    )
                },
                placeholder = { Text(stringResource(Res.string.search_placeholder)) },
                singleLine = true,
                leadingIcon = {
                    Icon(painterResource(Res.drawable.ic_search), contentDescription = null)
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(painterResource(Res.drawable.ic_close), contentDescription = stringResource(Res.string.clear))
                        }
                    }
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.roomTypeFilter == null,
                    onClick = { onRoomTypeFilterChange(null) },
                    label = { Text(stringResource(Res.string.rooms_and_spaces)) },
                    leadingIcon = {
                        Icon(
                            painterResource(Res.drawable.ic_apps),
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    }
                )
                FilterChip(
                    selected = state.roomTypeFilter == PublicRoomTypeFilter.Rooms,
                    onClick = { onRoomTypeFilterChange(PublicRoomTypeFilter.Rooms) },
                    label = { Text(stringResource(Res.string.public_rooms)) },
                    leadingIcon = {
                        Icon(
                            painterResource(Res.drawable.ic_group),
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    }
                )
                FilterChip(
                    selected = state.roomTypeFilter == PublicRoomTypeFilter.Spaces,
                    onClick = { onRoomTypeFilterChange(PublicRoomTypeFilter.Spaces) },
                    label = { Text(stringResource(Res.string.spaces)) },
                    leadingIcon = {
                        Icon(
                            painterResource(Res.drawable.ic_workspaces),
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    }
                )
            }

            if (state.isBusy && !state.isPaging) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                // always show first if available
                state.directJoinCandidate?.let { target ->
                    item(key = "direct_join") {
                        DirectJoinCard(
                            preview = state.directJoinPreview,
                            target = target,
                            onOpen = { onOpenJoinPreviewTarget(target) },
                        )
                    }
                }

                if (state.users.isNotEmpty()) {
                    item(key = "users_header") {
                        Text(
                            stringResource(Res.string.users),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    items(state.users, key = { it.userId }) { user ->
                        UserListItem(
                            user = user,
                            avatarPath = user.avatarUrl?.let { state.avatarPathByUrl[it] },
                            onMessage = { onOpenUser(user) }
                        )
                    }
                }

                // Rooms section
                if (state.rooms.isNotEmpty()) {
                    item(key = "rooms_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(
                                    when (state.roomTypeFilter) {
                                        PublicRoomTypeFilter.Spaces -> Res.string.spaces
                                        PublicRoomTypeFilter.Rooms -> Res.string.public_rooms
                                        null -> Res.string.rooms_and_spaces
                                    }
                                ),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(Res.string.found, state.rooms.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(state.rooms, key = { it.roomId }) { room ->
                        RoomListItem(
                            room = room,
                            avatarPath = room.avatarUrl?.let { state.avatarPathByUrl[it] },
                            onClick = { onOpenJoinPreview(room) }
                        )
                    }

                    if (state.nextBatch != null) {
                        item(key = "load_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (state.isPaging) {
                                    LoadingIndicator(modifier = Modifier.size(24.dp))
                                } else {
                                    OutlinedButton(onClick = onLoadMore) {
                                        Icon(
                                            painterResource(Res.drawable.ic_expand_more),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(Res.string.load_more_rooms))
                                    }
                                }
                            }
                        }
                    }
                }

                if (!state.isBusy &&
                    state.users.isEmpty() &&
                    state.rooms.isEmpty() &&
                    state.directJoinCandidate == null &&
                    (state.query.isNotBlank() || state.roomTypeFilter != null)
                ) {
                    item(key = "empty") {
                        if (state.query.isNotBlank()) {
                            EmptySearchState(query = state.query)
                        } else {
                            FilterEmptyState(
                                title = stringResource(
                                    if (state.roomTypeFilter == PublicRoomTypeFilter.Spaces) {
                                        Res.string.no_spaces_found
                                    } else {
                                        Res.string.no_rooms_found
                                    }
                                )
                            )
                        }
                    }
                }

                if (!state.isBusy &&
                    state.rooms.isEmpty() &&
                    state.users.isEmpty() &&
                    state.query.isBlank() &&
                    state.roomTypeFilter == null
                ) {
                    item(key = "hint") {
                        SearchHintCard()
                    }
                }
            }

            StatusBanner(
                message = state.error,
                type = BannerType.ERROR
            )
        }
    }
}

@Composable
private fun DirectJoinCard(
    preview: DirectJoinPreview?,
    target: String,
    onOpen: () -> Unit,
) {
    val title = preview?.title ?: target
    val subtitle = preview?.subtitle ?: stringResource(Res.string.join_this_room)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painterResource(Res.drawable.ic_meeting_room),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.width(Spacing.lg))

            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(subtitle)
                if (preview?.title != null && preview.target != preview.title) {
                    Text(
                        preview.target,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.width(Spacing.lg))

            Icon(
                painterResource(Res.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UserListItem(
    user: DirectoryUser,
    avatarPath: String?,
    onMessage: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            headlineContent = {
                Text(
                    user.displayName ?: user.userId,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                if (!user.displayName.isNullOrBlank()) {
                    Text(
                        user.userId,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            leadingContent = {
                Avatar(
                    name = user.displayName ?: user.userId,
                    avatarPath = avatarPath,
                    size = 40.dp,
                    colorKey = user.userId
                )
            },
            trailingContent = {
                TextButton(onClick = onMessage) {
                    Text(stringResource(Res.string.message))
                }
            }
        )
    }
}

@Composable
private fun RoomListItem(
    room: PublicRoom,
    avatarPath: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (avatarPath != null) {
                Avatar(
                    name = room.name ?: room.alias ?: room.roomId,
                    avatarPath = avatarPath,
                    size = 40.dp
                )
            } else {
                Icon(
                    if (room.isSpace) painterResource(Res.drawable.ic_workspaces) else painterResource(Res.drawable.ic_group),
                    contentDescription = null
                )
            }

            Spacer(Modifier.width(Spacing.lg))

            Column(Modifier.weight(1f)) {
                Text(
                    text = room.name ?: room.alias ?: room.roomId,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (room.isSpace) {
                    Text(
                        stringResource(Res.string.spaces),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                room.alias?.let { alias ->
                    if (alias != room.name) {
                        Text(
                            alias,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                room.topic?.takeIf { it.isNotBlank() }?.let { topic ->
                    Text(
                        text = topic,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(Spacing.lg))

            Icon(
                painterResource(Res.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptySearchState(query: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painterResource(Res.drawable.ic_search_off),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(Res.string.no_results_for, query),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(Res.string.try_different_search_term),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FilterEmptyState(title: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painterResource(Res.drawable.ic_search_off),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SearchHintCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(Res.string.search_tips),
                style = MaterialTheme.typography.titleMedium
            )

            SearchHintRow(
                icon = painterResource(Res.drawable.ic_tag),
                example = "#linux:matrix.org",
                description = stringResource(Res.string.join_room_by_alias)
            )

            SearchHintRow(
                icon = painterResource(Res.drawable.ic_person),
                example = "@username:server.com",
                description = stringResource(Res.string.start_dm_with_user)
            )

            SearchHintRow(
                icon = painterResource(Res.drawable.ic_search),
                example = "programming",
                description = stringResource(Res.string.search_rooms_by_topic)
            )

            SearchHintRow(
                icon = painterResource(Res.drawable.ic_link),
                example = "https://matrix.to/#/...",
                description = stringResource(Res.string.paste_matrix_link)
            )
        }
    }
}

@Composable
private fun SearchHintRow(
    icon: Painter,
    example: String,
    description: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column {
            Text(
                example,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
