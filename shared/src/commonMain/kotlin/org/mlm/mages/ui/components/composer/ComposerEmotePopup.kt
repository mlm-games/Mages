package org.mlm.mages.ui.components.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import mages.shared.generated.resources.*
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.emote_pack_unencrypted_notice
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.matrix.ImagePackSummary
import org.mlm.mages.ui.components.core.EmoteRef
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing

/**
 * One emote offered by the composer's `:` trigger.
 *
 * [packName] is only populated when the shortcode is ambiguous, which is the
 * disambiguation the spec asks for when several packs define the same name.
 */
data class EmoteSuggestion(
    val shortcode: String,
    val packName: String? = null,
    val ref: EmoteRef
) {
    /** Rewritten into the spec's `data-mx-emoticon` element when the message is sent. */
    val markdown: String
        get() = "![" + altForMarkdown() + "](" + ref.mxcUri + " \"" + shortcode + "\")"

    private fun altForMarkdown(): String {
        val raw = ref.label
        return raw.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("(", "\\(").replace(")", "\\)")
    }
}

/**
 * Emote-servicing images from [packs], ordered by shortcode. The pack name is
 * kept only where a shortcode is defined more than once, which is the
 * disambiguation the spec asks clients to provide.
 */
fun emoteSuggestionsFrom(packs: List<ImagePackSummary>): List<EmoteSuggestion> {
    val byShortcode = LinkedHashMap<String, MutableList<EmoteSuggestion>>()
    for (pack in packs) {
        if (!pack.servesEmoticons()) continue
        val packName = pack.displayName ?: pack.sourceRoom
        for (image in pack.images) {
            byShortcode.getOrPut(image.shortcode) { mutableListOf() } += EmoteSuggestion(
                shortcode = image.shortcode,
                packName = packName,
                ref = EmoteRef(
                    mxcUri = image.mxcUrl,
                    alt = image.body?.takeIf { it.isNotBlank() } ?: image.shortcode,
                    title = image.shortcode
                )
            )
        }
    }
    return byShortcode.keys.sorted().flatMap { key ->
        // The same image may sit in several packs; one row per shortcode and
        // media URI keeps the popup's item keys unique.
        val siblings = byShortcode.getValue(key).distinctBy { it.ref.mxcUri }
        if (siblings.size > 1) siblings else siblings.map { it.copy(packName = null) }
    }
}

@Composable
fun ComposerEmotePopup(
    suggestions: List<EmoteSuggestion>,
    resolvePreview: suspend (thumbnailMxcUri: String?, mxcUrl: String) -> String?,
    onEmoteSelected: (EmoteSuggestion) -> Unit,
    modifier: Modifier = Modifier,
    showUnencryptedNotice: Boolean = false,
) {
    if (suggestions.isEmpty()) return

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            if (showUnencryptedNotice) {
                Text(
                    text = stringResource(Res.string.emote_pack_unencrypted_notice),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        horizontal = Spacing.sm,
                        vertical = Spacing.xs
                    )
                )
            }

            LazyRow(
                modifier = Modifier.heightIn(max = 96.dp),
                contentPadding = PaddingValues(
                    horizontal = Spacing.sm,
                    vertical = Spacing.xs
                ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(
                    suggestions,
                    key = { "${it.shortcode}|${it.ref.mxcUri}|${it.packName.orEmpty()}" }
                ) { suggestion ->
                    EmoteSuggestionItem(
                        suggestion = suggestion,
                        resolvePreview = resolvePreview,
                        onClick = { onEmoteSelected(suggestion) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmoteSuggestionItem(
    suggestion: EmoteSuggestion,
    resolvePreview: suspend (String?, String) -> String?,
    onClick: () -> Unit
) {
    var previewPath by remember(suggestion.ref.mxcUri) { mutableStateOf<String?>(null) }

    LaunchedEffect(suggestion.ref.mxcUri) {
        previewPath = resolvePreview(null, suggestion.ref.mxcUri)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = 56.dp, max = 76.dp)
            .clickable { onClick() }
    ) {
        val path = previewPath
        if (path != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(path)
                    .crossfade(true)
                    .build(),
                contentDescription = suggestion.ref.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(Sizes.iconLarge)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(Sizes.iconLarge)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }

        Text(
            text = suggestion.packName ?: suggestion.shortcode,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
