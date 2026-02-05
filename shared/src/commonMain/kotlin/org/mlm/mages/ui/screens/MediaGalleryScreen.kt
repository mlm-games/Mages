package org.mlm.mages.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.mlm.mages.AttachmentKind
import org.mlm.mages.MessageEvent
import org.mlm.mages.platform.ShareContent
import org.mlm.mages.platform.rememberShareHandler
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.ExtractedLink
import org.mlm.mages.ui.viewmodel.MediaGalleryViewModel
import org.mlm.mages.ui.viewmodel.MediaTab
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGalleryScreen(
    viewModel: MediaGalleryViewModel,
    onBack: () -> Unit,
    onOpenAttachment: (MessageEvent) -> Unit,
    onForward: (List<String>) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    val shareHandler = rememberShareHandler()
    var selectedTab by remember { mutableStateOf(MediaTab.Images) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is MediaGalleryViewModel.Event.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is MediaGalleryViewModel.Event.ShowSuccess -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is MediaGalleryViewModel.Event.ShareFiles -> {
                    shareHandler(
                        ShareContent(
                            filePaths = event.paths,
                            mimeTypes = event.mimeTypes,
                            subject = "Mages"
                        )
                    )
                }
                is MediaGalleryViewModel.Event.OpenForwardPicker -> {
                    onForward(event.events)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (state.isSelectionMode) {
                SelectionTopBar(
                    selectedCount = state.selectedCount,
                    onClearSelection = viewModel::clearSelection,
                    onSelectAll = { viewModel.selectAll(selectedTab) }
                )
            } else {
                TopAppBar(
                    title = { Text("Media & Files") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = state.isSelectionMode,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                SelectionBottomBar(
                    onShare = viewModel::shareSelected,
                    onForward = viewModel::forwardSelected,
                    onDownload = viewModel::downloadSelected
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                MediaTab.entries.forEach { tab ->
                    val count = when (tab) {
                        MediaTab.Images -> state.images.size
                        MediaTab.Videos -> state.videos.size
                        MediaTab.Files -> state.files.size
                        MediaTab.Links -> viewModel.links.size
                    }
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text("${tab.name} ($count)") }
                    )
                }
            }

            when {
                state.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    when (selectedTab) {
                        MediaTab.Images -> MediaGrid(
                            items = state.images,
                            thumbnails = state.thumbnails,
                            selectedIds = state.selectedIds,
                            isSelectionMode = state.isSelectionMode,
                            isPaginating = state.isPaginatingBack,
                            hitStart = state.hitStart,
                            onLoadMore = viewModel::loadMore,
                            onItemClick = { event ->
                                if (state.isSelectionMode) {
                                    viewModel.toggleSelection(event.eventId)
                                } else {
                                    onOpenAttachment(event)
                                }
                            },
                            onItemLongClick = { event ->
                                viewModel.enterSelectionMode(event.eventId)
                            }
                        )
                        MediaTab.Videos -> MediaGrid(
                            items = state.videos,
                            thumbnails = state.thumbnails,
                            selectedIds = state.selectedIds,
                            isSelectionMode = state.isSelectionMode,
                            isPaginating = state.isPaginatingBack,
                            hitStart = state.hitStart,
                            onLoadMore = viewModel::loadMore,
                            onItemClick = { event ->
                                if (state.isSelectionMode) {
                                    viewModel.toggleSelection(event.eventId)
                                } else {
                                    onOpenAttachment(event)
                                }
                            },
                            onItemLongClick = { event ->
                                viewModel.enterSelectionMode(event.eventId)
                            }
                        )
                        MediaTab.Files -> FilesList(
                            items = state.files,
                            selectedIds = state.selectedIds,
                            isSelectionMode = state.isSelectionMode,
                            isPaginating = state.isPaginatingBack,
                            hitStart = state.hitStart,
                            onLoadMore = viewModel::loadMore,
                            onItemClick = { event ->
                                if (state.isSelectionMode) {
                                    viewModel.toggleSelection(event.eventId)
                                } else {
                                    onOpenAttachment(event)
                                }
                            },
                            onItemLongClick = { event ->
                                viewModel.enterSelectionMode(event.eventId)
                            }
                        )
                        MediaTab.Links -> LinksList(
                            links = viewModel.links,
                            isPaginating = state.isPaginatingBack,
                            hitStart = state.hitStart,
                            onLoadMore = viewModel::loadMore,
                            onLinkClick = { url ->
                                runCatching { uriHandler.openUri(url) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit
) {
    TopAppBar(
        title = { Text("$selectedCount selected") },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Default.Close, "Clear selection")
            }
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Default.SelectAll, "Select all")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    )
}

@Composable
private fun SelectionBottomBar(
    onShare: () -> Unit,
    onForward: () -> Unit,
    onDownload: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = onShare) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Share, null)
                    Text("Share", style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = onForward) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Filled.Forward, null)
                    Text("Forward", style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = onDownload) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Download, null)
                    Text("Download", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private sealed interface GalleryItem {
    data class MonthHeader(val label: String, val key: String) : GalleryItem
    data class Media(val event: MessageEvent) : GalleryItem
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    items: List<MessageEvent>,
    thumbnails: Map<String, String>,
    selectedIds: Set<String>,
    isSelectionMode: Boolean,
    isPaginating: Boolean,
    hitStart: Boolean,
    onLoadMore: () -> Unit,
    onItemClick: (MessageEvent) -> Unit,
    onItemLongClick: (MessageEvent) -> Unit
) {
    val gridState = rememberLazyGridState()

    // Group items by month (newest first)
    val galleryItems = remember(items) {
        buildGalleryItems(items.sortedByDescending { it.timestampMs })
    }

    val shouldPaginate by remember {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = layoutInfo.totalItemsCount
            !hitStart && !isPaginating && total > 0 && lastVisible >= total - 8
        }
    }

    LaunchedEffect(shouldPaginate) {
        if (shouldPaginate) {
            onLoadMore()
        }
    }

    // Auto-paginate when empty and not hit start yet
    LaunchedEffect(galleryItems.isEmpty(), hitStart, isPaginating) {
        if (galleryItems.isEmpty() && !hitStart && !isPaginating) {
            onLoadMore()
        }
    }

    if (galleryItems.isEmpty()) {
        if (hitStart) {
            EmptyTabContent(icon = Icons.Default.Image, text = "No media found")
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        return
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 110.dp),
        contentPadding = PaddingValues(Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        galleryItems.forEach { item ->
            when (item) {
                is GalleryItem.MonthHeader -> {
                    item(
                        key = item.key,
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        MonthHeaderRow(label = item.label)
                    }
                }

                is GalleryItem.Media -> {
                    item(key = item.event.eventId) {
                        MediaGridItem(
                            event = item.event,
                            thumbPath = thumbnails[item.event.eventId],
                            isSelected = item.event.eventId in selectedIds,
                            isSelectionMode = isSelectionMode,
                            onClick = { onItemClick(item.event) },
                            onLongClick = { onItemLongClick(item.event) }
                        )
                    }
                }
            }
        }

        // Loading indicator at bottom (full width)
        if (isPaginating) {
            item(
                key = "loading",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

@Suppress("NewApi")
private fun buildGalleryItems(sortedEvents: List<MessageEvent>): List<GalleryItem> {
    if (sortedEvents.isEmpty()) return emptyList()

    val formatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    val zoneId = ZoneId.systemDefault()

    return buildList {
        var currentMonth: String? = null

        for (event in sortedEvents) {
            val instant = Instant.ofEpochMilli(event.timestampMs)
            val monthLabel = formatter.format(instant.atZone(zoneId))

            if (monthLabel != currentMonth) {
                currentMonth = monthLabel
                add(GalleryItem.MonthHeader(label = monthLabel, key = "header_$monthLabel"))
            }
            add(GalleryItem.Media(event))
        }
    }
}

@Composable
private fun MonthHeaderRow(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGridItem(
    event: MessageEvent,
    thumbPath: String?,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraSmall)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        // Thumbnail or placeholder
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                if (thumbPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalPlatformContext.current)
                            .data(File(thumbPath))
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = if (event.attachment?.kind == AttachmentKind.Video)
                            Icons.Default.VideoFile else Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // Video indicator
        if (event.attachment?.kind == AttachmentKind.Video) {
            Surface(
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Duration badge
            event.attachment!!.durationMs?.let { ms ->
                Surface(
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                ) {
                    Text(
                        text = formatDuration(ms),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // Selection overlay
        AnimatedVisibility(
            visible = isSelectionMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
        ) {
            Surface(
                color = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                shape = CircleShape,
                shadowElevation = 2.dp,
                modifier = Modifier.size(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Selection tint overlay
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        val mins = minutes % 60
        "%d:%02d:%02d".format(hours, mins, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilesList(
    items: List<MessageEvent>,
    selectedIds: Set<String>,
    isSelectionMode: Boolean,
    isPaginating: Boolean,
    hitStart: Boolean,
    onLoadMore: () -> Unit,
    onItemClick: (MessageEvent) -> Unit,
    onItemLongClick: (MessageEvent) -> Unit
) {
    val listState = rememberLazyListState()

    val shouldPaginate by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = layoutInfo.totalItemsCount
            !hitStart && !isPaginating && total > 0 && lastVisible >= total - 8
        }
    }

    LaunchedEffect(shouldPaginate) {
        if (shouldPaginate) {
            onLoadMore()
        }
    }

    LaunchedEffect(items.isEmpty(), hitStart, isPaginating) {
        if (items.isEmpty() && !hitStart && !isPaginating) {
            onLoadMore()
        }
    }

    if (items.isEmpty()) {
        if (hitStart) {
            EmptyTabContent(icon = Icons.Default.Folder, text = "No files found")
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        items(items.reversed(), key = { it.eventId }) { event ->
            val isSelected = event.eventId in selectedIds

            FileListItem(
                event = event,
                isSelected = isSelected,
                isSelectionMode = isSelectionMode,
                onClick = { onItemClick(event) },
                onLongClick = { onItemLongClick(event) }
            )
        }

        if (isPaginating) {
            item(key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItem(
    event: MessageEvent,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.shapes.medium
                ) else Modifier
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection checkbox
            AnimatedVisibility(visible = isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.body.ifBlank { "File" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = event.sender.substringAfter('@').substringBefore(':'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    event.attachment?.sizeBytes?.let { size ->
                        Text(
                            text = formatFileSize(size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!isSelectionMode) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun LinksList(
    links: List<ExtractedLink>,
    isPaginating: Boolean,
    hitStart: Boolean,
    onLoadMore: () -> Unit,
    onLinkClick: (String) -> Unit
) {
    val listState = rememberLazyListState()

    val shouldPaginate by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = layoutInfo.totalItemsCount
            !hitStart && !isPaginating && total > 0 && lastVisible >= total - 8
        }
    }

    LaunchedEffect(shouldPaginate) {
        if (shouldPaginate) {
            onLoadMore()
        }
    }

    // Auto-paginate when empty and not hit start yet
    LaunchedEffect(links.isEmpty(), hitStart, isPaginating) {
        if (links.isEmpty() && !hitStart && !isPaginating) {
            onLoadMore()
        }
    }

    if (links.isEmpty()) {
        if (hitStart) {
            EmptyTabContent(icon = Icons.Default.Link, text = "No links found")
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        items(links, key = { "${it.eventId}_${it.url}" }) { link ->
            LinkListItem(link = link, onClick = { onLinkClick(link.url) })
        }

        if (isPaginating) {
            item(key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun LinkListItem(
    link: ExtractedLink,
    onClick: () -> Unit
) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = link.url,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = link.sender.substringAfter('@').substringBefore(':'),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}


@Composable
private fun EmptyTabContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}