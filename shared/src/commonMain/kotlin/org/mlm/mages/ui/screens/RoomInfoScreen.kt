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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.RoomDirectoryVisibility
import org.mlm.mages.matrix.RoomHistoryVisibility
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.RoomPowerLevelChanges
import org.mlm.mages.matrix.RoomPowerLevels
import org.mlm.mages.matrix.RoomProfile
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.ui.components.dialogs.ConfirmationDialog
import org.mlm.mages.ui.components.sheets.GranularPermissionsSheet
import org.mlm.mages.ui.components.sheets.PowerLevelsSheet
import org.mlm.mages.ui.components.sheets.ReportContentDialog
import org.mlm.mages.ui.components.sheets.RoomAliasesSheet
import org.mlm.mages.ui.components.settings.*
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.Spacing
import org.koin.compose.koinInject
import org.mlm.mages.ui.components.snackbar.SnackbarManager
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
    val snackbarManager: SnackbarManager = koinInject()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                RoomInfoViewModel.Event.LeaveSuccess -> onLeaveSuccess()
                is RoomInfoViewModel.Event.OpenRoom -> { /* handled in App.kt */ }
                is RoomInfoViewModel.Event.ShowError -> snackbarManager.showError(event.message)
                is RoomInfoViewModel.Event.ShowSuccess -> snackbarManager.show(event.message)
            }
        }
    }

    RoomInfoScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onNameChange = viewModel::updateName,
        onTopicChange = viewModel::updateTopic,
        onSaveName = viewModel::saveName,
        onSaveTopic = viewModel::saveTopic,
        onToggleFavourite = viewModel::toggleFavourite,
        onToggleLowPriority = viewModel::toggleLowPriority,
        onLeave = viewModel::leave,
        onSetVisibility = viewModel::setDirectoryVisibility,
        onEnableEncryption = viewModel::enableEncryption,
        onSetJoinRule = viewModel::setJoinRule,
        onSetHistoryVisibility = viewModel::setHistoryVisibility,
        onUpdateAliases = viewModel::updateCanonicalAlias,
        onUpdatePowerLevel = viewModel::updatePowerLevel,
        onApplyPowerLevelChanges = viewModel::applyPowerLevelChanges,
        onReportRoom = viewModel::reportRoom,
        onOpenRoom = viewModel::openRoom,
        onOpenMediaGallery = onOpenMediaGallery
    )
}

@Composable
fun RoomInfoScreen(
    state: RoomInfoUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onNameChange: (String) -> Unit,
    onTopicChange: (String) -> Unit,
    onSaveName: () -> Unit,
    onSaveTopic: () -> Unit,
    onToggleFavourite: () -> Unit,
    onToggleLowPriority: () -> Unit,
    onLeave: () -> Unit,
    onSetVisibility: (RoomDirectoryVisibility) -> Unit,
    onEnableEncryption: () -> Unit,
    onSetJoinRule: (RoomJoinRule) -> Unit,
    onSetHistoryVisibility: (RoomHistoryVisibility) -> Unit,
    onUpdateAliases: (String?, List<String>) -> Unit,
    onUpdatePowerLevel: (String, Long) -> Unit,
    onApplyPowerLevelChanges: (RoomPowerLevelChanges) -> Unit,
    onReportRoom: (String?) -> Unit,
    onOpenRoom: (String) -> Unit,
    onOpenMediaGallery: () -> Unit
) {
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showAliasesSheet by remember { mutableStateOf(false) }
    var showPowerLevelsSheet by remember { mutableStateOf(false) }
    var showGranularPermissionsSheet by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    val snackbarManager: SnackbarManager = koinInject()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(state.error) { state.error?.let { snackbarManager.showError(it) } }

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
        }
    ) { padding ->
        if (state.isLoading && state.profile == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    ProfileHeader(state)
                }

                state.successor?.let { successor ->
                    item {
                        UpgradeBanner(
                            title = "This room has been upgraded",
                            reason = successor.reason,
                            buttonText = "Go to new room",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            onAction = { onOpenRoom(successor.roomId) }
                        )
                    }
                }
                state.predecessor?.let { predecessor ->
                    item {
                        UpgradeBanner(
                            title = "Upgraded from another room",
                            buttonText = "Open previous room",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            onAction = { onOpenRoom(predecessor.roomId) }
                        )
                    }
                }

                item {
                    QuickActions(
                        isFavourite = state.isFavourite,
                        isLowPriority = state.isLowPriority,
                        isSaving = state.isSaving,
                        onToggleFavourite = onToggleFavourite,
                        onToggleLowPriority = onToggleLowPriority
                    )
                }

                if (state.canEditName || state.canEditTopic) {
                    item {
                        SettingsGroup {
                            if (state.canEditName) {
                                EditableSettingField(
                                    label = "Room name",
                                    value = state.editedName,
                                    onValueChange = onNameChange,
                                    onSave = onSaveName,
                                    enabled = true,
                                    isSaving = state.isSaving
                                )
                                if (state.canEditTopic) {
                                    HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                }
                            }
                            if (state.canEditTopic) {
                                EditableSettingField(
                                    label = "Topic",
                                    value = state.editedTopic,
                                    onValueChange = onTopicChange,
                                    onSave = onSaveTopic,
                                    enabled = true,
                                    isSaving = state.isSaving,
                                    singleLine = false
                                )
                            }
                        }
                    }
                }

                item {
                    SettingsGroup {
                        SettingsNavRow(
                            icon = Icons.Default.People,
                            title = "Members",
                            subtitle = "${state.members.size} members",
                            onClick = { showPowerLevelsSheet = true }
                        )
                        HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                        SettingsNavRow(
                            icon = Icons.Default.PhotoLibrary,
                            title = "Media & Files",
                            onClick = onOpenMediaGallery
                        )
                    }
                }

                val showSecuritySection = state.profile?.let { it.isEncrypted || state.canManageSettings } == true
                if (showSecuritySection) {
                    item {
                        SettingsGroupHeader("Security & Access")
                        SettingsGroup {
                            state.profile?.let { profile ->
                                if (profile.isEncrypted) {
                                    SettingsInfoRow(
                                        icon = Icons.Default.Lock,
                                        title = "Encryption",
                                        value = "Enabled"
                                    )
                                    if (state.canManageSettings) {
                                        HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                    }
                                } else if (state.canManageSettings) {
                                    SettingsActionRow(
                                        icon = Icons.Default.LockOpen,
                                        title = "Encryption",
                                        subtitle = "Not enabled",
                                        actionText = "Enable",
                                        enabled = !state.isAdminBusy,
                                        onClick = onEnableEncryption
                                    )
                                    HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                }

                                if (state.canManageSettings) {
                                    SettingsDropdownRow(
                                        icon = Icons.Default.MeetingRoom,
                                        label = "Who can join",
                                        currentValue = state.joinRule,
                                        displayName = { it.displayName },
                                        options = listOf(
                                            RoomJoinRule.Public to "Public — Anyone can join",
                                            RoomJoinRule.Invite to "Invite only",
                                            RoomJoinRule.Knock to "Knock — Request to join"
                                        ),
                                        enabled = !state.isAdminBusy,
                                        canChange = true,
                                        onSelect = onSetJoinRule
                                    )
                                    HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                    SettingsDropdownRow(
                                        icon = Icons.Default.History,
                                        label = "Message history",
                                        currentValue = state.historyVisibility,
                                        displayName = { it.displayName },
                                        options = listOf(
                                            RoomHistoryVisibility.WorldReadable to "Anyone",
                                            RoomHistoryVisibility.Shared to "All members",
                                            RoomHistoryVisibility.Joined to "Since joined",
                                            RoomHistoryVisibility.Invited to "Since invited"
                                        ),
                                        enabled = !state.isAdminBusy,
                                        canChange = true,
                                        onSelect = onSetHistoryVisibility
                                    )
                                    HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                    SettingSwitchRow(
                                        icon = Icons.Default.Public,
                                        title = "Listed in room directory",
                                        checked = state.directoryVisibility == RoomDirectoryVisibility.Public,
                                        enabled = !state.isAdminBusy,
                                        onCheckedChange = { checked ->
                                            onSetVisibility(if (checked) RoomDirectoryVisibility.Public else RoomDirectoryVisibility.Private)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.canManageSettings) {
                    item {
                        SettingsGroupHeader("Permissions")
                        SettingsGroup {
                            SettingsInfoRow(
                                icon = Icons.Default.Badge,
                                title = "Your role",
                                value = getRoleName(state.myPowerLevel)
                            )
                            HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                            SettingsNavRow(
                                icon = Icons.Default.AdminPanelSettings,
                                title = "Room permissions",
                                subtitle = "Configure what each role can do",
                                onClick = { showGranularPermissionsSheet = true }
                            )
                            HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                            SettingsNavRow(
                                icon = Icons.Default.Edit,
                                title = "Room addresses",
                                subtitle = state.profile?.canonicalAlias ?: "No primary address",
                                onClick = { showAliasesSheet = true }
                            )
                        }
                    }
                }

                item {
                    SettingsGroupHeader("Advanced")
                    SettingsGroup {
                        SettingsNavRow(
                            icon = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            title = if (showAdvanced) "Hide advanced" else "Show advanced",
                            onClick = { showAdvanced = !showAdvanced }
                        )
                        if (showAdvanced) {
                            HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                            SettingsInfoRow(
                                icon = Icons.Default.Info,
                                title = "Room version",
                                value = state.profile?.roomVersion ?: "Unknown"
                            )
                            state.profile?.roomId?.let { roomId ->
                                HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                SettingsCopyRow(
                                    icon = Icons.Default.Tag,
                                    title = "Room ID",
                                    value = roomId,
                                    onCopy = { clipboard.setText(AnnotatedString(roomId)) }
                                )
                            }
                            state.profile?.canonicalAlias?.let { alias ->
                                HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                SettingsCopyRow(
                                    icon = Icons.Default.AlternateEmail,
                                    title = "Primary address",
                                    value = alias,
                                    onCopy = { clipboard.setText(AnnotatedString(alias)) }
                                )
                            }
                            val altCount = state.profile?.altAliases?.size ?: 0
                            if (altCount > 0) {
                                HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                                SettingsInfoRow(
                                    icon = Icons.Default.Link,
                                    title = "Alternative addresses",
                                    value = "$altCount"
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(Spacing.lg))
                    SettingsGroup {
                        SettingsDangerRow(
                            icon = Icons.Default.Report,
                            title = "Report this room",
                            onClick = { showReportDialog = true }
                        )
                        HorizontalDivider(Modifier.padding(horizontal = Spacing.md))
                        SettingsDangerRow(
                            icon = Icons.AutoMirrored.Filled.ExitToApp,
                            title = if (state.profile?.isDm == true) "End conversation" else "Leave room",
                            onClick = { showLeaveDialog = true }
                        )
                    }
                    Spacer(Modifier.height(Spacing.lg))
                }
            }
        }

        if (showLeaveDialog) {
            ConfirmationDialog(
                title = "Leave room?",
                message = "You will no longer receive messages from this room. You can rejoin if invited again.",
                confirmText = "Leave",
                icon = Icons.Default.Warning,
                isDestructive = true,
                onConfirm = { showLeaveDialog = false; onLeave() },
                onDismiss = { showLeaveDialog = false }
            )
        }
        if (showAliasesSheet) {
            RoomAliasesSheet(
                canonicalAlias = state.profile?.canonicalAlias,
                altAliases = state.profile?.altAliases ?: emptyList(),
                onUpdate = { canonical, alts -> onUpdateAliases(canonical, alts); showAliasesSheet = false },
                onDismiss = { showAliasesSheet = false }
            )
        }
        if (showPowerLevelsSheet) {
            PowerLevelsSheet(
                members = state.members,
                powerLevels = state.powerLevels,
                myPowerLevel = state.myPowerLevel,
                onUpdatePowerLevel = onUpdatePowerLevel,
                onDismiss = { showPowerLevelsSheet = false }
            )
        }
        if (showGranularPermissionsSheet) {
            GranularPermissionsSheet(
                powerLevels = state.powerLevels,
                myPowerLevel = state.myPowerLevel,
                onUpdatePowerLevels = onApplyPowerLevelChanges,
                onDismiss = { showGranularPermissionsSheet = false }
            )
        }
        if (showReportDialog) {
            ReportContentDialog(
                onReport = { reason -> onReportRoom(reason); showReportDialog = false },
                onDismiss = { showReportDialog = false }
            )
        }
    }
}


@Composable
private fun ProfileHeader(state: RoomInfoUiState) {
    val profile = state.profile ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Avatar(
            name = profile.name,
            avatarPath = profile.avatarUrl,
            size = 80.dp,
            shape = MaterialTheme.shapes.large
        )
        Spacer(Modifier.height(Spacing.md))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (profile.isEncrypted) {
                Icon(Icons.Default.Lock, "Encrypted", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        profile.canonicalAlias?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (profile.topic?.isNotBlank() == true) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = profile.topic,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
private fun QuickActions(
    isFavourite: Boolean,
    isLowPriority: Boolean,
    isSaving: Boolean,
    onToggleFavourite: () -> Unit,
    onToggleLowPriority: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        FilterChip(
            selected = isFavourite,
            onClick = onToggleFavourite,
            label = { Text("Favourite") },
            leadingIcon = {
                Icon(
                    if (isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                    null, Modifier.size(18.dp)
                )
            },
            enabled = !isSaving
        )
        FilterChip(
            selected = isLowPriority,
            onClick = onToggleLowPriority,
            label = { Text("Low Priority") },
            leadingIcon = {
                Icon(
                    if (isLowPriority) Icons.Default.ArrowDownward else Icons.Default.Remove,
                    null, Modifier.size(18.dp)
                )
            },
            enabled = !isSaving
        )
    }
}

@Composable
private fun UpgradeBanner(
    title: String,
    reason: String? = null,
    buttonText: String,
    containerColor: androidx.compose.ui.graphics.Color,
    onAction: () -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs)
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            reason?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(buttonText) }
        }
    }
}

/** Groups settings rows inside a card with consistent padding. */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
    ) {
        Column(
            modifier = Modifier.padding(vertical = Spacing.xs),
            content = content
        )
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.lg, top = Spacing.md, bottom = Spacing.xs)
    )
}

/** A row that shows icon + title + subtitle and navigates on click. */
@Composable
private fun SettingsNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A row showing a label and a static value. */
@Composable
private fun SettingsInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(Spacing.md))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A row with a value that can be copied (tap anywhere to copy). */
@Composable
private fun SettingsCopyRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    onCopy: () -> Unit
) {
    Surface(
        onClick = onCopy,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.ContentCopy, "Copy", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** A row with icon + title + optional subtitle, with an action button on the right. */
@Composable
private fun SettingsActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    actionText: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(actionText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A destructive action row (red tinted). */
@Composable
private fun SettingsDangerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Spacing.md))
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.error)
        }
    }
}

/** Dropdown setting row with icon, matching the grouped list style. */
@Composable
private fun <T> SettingsDropdownRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    currentValue: T?,
    displayName: (T) -> String,
    options: List<Pair<T, String>>,
    enabled: Boolean,
    canChange: Boolean,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (canChange) expanded = it }
    ) {
        Surface(
            onClick = { if (canChange && enabled) expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        currentValue?.let(displayName) ?: "Unknown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (canChange) {
                    Icon(
                        Icons.Default.ArrowDropDown,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (canChange) {
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = { onSelect(value); expanded = false },
                        leadingIcon = if (currentValue == value) {
                            { Icon(Icons.Default.Check, null) }
                        } else null
                    )
                }
            }
        }
    }
}


private fun getRoleName(powerLevel: Long): String = when {
    powerLevel >= 100 -> "Admin"
    powerLevel >= 50 -> "Moderator"
    powerLevel > 0 -> "Custom ($powerLevel)"
    else -> "User"
}

private val RoomJoinRule.displayName: String
    get() = when (this) {
        RoomJoinRule.Public -> "Public"
        RoomJoinRule.Invite -> "Invite only"
        RoomJoinRule.Knock -> "Knock"
        RoomJoinRule.Restricted -> "Restricted"
        RoomJoinRule.KnockRestricted -> "Knock + Restricted"
    }

private val RoomHistoryVisibility.displayName: String
    get() = when (this) {
        RoomHistoryVisibility.WorldReadable -> "Visible to anyone"
        RoomHistoryVisibility.Shared -> "Visible to all members"
        RoomHistoryVisibility.Joined -> "Since joined"
        RoomHistoryVisibility.Invited -> "Since invited"
    }
