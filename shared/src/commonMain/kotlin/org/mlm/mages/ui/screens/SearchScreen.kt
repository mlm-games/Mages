package org.mlm.mages.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.matrix.SearchHit
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatTime
import org.mlm.mages.ui.viewmodel.SearchViewModel

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onOpenResult: (roomId: String, eventId: String, roomName: String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val pagingItems = viewModel.searchResults.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    val isActuallySearching = state.query.length >= 2 && 
        pagingItems.loadState.refresh is LoadState.Loading

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SearchViewModel.Event.OpenResult -> {
                    onOpenResult(event.roomId, event.eventId, event.roomName)
                }
                is SearchViewModel.Event.ShowError -> {
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            SearchTopBar(
                query = state.query,
                scopedRoomName = state.scopedRoomName,
                isSearching = isActuallySearching,
                onQueryChange = viewModel::setQuery,
                onBack = onBack,
                focusRequester = focusRequester
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                state.query.length < 2 -> {
                    SearchPlaceholder(isScoped = state.scopedRoomId != null)
                }

                pagingItems.loadState.refresh is LoadState.Error -> {
                    val error = (pagingItems.loadState.refresh as LoadState.Error).error
                    EmptyState(
                        icon = Icons.Default.ErrorOutline,
                        title = stringResource(Res.string.search_error),
                        subtitle = error.message ?: "Unknown error"
                    )
                }

                pagingItems.itemCount == 0 &&
                    pagingItems.loadState.refresh is LoadState.NotLoading -> {
                    EmptyState(
                        icon = Icons.Default.SearchOff,
                        title = stringResource(Res.string.search_no_results),
                        subtitle = stringResource(Res.string.search_no_messages_for, state.query)
                    )
                }

                isActuallySearching -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(
                            count = pagingItems.itemCount,
                            key = pagingItems.itemKey { "${it.roomId}_${it.eventId}" }
                        ) { index ->
                            val hit = pagingItems[index] ?: return@items
                            SearchResultItem(
                                hit = hit,
                                showRoomName = state.scopedRoomId == null,
                                onClick = { viewModel.openResult(hit) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = Spacing.lg),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }

                        if (pagingItems.loadState.append is LoadState.Loading) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(Spacing.lg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    LoadingIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    scopedRoomName: String?,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    focusRequester: FocusRequester
) {
    Column {
        if (scopedRoomName != null) {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.search_in_room, scopedRoomName),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                    }
                }
            )
        }

        SearchBarDefaults.InputField(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = {},
            expanded = false,
            onExpandedChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                .focusRequester(focusRequester),
            placeholder = { Text(stringResource(Res.string.search_messages_placeholder)) },
            leadingIcon = {
                if (scopedRoomName == null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                    }
                } else {
                    Icon(Icons.Default.Search, null)
                }
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Clear, "Clear")
                        }
                    }
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 4.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        )

        AnimatedVisibility(visible = isSearching) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SearchPlaceholder(isScoped: Boolean) {
    EmptyState(
        icon = Icons.Default.Search,
        title = if (isScoped) stringResource(Res.string.search_this_room) else stringResource(Res.string.search_all_messages),
        subtitle = stringResource(Res.string.search_min_chars)
    )
}

@Composable
private fun SearchResultItem(
    hit: SearchHit,
    showRoomName: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = hit.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        overlineContent = if (showRoomName || hit.sender.isNotBlank()) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hit.sender.isNotBlank()) {
                        Text(
                            text = hit.sender.substringAfter('@').substringBefore(':'),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        } else null,
        supportingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hit.timestampMs > 0U) {
                    Text(
                        text = formatTime(hit.timestampMs.toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable { onClick() }
    )
}
