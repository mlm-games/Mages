package org.mlm.mages.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.SeenByEntry
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatSeenBy

@Composable
fun MessageStatusLine(
    text: String,
    isMine: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isMine)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 0.dp
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (isMine)
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun SeenByChip(
    entries: List<SeenByEntry>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    if (entries.isEmpty()) return

    val shown = entries.take(3)
    val overflow = (entries.size - shown.size).coerceAtLeast(0)

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
//        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarStack(
                entries = shown,
                size = 20.dp,
                overlap = 6.dp
            )

            Spacer(Modifier.width(8.dp))

            Text(
                text = formatSeenBy(entries.map { it.displayName ?: it.userId }),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (overflow > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun AvatarStack(
    entries: List<SeenByEntry>,
    size: Dp,
    overlap: Dp = 6.dp
) {
    if (entries.isEmpty()) return

    val overlapPx = with(LocalDensity.current) { overlap.toPx() }

    Layout(
        content = {
            entries.forEach { entry ->
                Avatar(
                    name = entry.displayName ?: entry.userId,
                    avatarPath = entry.avatarUrl,
                    size = size,
                    modifier = Modifier
                        .clip(CircleShape)
                        .semantics {
                            contentDescription = entry.displayName ?: entry.userId
                        }
                )
            }
        }
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }

        val totalWidth = if (placeables.isEmpty()) 0 else {
            placeables.first().width + (placeables.size - 1) * (placeables.first().width - overlapPx.toInt())
        }

        layout(totalWidth, placeables.maxOfOrNull { it.height } ?: 0) {
            var x = 0
            placeables.forEach { p ->
                p.placeRelative(x, 0)
                x += p.width - overlapPx.toInt()
            }
        }
    }
}
