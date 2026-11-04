package org.mlm.mages.ui.components.sheets

import org.jetbrains.compose.resources.painterResource
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
import org.mlm.mages.AttachmentKind
import org.mlm.mages.MessageEvent
import org.mlm.mages.ui.ForwardableRoom
import org.mlm.mages.ui.displayPreview
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

@Composable
fun RoomPickerSheet(
    event: MessageEvent,
    rooms: List<ForwardableRoom>,
    isLoading: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onRoomSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = Spacing.md)
        ) {
            // Header
            Text(
                stringResource(Res.string.forward_message),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = Spacing.sm)
            )

            // Preview
            ForwardPreview(event = event)

            Spacer(Modifier.height(Spacing.md))

            // Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(Res.string.search_rooms)) },
                leadingIcon = { Icon(painterResource(Res.drawable.ic_search), stringResource(Res.string.search)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            )

            Spacer(Modifier.height(Spacing.md))

            // Room list
            when {
                isLoading -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }

                rooms.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painterResource(Res.drawable.ic_search_off),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (searchQuery.isNotBlank()) stringResource(Res.string.no_rooms_match_your_search)
                                else stringResource(Res.string.no_rooms_available),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item {
                            Text(
                                stringResource(Res.string.recent),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = Spacing.sm)
                            )
                        }

                        items(rooms, key = { it.roomId }) { room ->
                            RoomPickerItem(room = room, onClick = { onRoomSelected(room.roomId) })
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun ForwardPreview(event: MessageEvent) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painterResource(Res.drawable.ic_forward_to_inbox),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    event.sender.substringAfter('@').substringBefore(':'),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    event.displayPreview(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                event.attachment?.let { att ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(
                            when (att.kind) {
                                AttachmentKind.Image -> painterResource(Res.drawable.ic_image)
                                AttachmentKind.Video -> painterResource(Res.drawable.ic_videocam)
                                AttachmentKind.Audio -> painterResource(Res.drawable.ic_audio_file)
                                AttachmentKind.File -> painterResource(Res.drawable.ic_attach_file)
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            att.kind.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomPickerItem(room: ForwardableRoom, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoomAvatar(room = room, size = 48.dp)

            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    room.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (room.isDm) stringResource(Res.string.direct_message) else stringResource(Res.string.room),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(painterResource(Res.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
