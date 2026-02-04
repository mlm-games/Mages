package org.mlm.mages.ui.components.core

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.mages.MessageEvent
import org.mlm.mages.ui.theme.Spacing

/**
 * Currently displays the first pinned message with option to view all added later.
 */
@Composable
fun PinnedMessageBanner(
    pinnedEventIds: List<String>,
    events: List<MessageEvent>,
    onViewAll: () -> Unit,
    onDismiss: () -> Unit
) {
    // Find the first pinned event that we have in our events list
    val pinnedEvent = pinnedEventIds.firstNotNullOfOrNull { pinnedId ->
        events.find { it.eventId == pinnedId }
    }

    if (pinnedEvent == null) return

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(Modifier.width(Spacing.sm))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Pinned message",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = pinnedEvent.body.take(80),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

//                if (pinnedEventIds.size > 1) {
//                    TextButton(
//                        onClick = onViewAll,
//                        colors = ButtonDefaults.textButtonColors(
//                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
//                        )
//                    ) {
//                        Text("View all ${pinnedEventIds.size}")
//                        Icon(
//                            imageVector = Icons.Default.ExpandMore,
//                            contentDescription = null,
//                            modifier = Modifier.size(16.dp)
//                        )
//                    }
//                }
//
//                IconButton(
//                    onClick = onDismiss,
//                    modifier = Modifier.size(32.dp)
//                ) {
//                    Icon(
//                        imageVector = Icons.Default.Close,
//                        contentDescription = "Dismiss",
//                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
//                        modifier = Modifier.size(18.dp)
//                    )
//                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
        }
    }
}
