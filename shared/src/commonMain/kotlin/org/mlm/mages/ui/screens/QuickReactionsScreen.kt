package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.launch
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.back
import mages.shared.generated.resources.more_options
import mages.shared.generated.resources.move_down
import mages.shared.generated.resources.move_up
import mages.shared.generated.resources.no_quick_reactions
import mages.shared.generated.resources.quick_reactions
import mages.shared.generated.resources.quick_reactions_from_packs
import mages.shared.generated.resources.image
import mages.shared.generated.resources.remove
import mages.shared.generated.resources.reset
import mages.shared.generated.resources.setting_quick_reactions
import mages.shared.generated.resources.setting_quick_reactions_description
import mages.shared.generated.resources.sticker_pack_emoticon
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.mlm.mages.matrix.ImagePackSummary
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.encodeQuickReactions
import org.mlm.mages.settings.isImageReaction
import org.mlm.mages.settings.parseQuickReactions
import org.mlm.mages.ui.components.core.ReactionGlyph
import org.mlm.mages.ui.components.sheets.EmojiPickerSheet
import org.mlm.mages.ui.components.sheets.ReactionImagePickerSheet
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.QuickReactionsViewModel

@Composable
fun QuickReactionsRoute(onBack: () -> Unit) {
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val viewModel: QuickReactionsViewModel = koinViewModel()
    val settings by settingsRepository.flow.collectAsState(initial = AppSettings())
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    QuickReactionsScreen(
        reactions = parseQuickReactions(settings.quickReactions),
        packs = state.packs,
        packsLoading = state.isLoading,
        onChange = { reactions ->
            val encoded = encodeQuickReactions(reactions)
            scope.launch { settingsRepository.update { it.copy(quickReactions = encoded) } }
        },
        onReset = {
            scope.launch { settingsRepository.update { it.copy(quickReactions = "") } }
        },
        resolvePreview = viewModel::preview,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickReactionsScreen(
    reactions: List<String>,
    packs: List<ImagePackSummary>,
    packsLoading: Boolean,
    onChange: (List<String>) -> Unit,
    onReset: () -> Unit,
    resolvePreview: suspend (String) -> String?,
    onBack: () -> Unit,
) {
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showPackPicker by remember { mutableStateOf(false) }

    val imageInfo = remember(packs) {
        packs
            .flatMap { pack ->
                val packName = pack.displayName ?: pack.sourceRoomName ?: pack.sourceRoom
                pack.images.map { it.mxcUrl to (it.shortcode to packName) }
            }
            .toMap()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(Res.string.setting_quick_reactions),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onReset) {
                        Text(stringResource(Res.string.reset))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.sm,
                bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                Text(
                    stringResource(Res.string.setting_quick_reactions_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.xs)
                )
            }

            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(
                            stringResource(Res.string.quick_reactions),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(Spacing.sm))
                        if (reactions.isEmpty()) {
                            Text(
                                stringResource(Res.string.no_quick_reactions),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                items(reactions, key = { it }) { key ->
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            ReactionGlyph(
                                                key = key,
                                                size = 26.dp,
                                                resolvePreview = resolvePreview,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilledTonalButton(
                        onClick = { showEmojiPicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AddReaction, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(stringResource(Res.string.sticker_pack_emoticon))
                    }
                    FilledTonalButton(
                        onClick = { showPackPicker = true },
                        enabled = !packsLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(stringResource(Res.string.quick_reactions_from_packs))
                    }
                }
            }

            itemsIndexed(reactions, key = { _, key -> key }) { index, key ->
                val info = imageInfo[key]
                ReactionRow(
                    key = key,
                    shortcode = info?.first,
                    packName = info?.second,
                    canMoveUp = index > 0,
                    canMoveDown = index < reactions.lastIndex,
                    onMoveUp = { onChange(reactions.moved(index, index - 1)) },
                    onMoveDown = { onChange(reactions.moved(index, index + 1)) },
                    onRemove = { onChange(reactions - key) },
                    resolvePreview = resolvePreview,
                )
            }
        }
    }

    if (showEmojiPicker) {
        EmojiPickerSheet(
            onDismiss = { showEmojiPicker = false },
            onEmojiSelected = { emoji ->
                showEmojiPicker = false
                if (emoji !in reactions) onChange(reactions + emoji)
            }
        )
    }

    if (showPackPicker) {
        ReactionImagePickerSheet(
            packs = packs,
            isEncryptedRoom = false,
            resolvePreview = { _, mxcUrl -> resolvePreview(mxcUrl) },
            onImageSelected = { ref ->
                showPackPicker = false
                if (ref.mxcUri !in reactions) onChange(reactions + ref.mxcUri)
            },
            onDismiss = { showPackPicker = false }
        )
    }
}

@Composable
private fun ReactionRow(
    key: String,
    shortcode: String?,
    packName: String?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    resolvePreview: suspend (String) -> String?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val isImage = isImageReaction(key)
    val imageTitle = shortcode?.let { ":$it:" } ?: stringResource(Res.string.image)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 60.dp)
                .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isImage) {
                ReactionGlyph(
                    key = key,
                    size = 32.dp,
                    contentDescription = imageTitle,
                    resolvePreview = resolvePreview,
                )
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = imageTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (packName != null) {
                        Text(
                            text = packName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Text(
                    text = key,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Spacing.xs),
                    maxLines = 1
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(Res.string.more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.move_up)) },
                        leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null) },
                        enabled = canMoveUp,
                        onClick = {
                            menuOpen = false
                            onMoveUp()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.move_down)) },
                        leadingIcon = { Icon(Icons.Default.ArrowDownward, contentDescription = null) },
                        enabled = canMoveDown,
                        onClick = {
                            menuOpen = false
                            onMoveDown()
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.remove)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        }
                    )
                }
            }
        }
    }
}

private fun List<String>.moved(from: Int, to: Int): List<String> =
    toMutableList().apply { add(to, removeAt(from)) }
