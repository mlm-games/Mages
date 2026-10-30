package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.picker_no_sticker_packs
import mages.shared.generated.resources.sticker_pack_enable_globally
import mages.shared.generated.resources.sticker_pack_disable_globally
import mages.shared.generated.resources.sticker_pack_unencrypted_notice
import mages.shared.generated.resources.sticker_picker_discover
import mages.shared.generated.resources.sticker_picker_discover_back
import mages.shared.generated.resources.sticker_picker_discover_none
import mages.shared.generated.resources.sticker_picker_discover_refresh
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.matrix.ImagePackImageEntry
import org.mlm.mages.matrix.ImagePackSummary
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.core.PackImageTile
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing

private val EMPTY_STATE_HEIGHT = 200.dp
private val GRID_MAX_HEIGHT = 400.dp

/**
 * Picker for image packs (spec v1.19). Only packs that declare sticker usage, or
 * no usage at all, are shown; packs arrive in source-priority order.
 *
 * The grid is flat with full-span pack headers rather than a column of per-pack
 * rows, so only the images actually on screen are composed and therefore only
 * those are fetched.
 */
@Composable
fun StickerPickerSheet(
    packs: List<ImagePackSummary>,
    isLoading: Boolean,
    isEncryptedRoom: Boolean,
    packIdsBeingUpdated: Set<String>,
    discoveredPacks: List<ImagePackSummary>?,
    isDiscoveringPacks: Boolean,
    requestPreview: suspend (thumbnailMxcUri: String?, mxcUrl: String, mime: String?) -> String?,
    onSelect: (ImagePackImageEntry) -> Unit,
    onSetPackEnabled: (ImagePackSummary, Boolean) -> Unit,
    onDiscover: () -> Unit,
    onRefreshDiscovery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stickerPacks = remember(packs) { packs.filter { it.servesStickers() } }
    var showDiscovery by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showDiscovery) {
        if (showDiscovery) onDiscover()
    }

    val discoveryPacks = remember(discoveredPacks, packs) {
        val visible = packs.mapTo(mutableSetOf()) { it.packId }
        discoveredPacks.orEmpty().filter { it.servesStickers() && it.packId !in visible }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            discoveryRow(
                showDiscovery = showDiscovery,
                isDiscovering = isDiscoveringPacks,
                onToggle = { showDiscovery = !showDiscovery },
                onRefresh = onRefreshDiscovery,
            )

            when {
                showDiscovery && discoveredPacks == null -> loadingBox()

                showDiscovery && discoveryPacks.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(EMPTY_STATE_HEIGHT),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyState(
                        icon = Icons.Filled.TravelExplore,
                        title = stringResource(Res.string.sticker_picker_discover_none)
                    )
                }

                showDiscovery -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 64.dp),
                    modifier = Modifier.heightIn(max = GRID_MAX_HEIGHT),
                    contentPadding = PaddingValues(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        top = Spacing.sm,
                        bottom = Spacing.xl,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    packItems(
                        packs = discoveryPacks,
                        isEncryptedRoom = isEncryptedRoom,
                        showSourceRoom = true,
                        packIdsBeingUpdated = packIdsBeingUpdated,
                        requestPreview = requestPreview,
                        onSelect = onSelect,
                        onSetPackEnabled = onSetPackEnabled,
                    )
                }

                isLoading -> loadingBox()

                stickerPacks.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(EMPTY_STATE_HEIGHT),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyState(
                        icon = Icons.Filled.CollectionsBookmark,
                        title = stringResource(Res.string.picker_no_sticker_packs)
                    )
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 64.dp),
                    modifier = Modifier.heightIn(max = GRID_MAX_HEIGHT),
                    contentPadding = PaddingValues(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        top = Spacing.sm,
                        bottom = Spacing.xl,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    packItems(
                        packs = stickerPacks,
                        isEncryptedRoom = isEncryptedRoom,
                        showSourceRoom = false,
                        packIdsBeingUpdated = packIdsBeingUpdated,
                        requestPreview = requestPreview,
                        onSelect = onSelect,
                        onSetPackEnabled = onSetPackEnabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun loadingBox() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(EMPTY_STATE_HEIGHT),
        contentAlignment = Alignment.Center
    ) { CircularProgressIndicator() }
}

@Composable
private fun discoveryRow(
    showDiscovery: Boolean,
    isDiscovering: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onToggle) {
            Icon(
                imageVector = if (showDiscovery) {
                    Icons.AutoMirrored.Filled.ArrowBack
                } else {
                    Icons.Filled.TravelExplore
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = stringResource(
                    if (showDiscovery) {
                        Res.string.sticker_picker_discover_back
                    } else {
                        Res.string.sticker_picker_discover
                    }
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.weight(1f))
        if (showDiscovery) {
            IconButton(
                onClick = onRefresh,
                enabled = !isDiscovering,
                modifier = Modifier.size(Sizes.touchTarget)
            ) {
                if (isDiscovering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(Res.string.sticker_picker_discover_refresh),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private fun LazyGridScope.packItems(
    packs: List<ImagePackSummary>,
    isEncryptedRoom: Boolean,
    showSourceRoom: Boolean,
    packIdsBeingUpdated: Set<String>,
    requestPreview: suspend (String?, String, String?) -> String?,
    onSelect: (ImagePackImageEntry) -> Unit,
    onSetPackEnabled: (ImagePackSummary, Boolean) -> Unit,
) {
    if (isEncryptedRoom) {
        item(key = "notice", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = stringResource(Res.string.sticker_pack_unencrypted_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.sm)
            )
        }
    }

    packs.forEach { pack ->
        packHeader(
            pack = pack,
            isUpdating = pack.packId in packIdsBeingUpdated,
            showSourceRoom = showSourceRoom,
            onSetPackEnabled = onSetPackEnabled
        )
        items(
            items = pack.images,
            key = { "${pack.packId}:${it.shortcode}" }
        ) { image ->
            PackImageCell(
                image = image,
                requestPreview = requestPreview,
                onSelect = onSelect
            )
        }
    }
}

private fun LazyGridScope.packHeader(
    pack: ImagePackSummary,
    isUpdating: Boolean,
    showSourceRoom: Boolean = false,
    onSetPackEnabled: (ImagePackSummary, Boolean) -> Unit,
) {
    item(key = "header:${pack.packId}", span = { GridItemSpan(maxLineSpan) }) {
        Row(
            modifier = Modifier.padding(top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pack.displayName ?: pack.sourceRoomName ?: pack.sourceRoom,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (showSourceRoom && pack.displayName != null) {
                    Text(
                        text = pack.sourceRoomName ?: pack.sourceRoom,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            pack.attribution?.let { attribution ->
                Text(
                    text = attribution,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            IconButton(
                onClick = { onSetPackEnabled(pack, !pack.isGlobal) },
                enabled = !isUpdating,
                modifier = Modifier.size(Sizes.touchTarget)
            ) {
                if (isUpdating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (pack.isGlobal) Icons.Filled.Public else Icons.Outlined.Public,
                        contentDescription = stringResource(
                            if (pack.isGlobal) Res.string.sticker_pack_disable_globally
                            else Res.string.sticker_pack_enable_globally
                        ),
                        tint = if (pack.isGlobal) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PackImageCell(
    image: ImagePackImageEntry,
    requestPreview: suspend (String?, String, String?) -> String?,
    onSelect: (ImagePackImageEntry) -> Unit,
) {
    var previewPath by remember(image.mxcUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(image.mxcUrl) {
        previewPath = requestPreview(image.thumbnailMxcUri, image.mxcUrl, image.mime)
    }

    PackImageTile(
        path = previewPath,
        contentDescription = image.body ?: image.shortcode,
        modifier = Modifier.size(56.dp),
        onClick = { onSelect(image) },
        mime = image.mime,
    )
}
