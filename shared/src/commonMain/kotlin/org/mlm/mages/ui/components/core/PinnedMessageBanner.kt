package org.mlm.mages.ui.components.core

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.PinnedMessageUi
import org.mlm.mages.ui.theme.Spacing


@Composable
fun PinnedMessageBanner(
    pinnedMessages: List<PinnedMessageUi>,
    onViewAll: () -> Unit,
    onEventClick: (String) -> Unit = {},
) {
    if (pinnedMessages.isEmpty()) return
    val total = pinnedMessages.size
    var index by remember { mutableIntStateOf(total - 1) }
    val active = index.coerceIn(0, total - 1)

    LaunchedEffect(active) {
        if (index != active) index = active
    }

    val primary = pinnedMessages[active]

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEventClick(primary.eventId) }
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PinIndicators(
            count = total,
            activeIndex = active,
            onClick = { index = it }
        )

        Spacer(Modifier.width(Spacing.sm))

        Icon(
            imageVector = Icons.Default.PushPin,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )

        Spacer(Modifier.width(Spacing.sm))

        Column(modifier = Modifier.weight(1f)) {
            if (total > 1) {
                Text(
                    text = "${active + 1}/$total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = primary.senderLabel ?: "Pinned message",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = primary.previewText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(Spacing.sm))

        TextButton(onClick = onViewAll) {
            Text(
                text = "View all",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun PinIndicators(
    count: Int,
    activeIndex: Int,
    onClick: (Int) -> Unit,
) {
    val visible = minOf(count, 3)
    val shown = if (activeIndex >= count - visible) visible else minOf(visible, activeIndex + 1)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (i in 0 until visible) {
            val isActive = i == activeIndex - (count - visible)
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(if (isActive) 18.dp else 8.dp)
                    .clickable { onClick(count - visible + i) }
                    .background(
                        if (isActive || i < shown) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        MaterialTheme.shapes.extraSmall
                    )
            )
        }
    }
}
