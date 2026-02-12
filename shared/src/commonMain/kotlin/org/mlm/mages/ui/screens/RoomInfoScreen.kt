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
                is RoomInfoViewModel.Event.OpenRoom -> {
                    // handled in App.kt
                }
                is RoomInfoViewModel.Event.ShowError -> {
                    snackbarManager.showError(event.message)
                }
                is RoomInfoViewModel.Event.ShowSuccess -> {
                    snackbarManager.show(event.message)
                }
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
        onLeaveSuccess = onLeaveSuccess,
        onSetVisibility = viewModel::setDirectoryVisibility,
        onEnableEncryption = viewModel::enableEncryption,
        onSetJoinRule = viewModel::setJoinRule,
        onSetHistoryVisibility = viewModel::setHistoryVisibility,
        onUpdateAliases = viewModel::updateCanonicalAlias,
        onUpdatePowerLevel = viewModel::updatePowerLevel,
        onApplyPowerLevelChanges = viewModel::applyPowerLevelChanges,
        onReportContent = viewModel::reportContent,
        onReportRoom = viewModel::reportRoom,
        onOpenRoom = { roomId -> viewModel.openRoom(roomId) },
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
    onLeaveSuccess: () -> Unit,
    onSetVisibility: (RoomDirectoryVisibility) -> Unit,
    onEnableEncryption: () -> Unit,
    onSetJoinRule: (RoomJoinRule) -> Unit,
    onSetHistoryVisibility: (RoomHistoryVisibility) -> Unit,
    onUpdateAliases: (String?, List<String>) -> Unit,
    onUpdatePowerLevel: (String, Long) -> Unit,
    onApplyPowerLevelChanges: (RoomPowerLevelChanges) -> Unit,
    onReportContent: (String, Int?, String?) -> Unit,
    onReportRoom: (String?) -> Unit,
    onOpenRoom: (String) -> Unit,
    onOpenMediaGallery: () -> Unit
) {
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showAliasesSheet by remember { mutableStateOf(false) }
    var showPowerLevelsSheet by remember { mutableStateOf(false) }
    var showGranularPermissionsSheet by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showJoinRuleDropdown by remember { mutableStateOf(false) }
    var showHistoryVisibilityDropdown by remember { mutableStateOf(false) }

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

                item {
                    RoomSettingsSection(
                        roomVersion = state.profile?.roomVersion,
                        joinRule = state.joinRule,
                        historyVisibility = state.historyVisibility,
                        isAdminBusy = state.isAdminBusy,
                        canManageSettings = state.canManageSettings,
                        onSetJoinRule = onSetJoinRule,
                        onSetHistoryVisibility = onSetHistoryVisibility,
                        showJoinRuleDropdown = { showJoinRuleDropdown = true },
                        showHistoryVisibilityDropdown = { showHistoryVisibilityDropdown = true }
                    )
                }

                item {
                    AliasesSection(
                        canonicalAlias = state.profile?.canonicalAlias,
                        altAliases = state.profile?.altAliases ?: emptyList(),
                        isAdminBusy = state.isAdminBusy,
                        canManageSettings = state.canManageSettings,
                        onEditAliases = { showAliasesSheet = true }
                    )
                }

                item {
                    PowerLevelsSection(
                        myPowerLevel = state.myPowerLevel,
                        powerLevels = state.powerLevels,
                        members = state.members,
                        isAdminBusy = state.isAdminBusy,
                        canManageSettings = state.canManageSettings,
                        onManagePowerLevels = { showPowerLevelsSheet = true },
                        onEditPermissions = { showGranularPermissionsSheet = true }
                    )
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

                item {
                    Button(
                        onClick = { showReportDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Report, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Report this room")
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

        // Aliases editing sheet
        if (showAliasesSheet) {
            RoomAliasesSheet(
                canonicalAlias = state.profile?.canonicalAlias,
                altAliases = state.profile?.altAliases ?: emptyList(),
                onUpdate = { canonical, alts ->
                    onUpdateAliases(canonical, alts)
                    showAliasesSheet = false
                },
                onDismiss = { showAliasesSheet = false }
            )
        }

        // Power levels management sheet
        if (showPowerLevelsSheet) {
            PowerLevelsSheet(
                members = state.members,
                powerLevels = state.powerLevels,
                myPowerLevel = state.myPowerLevel,
                onUpdatePowerLevel = onUpdatePowerLevel,
                onDismiss = { showPowerLevelsSheet = false }
            )
        }

        // Granular permissions sheet
        if (showGranularPermissionsSheet) {
            GranularPermissionsSheet(
                powerLevels = state.powerLevels,
                myPowerLevel = state.myPowerLevel,
                onUpdatePowerLevels = onApplyPowerLevelChanges,
                onDismiss = { showGranularPermissionsSheet = false }
            )
        }

        // Report content dialog
        if (showReportDialog) {
            ReportContentDialog(
                onReport = { reason ->
                    onReportRoom(reason)
                    showReportDialog = false
                },
                onDismiss = { showReportDialog = false }
            )
        }

        // Join Rule Dropdown
        if (showJoinRuleDropdown) {
            JoinRuleDropdown(
                currentRule = state.joinRule,
                onSelect = { rule ->
                    onSetJoinRule(rule)
                    showJoinRuleDropdown = false
                },
                onDismiss = { showJoinRuleDropdown = false }
            )
        }

        // History Visibility Dropdown
        if (showHistoryVisibilityDropdown) {
            HistoryVisibilityDropdown(
                currentVisibility = state.historyVisibility,
                onSelect = { visibility ->
                    onSetHistoryVisibility(visibility)
                    showHistoryVisibilityDropdown = false
                },
                onDismiss = { showHistoryVisibilityDropdown = false }
            )
        }

        val snackbarManager: SnackbarManager = koinInject()

        LaunchedEffect(state.error) {
            state.error?.let {
                snackbarManager.showError(it)
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
                profile.canonicalAlias?.let { alias ->
                    Text(
                        text = alias,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
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

@Composable
private fun RoomSettingsSection(
    roomVersion: String?,
    joinRule: RoomJoinRule?,
    historyVisibility: RoomHistoryVisibility?,
    isAdminBusy: Boolean,
    canManageSettings: Boolean,
    onSetJoinRule: (RoomJoinRule) -> Unit,
    onSetHistoryVisibility: (RoomHistoryVisibility) -> Unit,
    showJoinRuleDropdown: () -> Unit,
    showHistoryVisibilityDropdown: () -> Unit
) {
    SettingsSection(title = "Room Settings") {
        Column(modifier = Modifier.padding(16.dp)) {
            // Room Version
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Room Version",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    roomVersion ?: "Unknown",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))

            // Join Rule
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Who can join",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        joinRule?.name ?: "Unknown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (canManageSettings) {
                    TextButton(
                        onClick = showJoinRuleDropdown,
                        enabled = !isAdminBusy
                    ) {
                        Text("Change")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // History Visibility
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Message history",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        historyVisibility?.displayName ?: "Unknown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (canManageSettings) {
                    TextButton(
                        onClick = showHistoryVisibilityDropdown,
                        enabled = !isAdminBusy
                    ) {
                        Text("Change")
                    }
                }
            }
        }
    }
}

@Composable
private fun AliasesSection(
    canonicalAlias: String?,
    altAliases: List<String>,
    isAdminBusy: Boolean,
    canManageSettings: Boolean,
    onEditAliases: () -> Unit
) {
    SettingsSection(title = "Room Addresses") {
        Column(modifier = Modifier.padding(16.dp)) {
            // Canonical Alias
            if (canonicalAlias != null) {
                Text(
                    "Primary address",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    canonicalAlias,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
            }

            // Alternative Aliases
            if (altAliases.isNotEmpty()) {
                Text(
                    "Alternative addresses (${altAliases.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                altAliases.take(3).forEach { alias ->
                    Text(
                        alias,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (altAliases.size > 3) {
                    Text(
                        "+${altAliases.size - 3} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // Edit button
            if (canManageSettings) {
                Button(
                    onClick = onEditAliases,
                    enabled = !isAdminBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Edit, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Edit addresses")
                }
            }
        }
    }
}

@Composable
private fun PowerLevelsSection(
    myPowerLevel: Long,
    powerLevels: RoomPowerLevels?,
    members: List<MemberSummary>,
    isAdminBusy: Boolean,
    canManageSettings: Boolean,
    onManagePowerLevels: () -> Unit,
    onEditPermissions: () -> Unit
) {
    SettingsSection(title = "Permissions & Roles") {
        Column(modifier = Modifier.padding(16.dp)) {
            // My power level
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Your role",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    getRoleName(myPowerLevel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(12.dp))

            // Member count with custom power levels
            val membersWithCustomPower = powerLevels?.users?.size ?: 0
            if (membersWithCustomPower > 0) {
                Text(
                    "$membersWithCustomPower members have custom permissions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }

            // Action buttons
            if (canManageSettings) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onManagePowerLevels,
                        enabled = !isAdminBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.People, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Users")
                    }
                    Button(
                        onClick = onEditPermissions,
                        enabled = !isAdminBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AdminPanelSettings, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Permissions")
                    }
                }
            }
        }
    }
}

@Composable
private fun JoinRuleDropdown(
    currentRule: RoomJoinRule?,
    onSelect: (RoomJoinRule) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        RoomJoinRule.Public to "Public - Anyone can join",
        RoomJoinRule.Invite to "Invite only - Only invited users can join",
        RoomJoinRule.Knock to "Knock - Users can request to join"
    )

    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss
    ) {
        options.forEach { (rule, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = { onSelect(rule) },
                leadingIcon = if (currentRule == rule) {
                    { Icon(Icons.Default.Check, null) }
                } else null
            )
        }
    }
}

@Composable
private fun HistoryVisibilityDropdown(
    currentVisibility: RoomHistoryVisibility?,
    onSelect: (RoomHistoryVisibility) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        RoomHistoryVisibility.WorldReadable to "Anyone",
        RoomHistoryVisibility.Shared to "All members",
        RoomHistoryVisibility.Joined to "Members since they joined",
        RoomHistoryVisibility.Invited to "Members since they were invited"
    )

    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss
    ) {
        options.forEach { (visibility, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = { onSelect(visibility) },
                leadingIcon = if (currentVisibility == visibility) {
                    { Icon(Icons.Default.Check, null) }
                } else null
            )
        }
    }
}

private fun getRoleName(powerLevel: Long): String = when {
    powerLevel >= 100 -> "Admin"
    powerLevel >= 50 -> "Moderator"
    powerLevel > 0 -> "Custom"
    else -> "User"
}

private val RoomJoinRule.name: String
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
