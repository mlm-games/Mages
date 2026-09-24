package org.mlm.mages.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.mages.RoomSummary
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.SpaceChildInfo
import org.mlm.mages.matrix.SpaceInfo
import org.koin.compose.koinInject
import org.mlm.mages.ui.components.sheets.JoinRuleSpacePickerSheet
import org.mlm.mages.ui.components.sheets.MemberActionsSheet
import org.mlm.mages.ui.components.sheets.MemberListSheet
import org.mlm.mages.ui.components.sheets.PowerLevelsSheet
import org.mlm.mages.ui.ActionAvailabilityUi
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.snackbar.snackbarHost
import org.mlm.mages.ui.components.snackbar.rememberErrorPoster
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.SpaceSettingsViewModel

@Composable
fun SpaceSettingsScreen(
    viewModel: SpaceSettingsViewModel,
    onBack: () -> Unit,
    onLeaveSuccess: () -> Unit = onBack
) {
    val state by viewModel.state.collectAsState()
    val snackbarManager: SnackbarManager = koinInject()
    val postError = rememberErrorPoster(snackbarManager)

    LaunchedEffect(state.error) {
        state.error?.let {
            postError(it)
            viewModel.clearError()
        }
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Space Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.isLoading) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        },
        snackbarHost = { snackbarManager.snackbarHost() }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Loading indicator
            AnimatedVisibility(visible = state.isLoading || state.isSaving) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = Spacing.md)
            ) {
                // Space info header
                state.space?.let { space ->
                    item(key = "header") {
                        SpaceInfoHeader(space = space, avatarPath = state.spaceAvatarPath)
                    }
                }

                // Actions
                item(key = "actions_title") {
                    SectionTitle("Actions")
                }

                if (state.canEditDetails) {
                    item(key = "action_edit_details") {
                        ListItem(
                            headlineContent = { Text("Edit details") },
                            supportingContent = { Text("Name, topic, and address") },
                            leadingContent = {
                                Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable(enabled = !state.isSaving) {
                                viewModel.showEditDetailsDialog()
                            }
                        )
                    }
                }

                item(key = "action_people") {
                    ListItem(
                        headlineContent = { Text("People") },
                        supportingContent = { Text("${state.members.size} members") },
                        leadingContent = {
                            Icon(Icons.Default.Group, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.clickable { viewModel.showPeople() }
                    )
                }

                if (state.canManageSettings) {
                    item(key = "action_roles") {
                        ListItem(
                            headlineContent = { Text("Roles and permissions") },
                            supportingContent = { Text("Who can change what") },
                            leadingContent = {
                                Icon(Icons.Default.AdminPanelSettings, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable(enabled = !state.isSaving) {
                                viewModel.showRoles()
                            }
                        )
                    }
                }

                item(key = "action_new_room") {
                    ListItem(
                        headlineContent = { Text("New room in this space") },
                        supportingContent = { Text("Create a room inside this space") },
                        leadingContent = {
                            Icon(Icons.Default.AddComment, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.clickable(enabled = !state.isSaving) {
                            viewModel.showCreateRoom()
                        }
                    )
                }

                item(key = "action_add_room") {
                    ListItem(
                        headlineContent = { Text("Add rooms") },
                        supportingContent = { Text("Add existing rooms to this space") },
                        leadingContent = {
                            Icon(
                                Icons.Default.Add,
                                null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        modifier = Modifier.clickable(enabled = !state.isSaving) {
                            viewModel.showAddRoomDialog()
                        }
                    )
                }

                item(key = "action_invite") {
                    if (state.canInvite || !state.canManageSettings) {
                        ListItem(
                            headlineContent = { Text("Invite users") },
                            supportingContent = { Text("Invite users to this space") },
                            leadingContent = {
                                Icon(
                                    Icons.Default.PersonAdd,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            modifier = Modifier.clickable(enabled = !state.isSaving) {
                                viewModel.showInviteDialog()
                            }
                        )
                    }
                }

                item(key = "action_leave") {
                    ListItem(
                        headlineContent = {
                            Text("Leave space", color = MaterialTheme.colorScheme.error)
                        },
                        leadingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.ExitToApp,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        modifier = Modifier.clickable(enabled = !state.isSaving) {
                            viewModel.showLeaveWithChildren()
                        }
                    )
                }

                item(key = "divider") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))
                }

                if (state.canManageSettings) {
                    item(key = "security_title") {
                        SectionTitle("Who can join")
                    }
                    item(key = "security_join_rule") {
                        var expanded by remember { mutableStateOf(false) }
                        val rule = state.joinRule ?: RoomJoinRule.Invite
                        val allowCount = state.joinRuleAllowedSpaceIds.size
                        val subtitle = when (rule) {
                            RoomJoinRule.Public -> "Anyone can join"
                            RoomJoinRule.Invite -> "Invite only"
                            RoomJoinRule.Knock -> "Ask to join"
                            RoomJoinRule.Restricted -> "Space members can join"
                            RoomJoinRule.KnockRestricted -> "Ask to join with space members"
                        }
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                            modifier = Modifier.padding(horizontal = Spacing.lg)
                        ) {
                            OutlinedTextField(
                                value = if (allowCount > 0 &&
                                    (rule == RoomJoinRule.Restricted || rule == RoomJoinRule.KnockRestricted)
                                ) "$rule: $allowCount spaces allowed" else "$rule ($subtitle)",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Access") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                RoomJoinRule.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.displayNameForSpace()) },
                                        onClick = {
                                            expanded = false
                                            viewModel.requestJoinRule(option)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "divider2") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))
                }

                // Children
                item(key = "children_title") {
                    SectionTitle("Rooms in this space (${state.children.size})")
                }

                if (state.children.isEmpty() && !state.isLoading) {
                    item(key = "empty") {
                        Text(
                            "No rooms in this space yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.lg)
                        )
                    }
                }

                items(state.children, key = { it.roomId }) { child ->
                    val resolvedAvatar = state.avatarPathByRoomId[child.roomId] ?: child.avatarUrl
                    ChildRoomItem(
                        child = child.copy(avatarUrl = resolvedAvatar),
                        onRemove = { viewModel.removeChild(child.roomId) },
                        isRemoving = state.isSaving
                    )
                }
            }
        }
    }

    // Add room dialog
    if (state.showAddRoom) {
        AddRoomDialog(
            availableRooms = state.availableRooms,
            onAdd = { roomId, suggested -> viewModel.addChild(roomId, suggested) },
            onDismiss = viewModel::hideAddRoomDialog
        )
    }

    // Invite user dialog
    if (state.showInviteUser) {
        InviteUserToSpaceDialog(
            userId = state.inviteUserId,
            onUserIdChange = viewModel::setInviteUserId,
            onInvite = viewModel::inviteUser,
            onDismiss = viewModel::hideInviteDialog,
            isSaving = state.isSaving
        )
    }

    if (state.showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::hideLeaveConfirm,
            title = { Text("Leave space") },
            text = { Text("Are you sure you want to leave this space?") },
            confirmButton = {
                TextButton(
                    onClick = viewModel::leaveSpace,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::hideLeaveConfirm) { Text("Cancel") }
            }
        )
    }

    // Edit details dialog
    if (state.showEditDetails) {
        AlertDialog(
            onDismissRequest = viewModel::hideEditDetailsDialog,
            title = { Text("Edit details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = state.editName,
                        onValueChange = viewModel::setEditName,
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.editTopic,
                        onValueChange = viewModel::setEditTopic,
                        label = { Text("Topic") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.editAlias,
                        onValueChange = viewModel::setEditAlias,
                        label = { Text("Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::saveEditDetails, enabled = !state.isSaving) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::hideEditDetailsDialog) { Text("Cancel") }
            }
        )
    }

    // Create room in space dialog
    if (state.showCreateRoom) {
        AlertDialog(
            onDismissRequest = viewModel::hideCreateRoom,
            title = { Text("New room") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = state.newRoomName,
                        onValueChange = viewModel::setNewRoomName,
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.newRoomTopic,
                        onValueChange = viewModel::setNewRoomTopic,
                        label = { Text("Topic") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = state.newRoomIsPublic,
                            onCheckedChange = viewModel::setNewRoomIsPublic
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text("Make this room public")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::createRoomInSpace, enabled = !state.isSaving) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::hideCreateRoom) { Text("Cancel") }
            }
        )
    }

    // Leave space with children dialog
    if (state.showLeaveWithChildren) {
        AlertDialog(
            onDismissRequest = viewModel::hideLeaveWithChildren,
            title = { Text("Leave space") },
            text = {
                Column {
                    Text("Select any rooms to leave as well.")
                    Spacer(Modifier.height(Spacing.sm))
                    state.joinedChildren.forEach { child ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = child.roomId in state.selectedChildIds,
                                onCheckedChange = { viewModel.toggleChildSelection(child.roomId) }
                            )
                            Text(
                                child.name ?: child.roomId,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::leaveSpaceWithChildren,
                    enabled = !state.isSaving,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::hideLeaveWithChildren) { Text("Cancel") }
            }
        )
    }

    // People sheet
    if (state.showPeople) {
        MemberListSheet(
            members = state.members,
            isLoading = state.isLoading,
            myUserId = state.myUserId,
            onDismiss = viewModel::hidePeople,
            onMemberClick = { viewModel.selectMember(it) },
            onInvite = { viewModel.hidePeople(); viewModel.showInviteDialog() }
        )
    }

    // Per-member actions (kick / ban / unban / ignore) for a tapped member
    state.selectedMember?.let { member ->
        val moderation = if (state.canManageSettings) ActionAvailabilityUi.Enabled else ActionAvailabilityUi()
        MemberActionsSheet(
            member = member,
            onDismiss = viewModel::clearSelectedMember,
            dmAction = ActionAvailabilityUi(),
            kickAction = moderation,
            banAction = moderation,
            unbanAction = moderation,
            onStartDm = { viewModel.clearSelectedMember() },
            onKick = { reason -> viewModel.kickMember(member.userId, reason) },
            onBan = { reason -> viewModel.banMember(member.userId, reason) },
            onUnban = { reason -> viewModel.unbanMember(member.userId, reason) },
            onIgnore = { viewModel.ignoreMember(member.userId) },
            isBanned = member.membership == "ban"
        )
    }

    // Roles sheet
    if (state.showRoles) {
        PowerLevelsSheet(
            members = state.members,
            powerLevels = state.powerLevels,
            myPowerLevel = state.myPowerLevel,
            onUpdatePowerLevel = viewModel::updateMemberRole,
            onDismiss = viewModel::hideRoles
        )
    }

    // Join rule space picker
    state.pendingJoinRule?.let { pending ->
        if (state.showJoinRulePicker) {
            JoinRuleSpacePickerSheet(
                rule = pending,
                spaces = state.selectableSpaces,
                initiallyAllowedSpaceIds = state.joinRuleAllowedSpaceIds,
                onSave = { rule, ids -> viewModel.setJoinRule(rule, ids) },
                onDismiss = viewModel::hideJoinRuleSpacePicker
            )
        }
    }
}

@Composable
private fun SpaceInfoHeader(space: SpaceInfo, avatarPath: String?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(
                name = space.name,
                avatarPath = avatarPath,
                size = 48.dp
            )
            Spacer(Modifier.width(Spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    space.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${space.memberCount} members",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
    )
}

@Composable
private fun ChildRoomItem(
    child: SpaceChildInfo,
    onRemove: () -> Unit,
    isRemoving: Boolean
) {
    val displayName = child.name ?: child.alias ?: child.roomId
    ListItem(
        headlineContent = {
            Text(
                displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = child.topic?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = {
            Avatar(
                name = displayName,
                avatarPath = child.avatarUrl,
                size = 40.dp,
                shape = MaterialTheme.shapes.small
            )
        },
        trailingContent = {
            IconButton(onClick = onRemove, enabled = !isRemoving) {
                Icon(
                    Icons.Default.RemoveCircleOutline,
                    "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

private fun RoomJoinRule.displayNameForSpace(): String = when (this) {
    RoomJoinRule.Public -> "Public (anyone can join)"
    RoomJoinRule.Invite -> "Invite only"
    RoomJoinRule.Knock -> "Knock (ask to join)"
    RoomJoinRule.Restricted -> "Space members can join"
    RoomJoinRule.KnockRestricted -> "Ask to join with space members"
}

@Composable
private fun AddRoomDialog(
    availableRooms: List<RoomSummary>,
    onAdd: (roomId: String, suggested: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRoom by remember { mutableStateOf<RoomSummary?>(null) }
    var suggested by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add room to space") },
        text = {
            Column {
                if (availableRooms.isEmpty()) {
                    Text(
                        "All your rooms are already in this space",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        items(availableRooms, key = { it.id }) { room ->
                            ListItem(
                                headlineContent = { Text(room.name) },
                                supportingContent = {
                                    Text(
                                        room.id,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingContent = {
                                    RadioButton(
                                        selected = selectedRoom?.id == room.id,
                                        onClick = { selectedRoom = room }
                                    )
                                },
                                modifier = Modifier.clickable { selectedRoom = room }
                            )
                        }
                    }

                    Spacer(Modifier.height(Spacing.md))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = suggested,
                            onCheckedChange = { suggested = it }
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text("Mark as suggested")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedRoom?.let { onAdd(it.id, suggested) } },
                enabled = selectedRoom != null
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun InviteUserToSpaceDialog(
    userId: String,
    onUserIdChange: (String) -> Unit,
    onInvite: () -> Unit,
    onDismiss: () -> Unit,
    isSaving: Boolean
) {
    val isValid = userId.startsWith("@") && ":" in userId && userId.length > 3

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.PersonAdd, null) },
        title = { Text("Invite user to space") },
        text = {
            OutlinedTextField(
                value = userId,
                onValueChange = onUserIdChange,
                label = { Text("User ID") },
                placeholder = { Text("@user:server.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isSaving,
                isError = userId.isNotBlank() && !isValid
            )
        },
        confirmButton = {
            Button(
                onClick = onInvite,
                enabled = isValid && !isSaving
            ) {
                if (isSaving) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text("Invite")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
