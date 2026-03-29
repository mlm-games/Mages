package org.mlm.mages.ui.components.core

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
) {
    val primary = pinnedMessages.firstOrNull() ?: return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onViewAll)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.PushPin,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )

        Spacer(Modifier.width(Spacing.sm))

        Column(modifier = Modifier.weight(1f)) {
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

        Text(
            text = if (pinnedMessages.size == 1) "View" else "${pinnedMessages.size} pinned",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
