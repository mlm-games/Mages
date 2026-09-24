package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.PinnedMessageUi
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.message.ReactionChipsRow
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatPinnedTimestamp

@Composable
fun PinnedMessagesSheet(
    pinnedMessages: List<PinnedMessageUi>,
    canUnpin: Boolean,
    onEventClick: (String) -> Unit,
    onUnpin: (String) -> Unit,
    onReact: (String, String) -> Unit,
    onForward: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xxl)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = "Pinned messages",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = pinnedMessages.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (pinnedMessages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No pinned messages",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(
                    items = pinnedMessages.asReversed(),
                    key = { it.eventId }
                ) { pinned ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEventClick(pinned.eventId) },
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = pinned.senderLabel ?: "Pinned message",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    pinned.timestampMs?.let { ts ->
                                        Spacer(Modifier.width(Spacing.sm))
                                        Text(
                                            text = formatPinnedTimestamp(ts),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    text = pinned.previewText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val event = pinned.event
                                when {
                                    event == null -> Text(
                                        text = "Tap to jump to the message in the timeline",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    event.reactions.isNotEmpty() -> ReactionChipsRow(
                                        chips = event.reactions,
                                        maxVisible = 6,
                                        onClick = { emoji -> onReact(pinned.eventId, emoji) }
                                    )
                                }
                            }

                            IconButton(onClick = { onForward(pinned.eventId) }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Forward,
                                    contentDescription = "Forward"
                                )
                            }

                            if (canUnpin) {
                                IconButton(onClick = { onUnpin(pinned.eventId) }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Unpin"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
