package org.mlm.mages.ui.components.sheets

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.mlm.mages.ui.ForwardableRoom
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.core.rememberResolvedAvatar
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** Server-side thumbnail size for the room avatars drawn in the pickers. */
private const val ROOM_AVATAR_PX = 64

@Composable
fun RoomSelectionList(
    rooms: List<ForwardableRoom>,
    selectedRoomIds: Set<String>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onRoomToggle: (String) -> Unit,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(Res.string.search_rooms)) },
            leadingIcon = { Icon(painterResource(Res.drawable.ic_search), stringResource(Res.string.search)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onSearchChange("") },
                        enabled = enabled
                    ) {
                        Icon(painterResource(Res.drawable.ic_close), stringResource(Res.string.clear))
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        )

        Spacer(Modifier.height(Spacing.md))

        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularWavyProgressIndicator()
                }
            }
            rooms.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painterResource(Res.drawable.ic_search_off),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (searchQuery.isNotBlank()) stringResource(Res.string.no_rooms_found)
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
                    items(rooms, key = { it.roomId }) { room ->
                        SelectableRoomItem(
                            room = room,
                            isSelected = room.roomId in selectedRoomIds,
                            enabled = enabled,
                            onClick = { onRoomToggle(room.roomId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SelectableRoomItem(
    room: ForwardableRoom,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick),
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = if (enabled) ({ onClick() }) else null
            )

            RoomAvatar(room = room, size = 40.dp)

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
        }
    }
}

@Composable
fun RoomAvatar(
    room: ForwardableRoom,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    Avatar(
        name = room.name,
        avatarPath = rememberResolvedAvatar(room.avatarUrl, px = ROOM_AVATAR_PX),
        size = size,
        modifier = modifier
    )
}

@Composable
fun SelectedRoomsRow(
    rooms: List<ForwardableRoom>,
    onRemove: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (rooms.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.selected_rooms),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(rooms, key = { it.roomId }) { room ->
                AssistChip(
                    onClick = { if (enabled) onRemove(room.roomId) },
                    label = {
                        Text(
                            text = room.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingIcon = {
                        Icon(painterResource(Res.drawable.ic_close), contentDescription = stringResource(Res.string.remove))
                    }
                )
            }
        }

        Spacer(Modifier.height(Spacing.xs))
    }
}