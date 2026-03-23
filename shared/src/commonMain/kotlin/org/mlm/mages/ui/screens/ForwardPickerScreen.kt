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
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::setSearchQuery,
                enabled = !state.isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                placeholder = { Text("Search rooms...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.setSearchQuery("") },
                            enabled = !state.isSubmitting
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )

            if (state.selectedRooms.isNotEmpty()) {
                SelectedRoomsRow(
                    state = state,
                    onRemove = viewModel::toggleRoomSelection
                )
            }

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }

                state.filteredRooms.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.SearchOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "No rooms found",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 0.dp,
                            top = Spacing.xs,
                            end = 0.dp,
                            bottom = 112.dp
                        )
                    ) {
                        items(
                            items = state.filteredRooms,
                            key = { it.roomId }
                        ) { room ->
                            RoomForwardItem(
                                room = room,
                                isSelected = room.roomId in state.selectedRoomIds,
                                status = state.roomStatuses[room.roomId],
                                enabled = !state.isSubmitting,
                                onClick = { viewModel.toggleRoomSelection(room.roomId) }
                            )
                        }
                    }
                }
            }
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
