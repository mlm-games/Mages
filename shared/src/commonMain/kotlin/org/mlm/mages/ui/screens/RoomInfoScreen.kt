package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.RoomDirectoryVisibility
import org.mlm.mages.matrix.RoomProfile
import org.mlm.mages.ui.components.dialogs.ConfirmationDialog
import org.mlm.mages.ui.components.settings.*
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.RoomInfoUiState
import org.mlm.mages.ui.viewmodel.RoomInfoViewModel

@Composable
fun RoomInfoRoute(
    viewModel: RoomInfoViewModel,
    onBack: () -> Unit,
    onLeaveSuccess: () -> Unit,
    onOpenMediaGallery: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                RoomInfoViewModel.Event.LeaveSuccess -> onLeaveSuccess()
                is RoomInfoViewModel.Event.OpenRoom -> {
                    // handled in App.kt
                }
                is RoomInfoViewModel.Event.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is RoomInfoViewModel.Event.ShowSuccess -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    RoomInfoScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onNameChange = viewModel::updateName,
        onTopicChange = viewModel::updateTopic,
        onSaveName = viewModel::saveName,
        onSaveTopic = viewModel::saveTopic,
        onToggleFavourite = viewModel::toggleFavourite,
        onToggleLowPriority = viewModel::toggleLowPriority,
        onLeave = viewModel::leave,
        onLeaveSuccess = onLeaveSuccess,
        onSetVisibility = viewModel::setDirectoryVisibility,
        onEnableEncryption = viewModel::enableEncryption,
        onOpenRoom = { roomId -> viewModel.openRoom(roomId) },
        onOpenMediaGallery = onOpenMediaGallery
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomInfoScreen(
    state: RoomInfoUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onNameChange: (String) -> Unit,
    onTopicChange: (String) -> Unit,
    onSaveName: () -> Unit,
    onSaveTopic: () -> Unit,
    onToggleFavourite: () -> Unit,
    onToggleLowPriority: () -> Unit,
    onLeave: () -> Unit,
    onLeaveSuccess: () -> Unit,
    onSetVisibility: (RoomDirectoryVisibility) -> Unit,
    onEnableEncryption: () -> Unit,
    onOpenRoom: (String) -> Unit,
    onOpenMediaGallery: () -> Unit
) {
    var showLeaveDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Room Info") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (state.isLoading && state.profile == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Room header
                item {
                    RoomHeader(state, onOpenRoom)
                }

                // Priority section
                item {
                    SettingsSection(title = "Room Priority") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = state.isFavourite,
                                onClick = onToggleFavourite,
                                label = { Text("Favourite") },
                                leadingIcon = {
                                    Icon(
                                        if (state.isFavourite) Icons.Default.Star
                                        else Icons.Default.StarBorder,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                enabled = !state.isSaving,
                                modifier = Modifier.weight(1f)
                            )

                            FilterChip(
                                selected = state.isLowPriority,
                                onClick = onToggleLowPriority,
                                label = { Text("Low Priority") },
                                leadingIcon = {
                                    Icon(
                                        if (state.isLowPriority) Icons.Default.ArrowDownward
                                        else Icons.Default.Remove,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                enabled = !state.isSaving,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Edit name
                item {
                    SettingsSection(title = "Room Details") {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            EditableSettingField(
                                label = "Room name",
                                value = state.editedName,
                                onValueChange = onNameChange,
                                onSave = onSaveName,
                                enabled = state.canEditName,
                                isSaving = state.isSaving,
                                helperText = if (!state.canEditName) "You don't have permission to change the room name" else null
                            )

                            HorizontalDivider()

                            EditableSettingField(
                                label = "Topic",
                                value = state.editedTopic,
                                onValueChange = onTopicChange,
                                onSave = onSaveTopic,
                                enabled = state.canEditTopic,
                                isSaving = state.isSaving,
                                singleLine = false,
                                helperText = if (!state.canEditTopic) "You don't have permission to change the topic" else null
                            )
                        }
                    }
                }

                // Misc section
                item {
                    state.profile?.let { profile ->
                        MiscSection(
                            profile = profile,
                            visibility = state.directoryVisibility,
                            isAdminBusy = state.isAdminBusy,
                            canManageSettings = state.canManageSettings,
                            onSetVisibility = onSetVisibility,
                            onEnableEncryption = onEnableEncryption
                        )
                    }
                }

                // Media & Files
                item {
                    ElevatedCard(
                        onClick = onOpenMediaGallery,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoLibrary,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Media & Files",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Leave room button
                item {
                    Button(
                        onClick = { showLeaveDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.profile?.isDm == true) "End conversation"
                            else "Leave room"
                        )
                    }
                }
            }
        }

        // Leave confirmation dialog
        if (showLeaveDialog) {
            ConfirmationDialog(
                title = "Leave room?",
                message = "You will no longer receive messages from this room. You can rejoin if invited again.",
                confirmText = "Leave",
                icon = Icons.Default.Warning,
                isDestructive = true,
                onConfirm = {
                    showLeaveDialog = false
                    onLeave()
                },
                onDismiss = { showLeaveDialog = false }
            )
        }

        // Error handling
        LaunchedEffect(state.error) {
            state.error?.let {
                snackbarHostState.showSnackbar(it)
            }
        }
    }
}

@Composable
private fun RoomHeader(
    state: RoomInfoUiState,
    onOpenRoom: (String) -> Unit
) {
    val profile = state.profile ?: return

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (profile.isDm) Icons.Default.Person
                        else Icons.Default.People,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (profile.isEncrypted) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Encrypted",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (state.isFavourite) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Favourite",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Text(
                    profile.roomId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        if (state.successor != null) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "This room has been upgraded",
                        style = MaterialTheme.typography.titleSmall
                    )
                    state.successor.reason?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { onOpenRoom(state.successor.roomId) }) {
                        Text("Go to new room")
                    }
                }
            }
            Spacer(Modifier.height(Spacing.md))
        }

        if (state.predecessor != null) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Upgraded from another room",
                        style = MaterialTheme.typography.titleSmall
                    )
                    TextButton(onClick = { onOpenRoom(state.predecessor.roomId) }) {
                        Text("Open previous room")
                    }
                }
            }
            Spacer(Modifier.height(Spacing.md))
        }
    }
}

@Composable
private fun MiscSection(
    profile: RoomProfile,
    visibility: RoomDirectoryVisibility?,
    isAdminBusy: Boolean,
    canManageSettings: Boolean,
    onSetVisibility: (RoomDirectoryVisibility) -> Unit,
    onEnableEncryption: () -> Unit,
) {
    SettingsSection(title = "Misc") {
        val isPublic = visibility == RoomDirectoryVisibility.Public

        SettingSwitchRow(
            icon = Icons.Default.Public,
            title = "Listed in room directory",
            subtitle = if (!canManageSettings) "You don't have permission to change this" else null,
            checked = isPublic,
            enabled = canManageSettings && !isAdminBusy,
            onCheckedChange = { checked ->
                onSetVisibility(
                    if (checked) RoomDirectoryVisibility.Public
                    else RoomDirectoryVisibility.Private
                )
            }
        )

        if (!profile.isEncrypted) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Box(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = onEnableEncryption,
                    enabled = canManageSettings && !isAdminBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isAdminBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Lock, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Enable encryption")
                    }
                }
            }
            if (!canManageSettings) {
                Text(
                    text = "You don't have the permission to enable encryption",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}
