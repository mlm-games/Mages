package org.mlm.mages.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.mlm.mages.matrix.ImagePackImageEntry
import org.mlm.mages.ui.ImagePackEditorUiState
import org.mlm.mages.ui.PackEditorEntry
import org.mlm.mages.ui.PendingPackImage
import org.mlm.mages.ui.components.core.PackImageTile
import org.mlm.mages.ui.components.dialogs.ConfirmationDialog
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.ImagePackEditorViewModel
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.sticker_pack_add_images
import mages.shared.generated.resources.sticker_pack_discard_cancel
import mages.shared.generated.resources.sticker_pack_discard_confirm
import mages.shared.generated.resources.sticker_pack_discard_message
import mages.shared.generated.resources.sticker_pack_discard_title
import mages.shared.generated.resources.sticker_pack_display_name
import mages.shared.generated.resources.sticker_pack_enable_globally
import mages.shared.generated.resources.sticker_pack_editing_disabled
import mages.shared.generated.resources.sticker_pack_emoticon
import mages.shared.generated.resources.sticker_pack_new_pack
import mages.shared.generated.resources.sticker_pack_no_packs
import mages.shared.generated.resources.sticker_pack_remove
import mages.shared.generated.resources.sticker_pack_save
import mages.shared.generated.resources.sticker_pack_shortcode
import mages.shared.generated.resources.sticker_pack_sticker
import mages.shared.generated.resources.sticker_pack_unencrypted_notice
import mages.shared.generated.resources.sticker_pack_usage
import mages.shared.generated.resources.sticker_pack_usage_any
import org.jetbrains.compose.resources.stringResource

private val CELL_TILE = 88.dp
private val CELL_CAPTION = 80.dp
private val HEADER_FIELD = 56.dp

@Composable
fun ImagePackEditorRoute(
    viewModel: ImagePackEditorViewModel,
    onBack: () -> Unit,
    onPickImages: (onPicked: (List<Pair<String, String>>) -> Unit) -> Unit
) {
    val state by viewModel.state.collectAsState()

    // Which pack the next pick targets. Ephemeral UI state, so it stays here
    // rather than in the ViewModel.
    var pickTarget by remember { mutableStateOf<Int?>(null) }

    // Leaving with unsaved edits would silently drop them, so confirm first.
    var confirmDiscard by remember { mutableStateOf(false) }

    if (confirmDiscard) {
        ConfirmationDialog(
            title = stringResource(Res.string.sticker_pack_discard_title),
            message = stringResource(Res.string.sticker_pack_discard_message),
            confirmText = stringResource(Res.string.sticker_pack_discard_confirm),
            cancelText = stringResource(Res.string.sticker_pack_discard_cancel),
            isDestructive = true,
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
            onDismiss = { confirmDiscard = false }
        )
    }

    ImagePackEditorScreen(
        state = state,
        onDiscard = {
            if (state.hasUnsavedChanges) confirmDiscard = true else onBack()
        },
        requestPreview = viewModel::packImagePreview,
        onAddImages = { packIndex ->
            pickTarget = packIndex
            onPickImages { picked -> viewModel.addImages(pickTarget, picked) }
        },
        onRenamePack = viewModel::updatePackName,
        onSetUsage = viewModel::updatePackUsage,
        onSetPackGlobal = viewModel::setPackEnabledGlobally,
        onSetShortcode = viewModel::setPendingShortcode,
        onRemoveImage = viewModel::removeImage,
        onRemovePendingImage = viewModel::removePendingImage,
        onRemovePack = viewModel::removePack,
        onSave = viewModel::save
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePackEditorScreen(
    state: ImagePackEditorUiState,
    onDiscard: () -> Unit,
    requestPreview: suspend (thumbnailMxcUri: String?, mxcUrl: String) -> String?,
    onAddImages: (packIndex: Int) -> Unit,
    onRenamePack: (index: Int, name: String) -> Unit,
    onSetUsage: (index: Int, usage: List<String>) -> Unit,
    onSetPackGlobal: (index: Int, enabled: Boolean) -> Unit,
    onSetShortcode: (packIndex: Int, localId: String, shortcode: String) -> Unit,
    onRemoveImage: (packIndex: Int, shortcode: String) -> Unit,
    onRemovePendingImage: (packIndex: Int, localId: String) -> Unit,
    onRemovePack: (index: Int) -> Unit,
    onSave: () -> Unit
) {
    // Pack images are never encrypted, per the spec, so this caveat is worth
    // showing in every room rather than only where editing is blocked.
    val unencryptedNotice = stringResource(Res.string.sticker_pack_unencrypted_notice)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(Res.string.sticker_pack_new_pack),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDiscard) { Icon(Icons.Default.Close, null) }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
        ) {
            Text(
                unencryptedNotice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )

            if (!state.canEdit && !state.isLoading) {
                Text(
                    stringResource(Res.string.sticker_pack_editing_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )
            }

            if (state.isUploading) {
                LinearWavyProgressIndicator(
                    progress = { state.uploadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) { LoadingIndicator() }
            } else {
                PackEditorGrid(
                    modifier = Modifier.weight(1f),
                    state = state,
                    requestPreview = requestPreview,
                    onRenamePack = onRenamePack,
                    onSetUsage = onSetUsage,
                    onSetPackGlobal = onSetPackGlobal,
                    onSetShortcode = onSetShortcode,
                    onRemoveImage = onRemoveImage,
                    onRemovePendingImage = onRemovePendingImage,
                    onAddImages = onAddImages,
                    onRemovePack = onRemovePack
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                if (state.isUploading) {
                    CircularWavyProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Spacing.sm))
                }
                // With packs already present, target the first one; with none,
                // this is what starts a new pack.
                OutlinedButton(
                    onClick = {
                        val target = state.packs.indexOfFirst { !it.isNew }
                            .takeIf { it >= 0 } ?: state.packs.size
                        onAddImages(target)
                    },
                    enabled = state.canEdit && !state.isUploading
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(Res.string.sticker_pack_add_images))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onSave,
                    enabled = state.canEdit && !state.isUploading && state.hasUnsavedChanges
                ) {
                    Text(stringResource(Res.string.sticker_pack_save))
                }
            }
        }
    }
}

@Composable
private fun PackEditorGrid(
    modifier: Modifier = Modifier,
    state: ImagePackEditorUiState,
    requestPreview: suspend (thumbnailMxcUri: String?, mxcUrl: String) -> String?,
    onRenamePack: (index: Int, name: String) -> Unit,
    onSetUsage: (index: Int, usage: List<String>) -> Unit,
    onSetPackGlobal: (index: Int, enabled: Boolean) -> Unit,
    onSetShortcode: (packIndex: Int, localId: String, shortcode: String) -> Unit,
    onRemoveImage: (packIndex: Int, shortcode: String) -> Unit,
    onRemovePendingImage: (packIndex: Int, localId: String) -> Unit,
    onAddImages: (packIndex: Int) -> Unit,
    onRemovePack: (index: Int) -> Unit
) {
    val gridState = rememberLazyGridState()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = CELL_TILE),
        state = gridState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.lg
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        if (state.packs.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(Res.string.sticker_pack_no_packs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        state.packs.forEachIndexed { packIndex, pack ->
            packSection(
                packIndex = packIndex,
                pack = pack,
                isReadOnly = !state.canEdit,
                isSaving = pack.stateKey in state.stateKeysBeingSaved,
                requestPreview = requestPreview,
                onRename = { onRenamePack(packIndex, it) },
                onSetUsage = { onSetUsage(packIndex, it) },
                onSetPackGlobal = { onSetPackGlobal(packIndex, it) },
                onSetShortcode = { localId, shortcode ->
                    onSetShortcode(packIndex, localId, shortcode)
                },
                onRemoveImage = { onRemoveImage(packIndex, it) },
                onRemovePending = { onRemovePendingImage(packIndex, it) },
                onAddImages = { onAddImages(packIndex) },
                onRemovePack = { onRemovePack(packIndex) }
            )
        }
    }
}

/**
 * Renders one pack as a full-span header followed by its image grid. Headers
 * are grid items rather than nested grids so only on-screen cells compose.
 */
private fun LazyGridScope.packSection(
    packIndex: Int,
    pack: PackEditorEntry,
    isReadOnly: Boolean,
    isSaving: Boolean,
    requestPreview: suspend (String?, String) -> String?,
    onRename: (String) -> Unit,
    onSetUsage: (List<String>) -> Unit,
    onSetPackGlobal: (Boolean) -> Unit,
    onSetShortcode: (String, String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onRemovePending: (String) -> Unit,
    onAddImages: () -> Unit,
    onRemovePack: () -> Unit
) {
    item(key = "pack_header_$packIndex", span = { GridItemSpan(maxLineSpan) }) {
        PackHeader(
            pack = pack,
            isReadOnly = isReadOnly,
            isSaving = isSaving,
            onRename = onRename,
            onSetUsage = onSetUsage,
            onSetPackGlobal = onSetPackGlobal,
            onAddImages = onAddImages,
            onRemovePack = onRemovePack
        )
    }
    items(
        items = pack.images,
        key = { "img_${packIndex}_${it.shortcode}" }
    ) { image ->
        StoredPackImageCell(
            image = image,
            isReadOnly = isReadOnly,
            requestPreview = requestPreview,
            onRemove = { onRemoveImage(image.shortcode) }
        )
    }
    items(
        items = pack.pendingImages,
        key = { "pending_${packIndex}_${it.localId}" }
    ) { pending ->
        PendingImageCell(
            pending = pending,
            isReadOnly = isReadOnly,
            onSetShortcode = { onSetShortcode(pending.localId, it) },
            onRemove = { onRemovePending(pending.localId) }
        )
    }
}

@Composable
private fun PackHeader(
    pack: PackEditorEntry,
    isReadOnly: Boolean,
    isSaving: Boolean,
    onRename: (String) -> Unit,
    onSetUsage: (List<String>) -> Unit,
    onSetPackGlobal: (Boolean) -> Unit,
    onAddImages: () -> Unit,
    onRemovePack: () -> Unit
) {
    // A pack that has never been written has no state key to name in either
    // `m.room.image_pack` or `m.image_pack.rooms`, so it can be neither removed
    // nor subscribed until its first save. Adding is still allowed, since a
    // pack's first save is what creates the key.
    val isEditable = !isReadOnly && !isSaving && !pack.isNew

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md, bottom = Spacing.xs)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = pack.displayName,
                onValueChange = onRename,
                enabled = !isReadOnly && !isSaving,
                singleLine = true,
                label = { Text(stringResource(Res.string.sticker_pack_display_name)) },
                modifier = Modifier.weight(1f)
            )
            if (isSaving) {
                CircularWavyProgressIndicator(
                    modifier = Modifier
                        .padding(start = Spacing.sm)
                        .size(20.dp)
                )
            } else {
                IconButton(
                    onClick = onAddImages,
                    enabled = !isReadOnly && !isSaving,
                    modifier = Modifier.height(HEADER_FIELD)
                ) {
                    Icon(
                        Icons.Default.AddPhotoAlternate,
                        stringResource(Res.string.sticker_pack_add_images)
                    )
                }
                IconButton(
                    onClick = onRemovePack,
                    enabled = isEditable,
                    modifier = Modifier.height(HEADER_FIELD)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        stringResource(Res.string.sticker_pack_remove)
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.xs))

        // Label left, control right on every row, so the three controls share
        // one alignment axis instead of each picking its own.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.sticker_pack_usage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            UsageSelector(
                usage = pack.usage,
                enabled = !isReadOnly && !isSaving,
                onChange = onSetUsage
            )
        }

        Row(
            modifier = Modifier.padding(top = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(Res.string.sticker_pack_enable_globally),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Switch(
                checked = pack.isEnabledGlobally,
                onCheckedChange = onSetPackGlobal,
                enabled = isEditable
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageSelector(
    usage: List<String>,
    enabled: Boolean,
    onChange: (List<String>) -> Unit
) {
    // An empty usage list means both usages per the spec, so "Both" is a real
    // selection rather than the absence of one.
    val selected = when {
        usage.size == 1 && usage[0] == "emoticon" -> 1
        usage.size == 1 && usage[0] == "sticker" -> 2
        else -> 0
    }
    val labels = listOf(
        stringResource(Res.string.sticker_pack_usage_any),
        stringResource(Res.string.sticker_pack_emoticon),
        stringResource(Res.string.sticker_pack_sticker)
    )

    SingleChoiceSegmentedButtonRow {
        labels.forEachIndexed { index, label ->
            SegmentedButton(
                selected = selected == index,
                onClick = {
                    onChange(
                        when (index) {
                            1 -> listOf("emoticon")
                            2 -> listOf("sticker")
                            else -> emptyList()
                        }
                    )
                },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size)
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun StoredPackImageCell(
    image: ImagePackImageEntry,
    isReadOnly: Boolean,
    requestPreview: suspend (String?, String) -> String?,
    onRemove: () -> Unit
) {
    var previewPath by remember(image.mxcUrl) { mutableStateOf<String?>(null) }
    LaunchedEffect(image.mxcUrl) {
        previewPath = requestPreview(image.thumbnailMxcUri, image.mxcUrl)
    }

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CELL_TILE)
        ) {
            PackImageTile(
                path = previewPath,
                contentDescription = image.body ?: image.shortcode,
                modifier = Modifier.fillMaxSize()
            )
            if (!isReadOnly) {
                RemoveChip(
                    onClick = onRemove,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }
        // Reserves the same slot a pending cell spends on its shortcode field,
        // so a stored and a pending image in one row stay on a shared baseline.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CELL_CAPTION),
            contentAlignment = Alignment.CenterStart
        ) {
            // A stored shortcode is not editable here: changing it means
            // renaming the pack's entry, which is a save-time operation.
            Text(
                text = image.shortcode,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.xs)
            )
        }
    }
}

@Composable
private fun PendingImageCell(
    pending: PendingPackImage,
    isReadOnly: Boolean,
    onSetShortcode: (String) -> Unit,
    onRemove: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CELL_TILE)
        ) {
            PackImageTile(
                path = pending.previewPath ?: pending.path,
                contentDescription = pending.shortcode,
                modifier = Modifier.fillMaxSize()
            )
            if (!isReadOnly) {
                RemoveChip(
                    onClick = onRemove,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }
        // The error replaces the placeholder rather than occupying
        // `supportingText`, so revealing one does not resize the cell and
        // reflow every row below it.
        OutlinedTextField(
            value = pending.shortcode,
            onValueChange = onSetShortcode,
            enabled = !isReadOnly,
            singleLine = true,
            isError = pending.shortcodeError != null,
            textStyle = MaterialTheme.typography.labelMedium,
            placeholder = { Text(stringResource(Res.string.sticker_pack_shortcode)) },
            supportingText = {
                Text(
                    text = pending.shortcodeError?.let { stringResource(it) }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .height(CELL_CAPTION)
        )
    }
}

@Composable
private fun RemoveChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(Sizes.touchTarget)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Close,
                    stringResource(Res.string.sticker_pack_remove),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
