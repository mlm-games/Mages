package org.mlm.mages.ui.components.sheets

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.mlm.mages.ui.PinnedMessageUi
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.message.ReactionChipsRow
import org.mlm.mages.ui.isForwardable
import org.mlm.mages.ui.previewText
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatPinnedTimestamp
import org.jetbrains.compose.resources.stringResource

@Composable
fun PinnedMessagesSheet(
    pinnedMessages: List<PinnedMessageUi>,
    canUnpin: Boolean,
    reactionImagePaths: Map<String, String> = emptyMap(),
    reactionShortcodes: Map<String, String> = emptyMap(),
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
                    painter = painterResource(Res.drawable.ic_push_pin_filled),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(Res.string.pinned_messages),
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
                        text = stringResource(Res.string.no_pinned_messages),
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
                                painter = painterResource(Res.drawable.ic_push_pin_filled),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )

                            Spacer(Modifier.width(Spacing.sm))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = pinned.senderLabel ?: stringResource(Res.string.pinned_message),
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
                                    text = pinned.previewText(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val event = pinned.event
                                when {
                                    event == null -> Text(
                                        text = stringResource(Res.string.tap_to_jump_to_the_message_in_the_timeline),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    event.reactions.isNotEmpty() -> ReactionChipsRow(
                                        chips = event.reactions,
                                        maxVisible = 6,
                                        imagePaths = reactionImagePaths,
                                        shortcodes = reactionShortcodes,
                                        onClick = { emoji -> onReact(pinned.eventId, emoji) }
                                    )
                                }
                            }

                            if (pinned.event?.isForwardable() != false) {
                                IconButton(onClick = { onForward(pinned.eventId) }) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_forward),
                                        contentDescription = stringResource(Res.string.forward)
                                    )
                                }
                            }

                            if (canUnpin) {
                                IconButton(onClick = { onUnpin(pinned.eventId) }) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_close),
                                        contentDescription = stringResource(Res.string.unpin)
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
