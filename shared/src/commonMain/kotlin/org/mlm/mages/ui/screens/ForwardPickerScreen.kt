package org.mlm.mages.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.components.snackbar.rememberErrorPoster
import org.mlm.mages.ui.components.snackbar.snackbarHost
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.*
import org.mlm.mages.ui.components.sheets.RoomSelectionList
import org.mlm.mages.ui.components.sheets.SelectedRoomsRow
import org.mlm.mages.ui.components.sheets.SelectableRoomItem

@Composable
fun ForwardPickerScreen(
    viewModel: ForwardPickerViewModel,
    onBack: () -> Unit,
    onForwardComplete: (BatchForwardSummary) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val snackbarManager: SnackbarManager = koinInject()
    val postError = rememberErrorPoster(snackbarManager)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ForwardPickerViewModel.Event.BatchForwardCompleted -> {
                    onForwardComplete(event.summary)
                }

                is ForwardPickerViewModel.Event.ShowError -> {
                    postError(event.message)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Forward")
                        Text(
                            text = "${state.eventCount} item${if (state.eventCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        enabled = !state.isSubmitting
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { snackbarManager.snackbarHost() },
        bottomBar = {
            ForwardActionBar(
                state = state,
                onSubmit = viewModel::submitForward
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.selectedRooms.isNotEmpty()) {
                SelectedRoomsRow(
                    rooms = state.selectedRooms,
                    onRemove = viewModel::toggleRoomSelection,
                    enabled = !state.isSubmitting
                )
            }

            RoomSelectionList(
                rooms = state.filteredRooms,
                selectedRoomIds = state.selectedRoomIds,
                searchQuery = state.searchQuery,
                onSearchChange = viewModel::setSearchQuery,
                onRoomToggle = viewModel::toggleRoomSelection,
                isLoading = state.isLoading,
                enabled = !state.isSubmitting,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ForwardActionBar(
    state: ForwardPickerUiState,
    onSubmit: () -> Unit
) {
    Surface(shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.progress?.let { progress ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Forwarding room ${progress.completedRooms + 1} of ${progress.totalRooms}",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    val totalWork = (progress.totalRooms * maxOf(progress.totalMessages, 1)).coerceAtLeast(1)
                    val currentWork =
                        (progress.completedRooms * maxOf(progress.totalMessages, 1) + progress.currentMessage)
                            .coerceAtMost(totalWork)

                    LinearWavyProgressIndicator(
                        progress = { currentWork.toFloat() / totalWork.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Button(
                onClick = onSubmit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isSubmitting) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Forwarding…")
                } else {
                    val label = when (state.selectedCount) {
                        0 -> "Select rooms"
                        1 -> "Forward to 1 room"
                        else -> "Forward to ${state.selectedCount} rooms"
                    }
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun SelectedRoomsRow(
    state: ForwardPickerUiState,
    onRemove: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.xs)
    ) {
        Text(
            text = "Selected rooms",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.selectedRooms, key = { it.roomId }) { room ->
                AssistChip(
                    onClick = { onRemove(room.roomId) },
                    label = {
                        Text(
                            text = room.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingIcon = {
                        Icon(Icons.Default.Close, contentDescription = "Remove")
                    }
                )
            }
        }
    }
}

@Composable
private fun RoomForwardItem(
    room: org.mlm.mages.ui.ForwardableRoom,
    isSelected: Boolean,
    status: RoomForwardStatus?,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }

    val supportingText = when {
        status?.stage == RoomForwardStage.Sending ->
            "Sending ${status.currentMessage}/${status.totalMessages}"

        status?.stage == RoomForwardStage.Success ->
            "Sent"

        status?.stage == RoomForwardStage.PartialSuccess ->
            "Sent ${status.successfulMessages}/${status.totalMessages}"

        status?.stage == RoomForwardStage.Failed ->
            status.errorMessage ?: "Failed"

        room.isDm ->
            "Direct message"

        else ->
            null
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick),
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = if (enabled) ({ onClick() }) else null
            )

            Avatar(
                name = room.name,
                avatarPath = room.avatarUrl,
                size = 48.dp
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = room.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                supportingText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (status?.stage) {
                            RoomForwardStage.Failed -> MaterialTheme.colorScheme.error
                            RoomForwardStage.Success -> MaterialTheme.colorScheme.primary
                            RoomForwardStage.PartialSuccess -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            when (status?.stage) {
                RoomForwardStage.Sending -> {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(22.dp),
                    )
                }

                RoomForwardStage.Success -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Sent",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                RoomForwardStage.PartialSuccess -> {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = "Partial success",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }

                RoomForwardStage.Failed -> {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Failed",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                else -> { }
            }
        }
    }
}
