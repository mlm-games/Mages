package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.picker_no_reaction_images
import mages.shared.generated.resources.picker_search_images
import mages.shared.generated.resources.search_no_results_found
import mages.shared.generated.resources.sticker_pack_unencrypted_notice
import mages.shared.generated.resources.try_different_search
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.matrix.ImagePackImageEntry
import org.mlm.mages.matrix.ImagePackSummary
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.core.EmoteRef
import org.mlm.mages.ui.components.core.PackImageTile
import org.mlm.mages.ui.theme.Spacing

private val EMPTY_STATE_HEIGHT = 200.dp
private val GRID_MAX_HEIGHT = 400.dp

/**
 * Picks an image to react with, per MSC4027, where the reaction key is the mxc
 * URI. The shortcode is sent alongside so other clients have a textual name.
 *
 * Cells are keyed by pack and shortcode rather than media URI: two packs may
 * legitimately carry the same image, and lazy lists reject duplicate keys.
 */
@Composable
fun ReactionImagePickerSheet(
    packs: List<ImagePackSummary>,
    isEncryptedRoom: Boolean,
    resolvePreview: suspend (thumbnailMxcUri: String?, mxcUrl: String) -> String?,
    onImageSelected: (EmoteRef) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visiblePacks = remember(packs, query) { filterImagePacks(packs, query) }
    val hasAnyImage = remember(packs) { packs.any { it.images.isNotEmpty() } }
    val visibleImageCount = remember(visiblePacks) { visiblePacks.sumOf { it.images.size } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            if (hasAnyImage) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(Res.string.picker_search_images)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )
            }

            if (visibleImageCount == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(EMPTY_STATE_HEIGHT),
                    contentAlignment = Alignment.Center
                ) {
                    if (hasAnyImage) {
                        EmptyState(
                            icon = Icons.Default.Search,
                            title = stringResource(Res.string.search_no_results_found),
                            subtitle = stringResource(Res.string.try_different_search)
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Default.AddReaction,
                            title = stringResource(Res.string.picker_no_reaction_images)
                        )
                    }
                }
                return@Column
            }

            LazyVerticalGrid(
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

                visiblePacks.forEach { pack ->
                    packHeading(pack)
                    items(
                        items = pack.images,
                        key = { "${pack.packId}:${it.shortcode}" }
                    ) { image ->
                        ReactionImageCell(
                            image = image,
                            resolvePreview = resolvePreview,
                            onClick = {
                                onImageSelected(
                                    EmoteRef(
                                        mxcUri = image.mxcUrl,
                                        alt = image.body ?: image.shortcode,
                                        title = image.shortcode
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.packHeading(pack: ImagePackSummary) {
    item(key = "header:${pack.packId}", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            text = pack.displayName ?: pack.sourceRoom,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.sm)
        )
    }
}

private fun filterImagePacks(
    packs: List<ImagePackSummary>,
    query: String
): List<ImagePackSummary> {
    val normalized = query.trim().lowercase()
    if (normalized.isEmpty()) return packs
    return packs.mapNotNull { pack ->
        val images = pack.images.filter { image ->
            image.shortcode.lowercase().contains(normalized) ||
                image.body?.lowercase()?.contains(normalized) == true
        }
        if (images.isEmpty()) null else pack.copy(images = images)
    }
}

@Composable
private fun ReactionImageCell(
    image: ImagePackImageEntry,
    resolvePreview: suspend (String?, String) -> String?,
    onClick: () -> Unit
) {
    var previewPath by remember(image.mxcUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(image.mxcUrl) {
        previewPath = resolvePreview(image.thumbnailMxcUri, image.mxcUrl)
    }

    PackImageTile(
        path = previewPath,
        contentDescription = image.body ?: image.shortcode,
        modifier = Modifier.size(64.dp),
        onClick = onClick,
        mime = image.mime,
    )
}
