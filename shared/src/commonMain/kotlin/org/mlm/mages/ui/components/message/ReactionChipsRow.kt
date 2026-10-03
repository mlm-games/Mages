package org.mlm.mages.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.mlm.mages.LocalMessageFontSize
import org.mlm.mages.matrix.ReactionSummary
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.util.secondaryClick

@Composable
fun ReactionChipsRow(
    chips: List<ReactionSummary>,
    modifier: Modifier = Modifier,
    maxVisible: Int? = null,
    avatarPathsByUserId: Map<String, String> = emptyMap(),
    imagePaths: Map<String, String> = emptyMap(),
    shortcodes: Map<String, String> = emptyMap(),
    showAvatars: Boolean = false,
    onClick: ((String) -> Unit)? = null,
    onLongClick: ((String) -> Unit)? = null,
) {
    if (chips.isEmpty()) return

    val visibleChips = maxVisible?.let { chips.take(it) } ?: chips
    val hiddenCount = (maxVisible?.let { chips.size - it } ?: 0).coerceAtLeast(0)

    FlowRow(
        modifier = modifier.padding(start = 2.dp),
        horizontalArrangement = Arrangement.spacedBy((-2).dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        visibleChips.forEach { chip ->
            ReactionChip(
                chip = chip,
                avatarPathsByUserId = avatarPathsByUserId,
                imagePaths = imagePaths,
                shortcodes = shortcodes,
                showAvatars = showAvatars,
                onClick = onClick,
                onLongClick = onLongClick
            )
        }

        if (hiddenCount > 0) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainerLowest)
            ) {
                Text(
                    text = "+$hiddenCount",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * Renders a reaction key, which is either an emoji or, per MSC4027, an mxc URI
 * standing in for an image.
 */
@Composable
private fun ReactionKeyLabel(
    key: String,
    imagePath: String?,
    shortcode: String?
) {
    val fontSize = LocalMessageFontSize.current.sp
    val labelSize = with(LocalDensity.current) { fontSize.toDp() * LABEL_SIZE_FACTOR }
    val labelLineHeight = with(LocalDensity.current) { labelSize.toSp() }

    if (!key.startsWith("mxc://")) {
        Text(
            text = key,
            fontSize = fontSize,
            lineHeight = labelLineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = labelSize * MAX_LABEL_SPANS)
        )
        return
    }

    if (imagePath != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalPlatformContext.current)
                .data(imagePath)
                .crossfade(true)
                .build(),
            contentDescription = shortcode,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(labelSize)
        )
        return
    }

    Box(
        modifier = Modifier
            .size(labelSize)
            .background(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
            )
    )
}

/** Line box and image tile stay equal so emoji and image chips align. */
private const val LABEL_SIZE_FACTOR = 1.25f

/** Bounds a hand-written reaction key so it cannot stretch a chip across the bubble. */
private const val MAX_LABEL_SPANS = 6f

/** Matches the senders the backend packs into `ReactionSummary.userIds`. */
private const val MAX_CHIP_AVATARS = 3

@Composable
private fun ReactionChip(
    chip: ReactionSummary,
    avatarPathsByUserId: Map<String, String>,
    imagePaths: Map<String, String>,
    shortcodes: Map<String, String>,
    showAvatars: Boolean = true,
    onClick: ((String) -> Unit)?,
    onLongClick: ((String) -> Unit)?
) {
    val isSelected = chip.mine

    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    val outlineColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }

    val chipLongPress: () -> Unit = { onLongClick?.invoke(chip.key) }

    Surface(
        modifier = Modifier
            .combinedClickable(
                onClick = { onClick?.invoke(chip.key) },
                onLongClick = chipLongPress
            )
            .secondaryClick(chipLongPress),
        shape = RoundedCornerShape(percent = 50),
        color = backgroundColor,
        border = BorderStroke(1.dp, outlineColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ReactionKeyLabel(
                key = chip.key,
                imagePath = imagePaths[chip.key],
                shortcode = shortcodes[chip.key]
            )

            if (showAvatars && chip.userIds.isNotEmpty()) {
                val userIdsToShow = chip.userIds.take(MAX_CHIP_AVATARS)

                Row(
                    horizontalArrangement = Arrangement.spacedBy((-6).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    userIdsToShow.forEach { userId ->
                        Avatar(
                            name = userId,
                            avatarPath = avatarPathsByUserId[userId],
                            size = 20.dp
                        )
                    }
                }

                if (chip.count > userIdsToShow.size) {
                    Text(
                        text = "+${chip.count - userIdsToShow.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
            else {
                Text("${chip.count}",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 13.sp)
            }
        }
    }
}
