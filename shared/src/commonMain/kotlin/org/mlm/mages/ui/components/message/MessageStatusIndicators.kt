package org.mlm.mages.ui.components.message

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.SeenByEntry
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatSeenBy

@Composable
fun MessageStatusLine(text: String, isMine: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isMine) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun SeenByChip(entries: List<SeenByEntry>) {
    if (entries.isEmpty()) return
    val shown = entries.take(3)
    val names = entries.mapNotNull { it.displayName ?: it.userId }
    val overflow = (entries.size - shown.size).coerceAtLeast(0)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AvatarStack(entries = shown, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatSeenBy(names),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (overflow > 0) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "+$overflow",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarStack(entries: List<SeenByEntry>, size: Dp) {
    if (entries.isEmpty()) return
    Layout(
        content = {
            entries.forEach { entry ->
                Avatar(
                    name = entry.displayName ?: entry.userId,
                    avatarPath = entry.avatarUrl,
                    size = size,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                )
            }
        }
    ) { measurables, constraints ->
        val overlap = (size * 0.35f).roundToPx()
        val placeables = measurables.map { it.measure(constraints) }
        val width = placeables.sumOf { it.width } - overlap * (placeables.size - 1)
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            var x = 0
            placeables.forEach { p ->
                p.placeRelative(x, 0)
                x += p.width - overlap
            }
        }
    }
}
