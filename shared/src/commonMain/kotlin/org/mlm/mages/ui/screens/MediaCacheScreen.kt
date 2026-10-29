package org.mlm.mages.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mages.shared.generated.resources.*
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.back
import mages.shared.generated.resources.files
import mages.shared.generated.resources.media_cache
import mages.shared.generated.resources.media_cache_audio
import mages.shared.generated.resources.media_cache_by_chat
import mages.shared.generated.resources.media_cache_by_type
import mages.shared.generated.resources.media_cache_cache_limit
import mages.shared.generated.resources.media_cache_clear
import mages.shared.generated.resources.media_cache_clear_chat_body
import mages.shared.generated.resources.media_cache_clear_chat_title
import mages.shared.generated.resources.media_cache_clear_confirm_body
import mages.shared.generated.resources.media_cache_clear_confirm_title
import mages.shared.generated.resources.media_cache_clear_media_cache
import mages.shared.generated.resources.media_cache_clear_older
import mages.shared.generated.resources.media_cache_clear_older_confirm
import mages.shared.generated.resources.media_cache_days
import mages.shared.generated.resources.media_cache_downloaded_media
import mages.shared.generated.resources.media_cache_images
import mages.shared.generated.resources.media_cache_keep_forever
import mages.shared.generated.resources.media_cache_keep_media
import mages.shared.generated.resources.media_cache_max_file
import mages.shared.generated.resources.media_cache_of_limit
import mages.shared.generated.resources.media_cache_other
import mages.shared.generated.resources.media_cache_storage_used
import mages.shared.generated.resources.media_cache_thumbnails
import mages.shared.generated.resources.media_cache_unlimited
import mages.shared.generated.resources.media_cache_videos
import mages.shared.generated.resources.messages
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.mlm.mages.RoomSummary
import org.mlm.mages.matrix.MediaCacheOverview
import org.mlm.mages.matrix.MediaItem
import org.mlm.mages.matrix.MediaRetentionPolicyInfo
import org.mlm.mages.matrix.MediaTypeMetrics
import org.mlm.mages.matrix.RoomStorageUsage
import org.mlm.mages.ui.components.dialogs.ConfirmationDialog
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatBytes
import org.mlm.mages.ui.viewmodel.MediaCacheUiState
import org.mlm.mages.ui.viewmodel.MediaCacheViewModel

@Composable
fun MediaCacheRoute(onBack: () -> Unit) {
    val viewModel: MediaCacheViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    MediaCacheScreen(
        state = state,
        onBack = onBack,
        onClearCache = viewModel::clearCache,
        onClearRoom = viewModel::clearRoomMedia,
        onClearOlderThan = viewModel::clearCacheOlderThan,
        onKeepMediaChange = viewModel::setKeepMediaFor,
        onCacheLimitChange = viewModel::setCacheLimit,
        onMaxFileSizeChange = viewModel::setMaxFileSize,
        onOpenMediaList = viewModel::openMediaList,
        onCloseMediaList = viewModel::closeMediaList,
        onToggleMediaSelection = viewModel::toggleMediaSelection,
        onSelectAllMedia = viewModel::selectAllMedia,
        onClearSelectedMedia = viewModel::clearSelectedMedia,
        onClearMediaType = viewModel::clearMediaType,
        onClearMediaAboveSize = viewModel::clearMediaAboveSize,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCacheScreen(
    state: MediaCacheUiState,
    onBack: () -> Unit,
    onClearCache: () -> Unit,
    onClearRoom: (String) -> Unit,
    onClearOlderThan: (Long) -> Unit,
    onKeepMediaChange: (Long?) -> Unit,
    onCacheLimitChange: (ULong?) -> Unit,
    onMaxFileSizeChange: (ULong?) -> Unit,
    onOpenMediaList: (String?, String?) -> Unit,
    onCloseMediaList: () -> Unit,
    onToggleMediaSelection: (String) -> Unit,
    onSelectAllMedia: () -> Unit,
    onClearSelectedMedia: () -> Unit,
    onClearMediaType: (String) -> Unit,
    onClearMediaAboveSize: (ULong) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfirm by remember { mutableStateOf(false) }
    var showOlderMenu by remember { mutableStateOf(false) }
    var showClearAboveMenu by remember { mutableStateOf(false) }
    var pendingOlderThan by remember { mutableStateOf<Long?>(null) }
    var pendingClearRoom by remember { mutableStateOf<RoomSummary?>(null) }
    var pendingClearType by remember { mutableStateOf<String?>(null) }
    var pendingClearAboveSize by remember { mutableStateOf<ULong?>(null) }

    if (showConfirm) {
        ConfirmationDialog(
            title = stringResource(Res.string.media_cache_clear_confirm_title),
            message = stringResource(Res.string.media_cache_clear_confirm_body),
            onConfirm = {
                showConfirm = false
                onClearCache()
            },
            onDismiss = { showConfirm = false },
        )
    }

    pendingClearRoom?.let { room ->
        ConfirmationDialog(
            title = stringResource(Res.string.media_cache_clear_chat_title),
            message = stringResource(Res.string.media_cache_clear_chat_body, room.name),
            isDestructive = true,
            isLoading = state.isClearing,
            onConfirm = {
                pendingClearRoom = null
                onClearRoom(room.id)
            },
            onDismiss = { pendingClearRoom = null },
        )
    }

    pendingOlderThan?.let { days ->
        ConfirmationDialog(
            title = stringResource(Res.string.media_cache_clear_older_confirm, days.toInt()),
            message = stringResource(Res.string.media_cache_clear_confirm_body),
            onConfirm = {
                pendingOlderThan = null
                onClearOlderThan(days)
            },
            onDismiss = { pendingOlderThan = null },
        )
    }

    pendingClearType?.let { kind ->
        ConfirmationDialog(
            title = stringResource(
                Res.string.media_cache_clear_type_title,
                mediaKindLabel(kind),
            ),
            message = stringResource(Res.string.media_cache_clear_type_body),
            isDestructive = true,
            isLoading = state.isClearing,
            onConfirm = {
                pendingClearType = null
                onClearMediaType(kind)
            },
            onDismiss = { pendingClearType = null },
        )
    }

    pendingClearAboveSize?.let { minSize ->
        val sizeLabel = formatBytes(minSize.toLong())
        ConfirmationDialog(
            title = stringResource(Res.string.media_cache_clear_above_title, sizeLabel),
            message = stringResource(Res.string.media_cache_clear_above_body, sizeLabel),
            isDestructive = true,
            isLoading = state.isClearing,
            onConfirm = {
                pendingClearAboveSize = null
                onClearMediaAboveSize(minSize)
            },
            onDismiss = { pendingClearAboveSize = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.media_cache)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                }
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            StorageSummaryCard(
                overview = state.overview,
                limitBytes = state.policy?.maxCacheSizeBytes,
                isLoading = state.isLoading,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            )

            val chatRows = state.rooms
                .mapNotNull { room ->
                    val usage = state.usageById[room.id] ?: return@mapNotNull null
                    if (usage.bytes == 0uL) null else room to usage
                }
                .sortedByDescending { it.second.bytes }

            MediaTypeCard(
                usage = state.types,
                onOpen = { kind -> onOpenMediaList(null, kind) },
                onClear = { pendingClearType = it },
                modifier = Modifier.padding(top = Spacing.lg),
            )

            if (!state.isLoading && chatRows.isNotEmpty()) {
                ByChatCard(
                    rows = chatRows,
                    totalBytes = state.overview?.totalBytes ?: 0uL,
                    onOpen = { room -> onOpenMediaList(room.id, null) },
                    onClear = { pendingClearRoom = it },
                    modifier = Modifier.padding(top = Spacing.lg),
                )
            }

            val policy = state.policy ?: MediaRetentionPolicyInfo()
            val policyEnabled = !state.isLoading && !state.isSavingPolicy && !state.isClearing

            val keepOptions: List<Pair<String, Long?>> = listOf(
                stringResource(Res.string.media_cache_keep_forever) to null,
                stringResource(Res.string.media_cache_days, 7) to 7L,
                stringResource(Res.string.media_cache_days, 30) to 30L,
                stringResource(Res.string.media_cache_days, 90) to 90L,
            )

            val cacheLimitOptions: List<Pair<String, ULong?>> = listOf(
                stringResource(Res.string.media_cache_unlimited) to null,
                formatBytes(200L * 1024 * 1024) to 200uL * 1024uL * 1024uL,
                formatBytes(500L * 1024 * 1024) to 500uL * 1024uL * 1024uL,
                formatBytes(1024L * 1024 * 1024) to 1024uL * 1024uL * 1024uL,
                formatBytes(2048L * 1024 * 1024) to 2048uL * 1024uL * 1024uL,
            )

            val maxFileOptions: List<Pair<String, ULong?>> = listOf(
                stringResource(Res.string.media_cache_unlimited) to null,
                formatBytes(10L * 1024 * 1024) to 10uL * 1024uL * 1024uL,
                formatBytes(25L * 1024 * 1024) to 25uL * 1024uL * 1024uL,
                formatBytes(50L * 1024 * 1024) to 50uL * 1024uL * 1024uL,
                formatBytes(100L * 1024 * 1024) to 100uL * 1024uL * 1024uL,
            )

            HorizontalDivider(Modifier.padding(top = Spacing.lg))

            OptionRow(
                label = stringResource(Res.string.media_cache_keep_media),
                value = policy.lastAccessExpiryDays?.toLong(),
                options = keepOptions,
                fallback = policy.lastAccessExpiryDays
                    ?.let { days -> stringResource(Res.string.media_cache_days, days.toInt()) }
                    ?: stringResource(Res.string.media_cache_keep_forever),
                enabled = policyEnabled,
                onSelect = onKeepMediaChange,
            )

            OptionRow(
                label = stringResource(Res.string.media_cache_cache_limit),
                value = policy.maxCacheSizeBytes,
                options = cacheLimitOptions,
                fallback = policy.maxCacheSizeBytes
                    ?.let { bytes -> formatBytes(bytes.toLong()) }
                    ?: stringResource(Res.string.media_cache_unlimited),
                enabled = policyEnabled,
                onSelect = onCacheLimitChange,
            )

            OptionRow(
                label = stringResource(Res.string.media_cache_max_file),
                value = policy.maxFileSizeBytes,
                options = maxFileOptions,
                fallback = policy.maxFileSizeBytes
                    ?.let { bytes -> formatBytes(bytes.toLong()) }
                    ?: stringResource(Res.string.media_cache_unlimited),
                enabled = policyEnabled,
                onSelect = onMaxFileSizeChange,
            )

            HorizontalDivider(Modifier.padding(top = Spacing.sm))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { showOlderMenu = true },
                        enabled = !state.isClearing && !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(Res.string.media_cache_clear_older))
                    }

                    DropdownMenu(
                        expanded = showOlderMenu,
                        onDismissRequest = { showOlderMenu = false },
                    ) {
                        listOf(7L, 30L, 90L).forEach { days ->
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.media_cache_days, days.toInt())) },
                                onClick = {
                                    showOlderMenu = false
                                    pendingOlderThan = days
                                },
                            )
                        }
                    }
                }

                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { showClearAboveMenu = true },
                        enabled = !state.isClearing && !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(Res.string.media_cache_clear_above))
                    }

                    DropdownMenu(
                        expanded = showClearAboveMenu,
                        onDismissRequest = { showClearAboveMenu = false },
                    ) {
                        listOf(5L, 10L, 25L, 50L, 100L).forEach { megabytes ->
                            DropdownMenuItem(
                                text = {
                                    Text(formatBytes(megabytes * 1024 * 1024))
                                },
                                onClick = {
                                    showClearAboveMenu = false
                                    pendingClearAboveSize =
                                        megabytes.toULong() * 1024uL * 1024uL
                                },
                            )
                        }
                    }
                }

                Button(
                    onClick = { showConfirm = true },
                    enabled = !state.isClearing && !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    if (state.isClearing) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.padding(end = Spacing.sm),
                            color = MaterialTheme.colorScheme.onError,
                        )
                    }
                    Text(stringResource(Res.string.media_cache_clear_media_cache))
                }

                state.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (state.mediaItemsOpen) {
        MediaItemsDialog(
            state = state,
            roomName = state.rooms.firstOrNull { it.id == state.mediaItemsRoomId }?.name,
            onDismiss = onCloseMediaList,
            onToggle = onToggleMediaSelection,
            onSelectAll = onSelectAllMedia,
            onClearSelected = onClearSelectedMedia,
        )
    }
}

@Composable
private fun StorageSummaryCard(
    overview: MediaCacheOverview?,
    limitBytes: ULong?,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val usedBytes = overview?.totalBytes
    val known = overview?.storeBytes != null
    val limit = limitBytes?.toLong()
    val progress = when {
        isLoading -> 0f
        limit != null && limit > 0 && known -> ((usedBytes ?: 0uL).toLong().toFloat() / limit).coerceIn(0f, 1f)
        known -> 1f
        else -> 0f
    }
    val usedText = if (known && usedBytes != null) formatBytes(usedBytes.toLong()) else null
    val limitText = when {
        isLoading || !known -> null
        limit != null -> stringResource(Res.string.media_cache_of_limit, formatBytes(limit))
        else -> stringResource(Res.string.media_cache_unlimited)
    }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StorageRing(
                progress = progress,
                usedText = usedText,
                limitText = limitText,
                loading = isLoading,
            )

            val stats = mutableListOf<Pair<String, ULong>>()
            if (!isLoading && overview != null) {
                val storeBytes = overview.storeBytes
                if (storeBytes != null) {
                    stats.add(stringResource(Res.string.media_cache_downloaded_media) to storeBytes)
                }
                val eventBytes = overview.eventBytes
                if (eventBytes != null) {
                    stats.add(stringResource(Res.string.messages) to eventBytes)
                }
                stats.add(stringResource(Res.string.media_cache_thumbnails) to overview.thumbnailBytes)
            }

            if (stats.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.lg))
                HorizontalDivider()
                Spacer(Modifier.height(Spacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    stats.forEach { (label, value) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = formatBytes(value.toLong()),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageRing(
    progress: Float,
    usedText: String?,
    limitText: String?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "mediaCacheStorageProgress",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val arcColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
    )

    Box(
        modifier = modifier
            .size(180.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(animatedProgress, 0f..1f) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = 16.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2f
            drawCircle(
                color = trackColor,
                radius = radius,
                style = Stroke(strokeWidth, cap = StrokeCap.Round),
            )
            if (animatedProgress > 0.001f) {
                drawArc(
                    brush = Brush.sweepGradient(arcColors, center = center),
                    startAngle = -90f,
                    sweepAngle = animatedProgress * 360f,
                    useCenter = false,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                )
            }
        }

        if (loading) {
            CircularWavyProgressIndicator()
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = usedText ?: "-",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(Res.string.media_cache_storage_used),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                limitText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class MediaTypeRow(
    val label: String,
    val kind: String,
    val bytes: ULong,
    val count: ULong,
)

@Composable
private fun MediaTypeCard(
    usage: MediaTypeMetrics,
    onOpen: (String) -> Unit,
    onClear: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = listOf(
        MediaTypeRow(
            label = stringResource(Res.string.media_cache_images),
            kind = "image",
            bytes = usage.images.size,
            count = usage.images.count,
        ),
        MediaTypeRow(
            label = stringResource(Res.string.media_cache_videos),
            kind = "video",
            bytes = usage.videos.size,
            count = usage.videos.count,
        ),
        MediaTypeRow(
            label = stringResource(Res.string.media_cache_audio),
            kind = "audio",
            bytes = usage.audio.size,
            count = usage.audio.count,
        ),
        MediaTypeRow(
            label = stringResource(Res.string.files),
            kind = "file",
            bytes = usage.files.size,
            count = usage.files.count,
        ),
        MediaTypeRow(
            label = stringResource(Res.string.media_cache_other),
            kind = "other",
            bytes = usage.other.size,
            count = usage.other.count,
        ),
    ).filter { it.bytes > 0uL }

    if (rows.isEmpty()) return

    val totalCount = rows.fold(0uL) { total, row -> total + row.count }
    val title = if (totalCount > 0uL) {
        stringResource(
            Res.string.media_cache_by_type_count,
            mediaItemCountLabel(totalCount),
        )
    } else {
        stringResource(Res.string.media_cache_by_type)
    }
    val clearLabel = stringResource(Res.string.media_cache_clear)

    BreakdownCard(
        title = title,
        modifier = modifier,
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(Modifier.padding(start = Spacing.lg))
            }
            BreakdownRow(
                label = row.label,
                detail = row.count.takeIf { it > 0uL }?.let { mediaItemCountLabel(it) },
                bytes = row.bytes,
                totalBytes = usage.totalBytes,
                onClick = { onOpen(row.kind) },
                trailing = {
                    IconButton(onClick = { onClear(row.kind) }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = clearLabel,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun mediaItemCountLabel(count: ULong): String =
    if (count == 1uL) {
        stringResource(Res.string.media_cache_item)
    } else {
        stringResource(Res.string.media_cache_items, count.toInt())
    }

@Composable
private fun ByChatCard(
    rows: List<Pair<RoomSummary, RoomStorageUsage>>,
    totalBytes: ULong,
    onOpen: (RoomSummary) -> Unit,
    onClear: (RoomSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filesLabel = stringResource(Res.string.files)
    val messagesLabel = stringResource(Res.string.messages)
    val clearLabel = stringResource(Res.string.media_cache_clear)

    BreakdownCard(
        title = stringResource(Res.string.media_cache_by_chat),
        modifier = modifier,
    ) {
        rows.forEachIndexed { index, (room, usage) ->
            if (index > 0) {
                HorizontalDivider(Modifier.padding(start = Spacing.lg))
            }
            BreakdownRow(
                label = room.name,
                detail = buildString {
                    if (usage.files > 0uL) {
                        append(usage.files).append(' ').append(filesLabel)
                    }
                    if (usage.messages > 0uL) {
                        if (isNotEmpty()) append(" · ")
                        append(usage.messages).append(' ').append(messagesLabel)
                    }
                }.takeIf { it.isNotEmpty() },
                bytes = usage.bytes,
                totalBytes = totalBytes,
                onClick = { onOpen(room) },
                trailing = {
                    IconButton(onClick = { onClear(room) }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = clearLabel,
                        )
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaItemsDialog(
    state: MediaCacheUiState,
    roomName: String?,
    onDismiss: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelected: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val kind = state.mediaItemsKind
    val roomId = state.mediaItemsRoomId

    val title = when {
        kind != null -> stringResource(
            Res.string.media_cache_media_list_type,
            mediaKindLabel(kind),
        )
        roomId != null -> stringResource(
            Res.string.media_cache_media_list_in_chat,
            roomName ?: "",
        )
        else -> stringResource(Res.string.media_cache_media_list)
    }

    val selectedBytes = state.mediaItems
        .filter { it.uri in state.selectedMediaUris }
        .fold(0uL) { total, item -> total + item.size }

    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = stringResource(Res.string.media_cache_delete_selected_title),
            message = stringResource(Res.string.media_cache_delete_selected_body),
            isDestructive = true,
            isLoading = state.isClearing,
            onConfirm = {
                showDeleteConfirm = false
                onClearSelected()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    Dialog(
        onDismissRequest = {
            if (!state.isClearing) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss, enabled = !state.isClearing) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(Res.string.cancel),
                                )
                            }
                        },
                    )
                },
                bottomBar = {
                    Surface(shadowElevation = 3.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = onSelectAll,
                                enabled = !state.mediaItemsLoading && state.mediaItems.isNotEmpty(),
                            ) {
                                Text(stringResource(Res.string.media_cache_select_all))
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { showDeleteConfirm = true },
                                enabled = state.selectedMediaUris.isNotEmpty() && !state.isClearing,
                            ) {
                                Text(
                                    stringResource(
                                        Res.string.media_cache_delete_selected,
                                        formatBytes(selectedBytes.toLong()),
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                when {
                    state.mediaItemsLoading -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularWavyProgressIndicator()
                    }

                    state.mediaItems.isEmpty() -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.media_cache_no_media),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        items(state.mediaItems, key = { it.uri }) { item ->
                            MediaItemRow(
                                item = item,
                                roomName = if (state.mediaItemsRoomId == null) {
                                    state.rooms.firstOrNull { it.id == item.roomId }?.name
                                } else {
                                    null
                                },
                                selected = item.uri in state.selectedMediaUris,
                                onToggle = { onToggle(item.uri) },
                            )
                            HorizontalDivider(Modifier.padding(start = Spacing.lg))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaItemRow(
    item: MediaItem,
    roomName: String?,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val kindLabel = mediaKindLabel(item.kind)
    val detail = buildString {
        append(kindLabel)
        roomName?.let { append(" · ").append(it) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.body.ifBlank { kindLabel },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = formatBytes(item.size.toLong()),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun mediaKindLabel(kind: String): String = when (kind) {
    "image" -> stringResource(Res.string.media_cache_images)
    "video" -> stringResource(Res.string.media_cache_videos)
    "audio" -> stringResource(Res.string.media_cache_audio)
    "file" -> stringResource(Res.string.files)
    else -> stringResource(Res.string.media_cache_other)
}

@Composable
private fun BreakdownCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun BreakdownRow(
    label: String,
    detail: String?,
    bytes: ULong,
    totalBytes: ULong,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val total = totalBytes.toFloat().takeIf { it > 0f }
    val clickModifier = onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            ShareBar(fraction = total?.let { bytes.toFloat() / it } ?: 0f)
        }
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = formatBytes(bytes.toLong()),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
        trailing?.let {
            Spacer(Modifier.width(Spacing.xs))
            it()
        }
    }
}

@Composable
private fun ShareBar(fraction: Float) {
    val safeFraction = fraction.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(safeFraction)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun <T> OptionRow(
    label: String,
    value: T,
    options: List<Pair<String, T>>,
    fallback: String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Box {
                TextButton(onClick = { expanded = true }, enabled = enabled) {
                    Text(options.firstOrNull { it.second == value }?.first ?: fallback)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }

                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { (text, option) ->
                        DropdownMenuItem(
                            text = { Text(text) },
                            onClick = {
                                expanded = false
                                if (option != value) onSelect(option)
                            },
                        )
                    }
                }
            }
        },
    )
}
