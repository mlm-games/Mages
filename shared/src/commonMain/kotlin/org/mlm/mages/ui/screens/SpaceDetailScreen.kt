package org.mlm.mages.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.core.LoadMoreButton
import org.mlm.mages.ui.components.core.SectionHeader
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.SpaceDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceDetailScreen(
    viewModel: SpaceDetailViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.space?.name ?: state.spaceName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${state.hierarchy.size} items",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.isLoading) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedVisibility(visible = state.isLoading && state.hierarchy.isEmpty()) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.space?.let { space ->
                SpaceHeaderCard(space = space)
            }

            when {
                state.isLoading && state.hierarchy.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }

                state.hierarchy.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.FolderOpen,
                        title = "This space is empty",
                        subtitle = "Add rooms or subspaces to organize your conversations"
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = Spacing.lg)
                    ) {
                        if (state.subspaces.isNotEmpty()) {
                            item(key = "header_subspaces") {
                                SectionHeader(
                                    title = "Spaces",
                                    count = state.subspaces.size
                                )
                            }
                            items(state.subspaces, key = { "sub_${it.roomId}" }) { child ->
                                val resolvedAvatar = state.avatarPathByRoomId[child.roomId] ?: child.avatarUrl
                                SpaceChildItem(
                                    child = child.copy(avatarUrl = resolvedAvatar),
                                    onClick = { viewModel.openChild(child) }
                                )
                            }
                        }

                        if (state.rooms.isNotEmpty()) {
                            item(key = "header_rooms") {
                                SectionHeader(
                                    title = "Rooms",
                                    count = state.rooms.size
                                )
                            }
                            items(state.rooms, key = { "room_${it.roomId}" }) { child ->
                                val resolvedAvatar = state.avatarPathByRoomId[child.roomId] ?: child.avatarUrl
                                SpaceChildItem(
                                    child = child.copy(avatarUrl = resolvedAvatar),
                                    onClick = { viewModel.openChild(child) }
                                )
                            }
                        }

                        if (state.nextBatch != null) {
                            item(key = "load_more") {
                                LoadMoreButton(
                                    isLoading = state.isLoadingMore,
                                    onClick = viewModel::loadMore
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpaceHeaderCard(space: SpaceInfo) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Workspaces,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(Modifier.width(Spacing.lg))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            space.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (space.isPublic) {
                            Spacer(Modifier.width(Spacing.sm))
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    "Public",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(
                                        horizontal = Spacing.sm,
                                        vertical = 2.dp
                                    )
                                )
                            }
                        }
                    }
                    Text(
                        "${space.memberCount} members",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            space.topic?.let { topic ->
                Spacer(Modifier.height(Spacing.md))
                Text(
                    topic,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SpaceChildItem(
    child: SpaceChildInfo,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    child.name ?: child.alias ?: child.roomId,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (child.suggested) {
                    Spacer(Modifier.width(Spacing.xs))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            "Suggested",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        },
        supportingContent = child.topic?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = {
            val displayName = child.name ?: child.alias ?: child.roomId
            Avatar(
                name = displayName,
                avatarPath = child.avatarUrl,
                size = 40.dp,
                shape = MaterialTheme.shapes.small
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${child.memberCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Spacing.xs))
                Icon(
                    Icons.Default.ChevronRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        modifier = Modifier.clickable { onClick() }
    )
}