package org.mlm.mages.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.mlmgames.settings.core.SettingsSchema
import io.github.mlmgames.settings.core.annotations.SettingAction
import io.github.mlmgames.settings.core.remote.RemoteFieldState
import io.github.mlmgames.settings.ui.AutoSettingsScreen
import io.github.mlmgames.settings.ui.ProvideStringResources
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.mimeType
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.mlm.mages.matrix.DeviceSummary
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.OwnProfile
import org.mlm.mages.matrix.ProfileField
import org.mlm.mages.platform.toTransferItem
import org.mlm.mages.settings.*
import org.mlm.mages.ui.AvatarEdit
import org.mlm.mages.ui.components.core.EditableAvatar
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.sheets.EnterRecoveryKeySheet
import org.mlm.mages.ui.components.sheets.SetupRecoverySheet
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.components.snackbar.snackbarHost
import org.mlm.mages.ui.components.snackbar.rememberErrorPoster
import org.mlm.mages.nav.Route
import org.mlm.mages.ui.theme.AppColors
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.popBack
import org.mlm.mages.ui.viewmodel.SecurityViewModel
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.*
import kotlin.reflect.KClass
import mages.shared.generated.resources.Res

private sealed interface SecuritySheet {
    data class SetupRecovery(val isChange: Boolean) : SecuritySheet
    data object EnterRecoveryKey : SecuritySheet
}

private const val TIME_ZONE_FIELD = "m.tz"
private const val SETTINGS_TAB_INDEX = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    viewModel: SecurityViewModel,
    backStack: NavBackStack<NavKey>,
    onOpenAccountSwitcher: () -> Unit,
    remoteStates: Map<String, RemoteFieldState> = emptyMap(),
) {
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val activeAccount by viewModel.activeAccount.collectAsState()
    val snackbarManager: SnackbarManager = koinInject()
    val postError = rememberErrorPoster(snackbarManager)
    val settingsSnackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current

    var verifyUserId by remember { mutableStateOf("") }
    var showVerifyUserDialog by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf<SecuritySheet?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val closeSearch: () -> Unit = {
        searchOpen = false
        searchQuery = ""
    }

    val pickScope = rememberCoroutineScope()
    var pendingAvatarPick by remember { mutableStateOf<((String, String) -> Unit)?>(null) }
    val avatarPicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        val onPicked = pendingAvatarPick ?: return@rememberFilePickerLauncher
        pendingAvatarPick = null
        val picked = file ?: return@rememberFilePickerLauncher
        pickScope.launch {
            val path = picked.toTransferItem().path ?: return@launch
            onPicked(path, picked.mimeType()?.toString() ?: "image/png")
        }
    }

    val isCurrentDeviceVerified = remember(state.devices) {
        state.devices.firstOrNull { it.isOwn }?.verified == true
    }

    LaunchedEffect(state.error) {
        state.error?.let { postError(it) }
    }

    LaunchedEffect(state.recoverySubmitSuccess) {
        if (state.recoverySubmitSuccess) {
            activeSheet = null
            viewModel.clearRecoverySubmitSuccess()
        }
    }

    LaunchedEffect(activeAccount?.id) {
        verifyUserId = ""
        showVerifyUserDialog = false
        activeSheet = null
        viewModel.dismissRecoveryKey()
    }

    LaunchedEffect(Unit) {
        viewModel.loadSecurityData()
    }

    LaunchedEffect(state.selectedTab) {
        if (state.selectedTab != SETTINGS_TAB_INDEX) closeSearch()
    }

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (searchOpen && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                closeSearch()
                true
            } else {
                false
            }
        },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        AnimatedContent(
                            targetState = searchOpen,
                            label = "SettingsSearchTitle"
                        ) { searching ->
                            if (searching) {
                                SettingsSearchField(
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it }
                                )
                            } else {
                                Text(
                                    stringResource(Res.string.security_settings),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (searchOpen) closeSearch() else backStack.popBack()
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                        }
                    },
                    actions = {
                        AnimatedVisibility(visible = !searchOpen) {
                            if (!settings.showSwitchAccountButton) {
                                IconButton(onClick = onOpenAccountSwitcher) {
                                    Icon(Icons.Default.SwitchAccount, stringResource(Res.string.account))
                                }
                            }
                        }
                        AnimatedVisibility(
                            visible = state.selectedTab == SETTINGS_TAB_INDEX && !searchOpen,
                            enter = fadeIn() + scaleIn(initialScale = 0.6f) + expandHorizontally(),
                            exit = fadeOut() + scaleOut(targetScale = 0.6f) + shrinkHorizontally()
                        ) {
                            IconButton(onClick = { searchOpen = true }) {
                                Icon(Icons.Default.Search, stringResource(Res.string.search_settings))
                            }
                        }
                    }
                )

                AnimatedVisibility(visible = !searchOpen) {
                    PrimaryTabRow(selectedTabIndex = state.selectedTab) {
                        Tab(
                            selected = state.selectedTab == 0,
                            onClick = { viewModel.setSelectedTab(0) },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(stringResource(Res.string.devices))
//                                    if (state.recoveryState == MatrixPort.RecoveryState.Incomplete) {
//                                        Spacer(Modifier.width(2.dp))
//                                        Badge { Text("!") }
//                                    }
                                }
                            },
                            icon = { Icon(Icons.Default.Devices, null) }
                        )
                        Tab(
                            selected = state.selectedTab == 1,
                            onClick = { viewModel.setSelectedTab(1) },
                            text = { Text(stringResource(Res.string.profile)) },
                            icon = { Icon(Icons.Default.Person, null) }
                        )
                        Tab(
                            selected = state.selectedTab == 2,
                            onClick = { viewModel.setSelectedTab(2) },
                            text = { Text(stringResource(Res.string.settings_tab)) },
                            icon = { Icon(Icons.Default.Settings, null) }
                        )
                    }
                }
            }
        },
        snackbarHost = { snackbarManager.snackbarHost() }
    ) { padding ->
        AnimatedContent(
            targetState = state.selectedTab,
            modifier = Modifier.padding(padding),
            label = "TabContent"
        ) { tab ->
            when (tab) {
                0 -> DevicesTab(
                    devices = state.devices,
                    isLoading = state.isLoadingDevices,
                    recoveryState = state.recoveryState,
                    backupState = state.backupState,
                    isKeyStorageEnabled = state.isKeyStorageEnabled,
                    isTogglingKeyStorage = state.isTogglingKeyStorage,
                    isCurrentDeviceVerified = isCurrentDeviceVerified,
                    enableShareHistoryOnInvite = state.enableShareHistoryOnInvite,
                    onRefresh = viewModel::refreshDevices,
                    onVerifyDevice = viewModel::startSelfVerify,
                    onVerifyUser = { showVerifyUserDialog = true },
                    onSetupRecovery = { activeSheet = SecuritySheet.SetupRecovery(isChange = false) },
                    onChangeRecovery = { activeSheet = SecuritySheet.SetupRecovery(isChange = true) },
                    onEnterRecoveryKey = { activeSheet = SecuritySheet.EnterRecoveryKey },
                    onToggleKeyStorage = viewModel::toggleKeyStorage,
                    onSetEnableShareHistoryOnInvite = viewModel::setEnableShareHistoryOnInvite
                )

                1 -> ProfileTab(
                    profile = state.ownProfile,
                    avatarPath = state.ownAvatarPath,
                    isLoading = state.isLoadingProfile,
                    isSaving = state.isSavingProfile,
                    accountManagementUrl = state.accountManagementUrl,
                    accountId = activeAccount?.id,
                    ignoredUsers = state.ignoredUsers,
                    onPickAvatar = { onPicked ->
                        pendingAvatarPick = onPicked
                        avatarPicker.launch()
                    },
                    onSave = viewModel::saveProfile,
                    onRetry = viewModel::loadProfile,
                    onUnignore = viewModel::unignoreUser,
                    onOpenAccountManagement = { url -> uriHandler.openUri(url) },
                    canSetProfileFields = state.canSetProfileFields,
                    profileFields = state.profileFields,
                    isSavingField = state.isSavingProfileField,
                    onSaveField = viewModel::saveProfileField,
                    onDeleteField = viewModel::deleteProfileField
                )

                2 -> SettingsTab(
                    settings = settings,
                    schema = viewModel.settingsSchema,
                    searchQuery = searchQuery,
                    onSettingChange = viewModel::updateSetting,
                    onSettingAction = viewModel::executeSettingAction,
                    snackbarHostState = settingsSnackbarHostState,
                    remoteStates = remoteStates,
                )
            }
        }
    }

    activeSheet?.let { sheet ->
        ModalBottomSheet(
            onDismissRequest = {
                activeSheet = null
                viewModel.dismissRecoveryKey()
            },
            sheetState = sheetState,
        ) {
            when (sheet) {
                is SecuritySheet.SetupRecovery -> SetupRecoverySheet(
                    viewModel = viewModel,
                    isChange = sheet.isChange,
                    onDone = {
                        viewModel.dismissRecoveryKey()
                        activeSheet = null
                    }
                )

                SecuritySheet.EnterRecoveryKey -> EnterRecoveryKeySheet(
                    viewModel = viewModel,
                    onResetRecovery = {
                        activeSheet = SecuritySheet.SetupRecovery(isChange = true)
                    }
                )
            }
        }
    }

    if (showVerifyUserDialog) {
        AlertDialog(
            onDismissRequest = { showVerifyUserDialog = false },
            icon = { Icon(Icons.Default.VerifiedUser, null) },
            title = { Text(stringResource(Res.string.verify_user)) },
            text = {
                OutlinedTextField(
                    value = verifyUserId,
                    onValueChange = { verifyUserId = it },
                    label = { Text(stringResource(Res.string.user_id)) },
                    placeholder = { Text(stringResource(Res.string.user_id_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (verifyUserId.isNotBlank()) {
                            viewModel.startUserVerify(verifyUserId.trim())
                            showVerifyUserDialog = false
                            verifyUserId = ""
                        }
                    },
                    enabled = verifyUserId.startsWith("@") && ":" in verifyUserId
                ) {
                    Text(stringResource(Res.string.verify))
                }
            },
            dismissButton = {
                TextButton(onClick = { showVerifyUserDialog = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ProfileTab(
    profile: OwnProfile?,
    avatarPath: String?,
    isLoading: Boolean,
    isSaving: Boolean,
    accountManagementUrl: String?,
    accountId: String?,
    ignoredUsers: List<String>,
    onPickAvatar: ((String, String) -> Unit) -> Unit,
    onSave: (String, AvatarEdit) -> Unit,
    onRetry: () -> Unit,
    onUnignore: (String) -> Unit,
    onOpenAccountManagement: (String) -> Unit,
    canSetProfileFields: Boolean,
    profileFields: List<ProfileField>,
    isSavingField: Boolean,
    onSaveField: (String, String) -> Unit,
    onDeleteField: (String) -> Unit
) {
    var displayName by remember(accountId, profile?.displayName) {
        mutableStateOf(profile?.displayName.orEmpty())
    }
    var avatarEdit by remember(accountId, profile?.avatarUrl) {
        mutableStateOf<AvatarEdit>(AvatarEdit.None)
    }
    var fieldEditorOpen by remember(accountId) { mutableStateOf(false) }
    var fieldEditorExisting by remember(accountId) { mutableStateOf(false) }
    var fieldEditorName by remember(accountId) { mutableStateOf("") }
    var fieldEditorValue by remember(accountId) { mutableStateOf("") }

    if (isLoading && profile == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularWavyProgressIndicator()
        }
        return
    }

    val current = profile
    if (current == null) {
        EmptyState(
            icon = Icons.Default.CloudOff,
            title = stringResource(Res.string.profile_load_failed),
            action = { TextButton(onClick = onRetry) { Text(stringResource(Res.string.retry)) } }
        )
        return
    }

    val nameChanged = displayName.trim() != current.displayName.orEmpty().trim()
    val canSave = !isSaving && (nameChanged || avatarEdit != AvatarEdit.None)
    val previewPath = when (val edit = avatarEdit) {
        is AvatarEdit.Replace -> edit.path
        AvatarEdit.Remove -> null
        AvatarEdit.None -> avatarPath
    }

    if (fieldEditorOpen) {
        AlertDialog(
            onDismissRequest = { fieldEditorOpen = false },
            title = {
                Text(
                    stringResource(
                        if (fieldEditorExisting) Res.string.edit_profile_field
                        else Res.string.add_profile_field
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = fieldEditorName,
                        onValueChange = { fieldEditorName = it },
                        enabled = !fieldEditorExisting,
                        singleLine = true,
                        label = { Text(stringResource(Res.string.profile_field_name)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!fieldEditorExisting && fieldEditorName.isBlank()) {
                        TextButton(onClick = { fieldEditorName = TIME_ZONE_FIELD }) {
                            Text(stringResource(Res.string.time_zone))
                        }
                    }
                    OutlinedTextField(
                        value = fieldEditorValue,
                        onValueChange = { fieldEditorValue = it },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.profile_field_value)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        fieldEditorOpen = false
                        onSaveField(fieldEditorName, fieldEditorValue)
                    },
                    enabled = fieldEditorName.isNotBlank() && !isSavingField
                ) {
                    Text(stringResource(Res.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { fieldEditorOpen = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                EditableAvatar(
                    name = displayName.ifBlank { current.userId },
                    avatarPath = previewPath,
                    size = Sizes.avatarLarge * 2,
                    isSaving = isSaving,
                    onClick = if (current.canChangeAvatar) {
                        { onPickAvatar { path, mime -> avatarEdit = AvatarEdit.Replace(path, mime) } }
                    } else {
                        null
                    },
                )
                Text(
                    current.userId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                enabled = current.canChangeDisplayName && !isSaving,
                singleLine = true,
                label = { Text(stringResource(Res.string.profile_display_name)) },
                placeholder = { Text(stringResource(Res.string.profile_display_name_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
            )
        }

        if (current.canChangeAvatar && current.avatarUrl != null) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.profile_remove_photo)) },
                    leadingContent = {
                        Icon(
                            Icons.Default.DeleteOutline,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.clickable(enabled = !isSaving) {
                        avatarEdit = AvatarEdit.Remove
                    }
                )
            }
        }

        item {
            Button(
                onClick = { onSave(displayName, avatarEdit) },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
            ) {
                if (isSaving) {
                    CircularWavyProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text(stringResource(Res.string.save))
                }
            }
        }

        if (canSetProfileFields) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(Res.string.profile_fields),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = {
                            fieldEditorExisting = false
                            fieldEditorName = ""
                            fieldEditorValue = ""
                            fieldEditorOpen = true
                        },
                        enabled = !isSaving && !isSavingField
                    ) {
                        Icon(Icons.Default.Add, null)
                        Text(stringResource(Res.string.add))
                    }
                }
            }

            items(profileFields, key = { it.name }) { field ->
                ListItem(
                    headlineContent = {
                        Text(
                            if (field.name == TIME_ZONE_FIELD) stringResource(Res.string.time_zone)
                            else field.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = {
                        Text(field.value, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    fieldEditorExisting = true
                                    fieldEditorName = field.name
                                    fieldEditorValue = field.value
                                    fieldEditorOpen = true
                                },
                                enabled = !isSaving && !isSavingField
                            ) {
                                Icon(Icons.Default.Edit, stringResource(Res.string.edit))
                            }
                            IconButton(
                                onClick = { onDeleteField(field.name) },
                                enabled = !isSaving && !isSavingField
                            ) {
                                Icon(Icons.Default.DeleteOutline, stringResource(Res.string.delete))
                            }
                        }
                    }
                )
            }
        }

        if (accountManagementUrl != null) {
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.manage_account)) },
                    leadingContent = {
                        Icon(
                            Icons.Default.OpenInNew,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.clickable { onOpenAccountManagement(accountManagementUrl) }
                )
            }
        }

        item {
            Text(
                stringResource(Res.string.ignored_users),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(top = Spacing.lg)
            )
        }

        if (ignoredUsers.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        stringResource(Res.string.no_ignored_users),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(Res.string.ignored_users_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(ignoredUsers, key = { it }) { mxid ->
                ListItem(
                    headlineContent = { Text(mxid, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        Icon(
                            Icons.Default.Block,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { onUnignore(mxid) }) {
                            Text(stringResource(Res.string.unignore))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DevicesTab(
    devices: List<DeviceSummary>,
    isLoading: Boolean,
    recoveryState: MatrixPort.RecoveryState,
    backupState: MatrixPort.BackupState,
    isKeyStorageEnabled: Boolean?,
    isTogglingKeyStorage: Boolean,
    isCurrentDeviceVerified: Boolean,
    enableShareHistoryOnInvite: Boolean,
    onRefresh: () -> Unit,
    onVerifyDevice: (String) -> Unit,
    onVerifyUser: () -> Unit,
    onSetupRecovery: () -> Unit,
    onChangeRecovery: () -> Unit,
    onEnterRecoveryKey: () -> Unit,
    onToggleKeyStorage: () -> Unit,
    onSetEnableShareHistoryOnInvite: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.lg)
    ) {
        item {
            Text(
                stringResource(Res.string.encryption),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(top = Spacing.lg, bottom = Spacing.sm)
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                val recoveryAction = when (recoveryState) {
                    MatrixPort.RecoveryState.Enabled -> Triple(
                        stringResource(Res.string.change_recovery_key),
                        stringResource(Res.string.recovery_enabled_subtitle),
                        onChangeRecovery
                    )

                    MatrixPort.RecoveryState.Incomplete -> Triple(
                        stringResource(Res.string.enter_recovery_key),
                        stringResource(Res.string.recovery_incomplete_subtitle),
                        onEnterRecoveryKey
                    )

                    else -> Triple(
                        stringResource(Res.string.set_up_recovery),
                        stringResource(Res.string.recovery_disabled_subtitle),
                        onSetupRecovery
                    )
                }

                ActionCard(
                    icon = Icons.Default.Key,
                    title = recoveryAction.first,
                    subtitle = recoveryAction.second,
                    badge = if (
                        recoveryState == MatrixPort.RecoveryState.Incomplete &&
                        !isCurrentDeviceVerified
                    ) stringResource(Res.string.incomplete_badge) else null,
                    enabled = isKeyStorageEnabled == true,
                    onClick = recoveryAction.third,
                    modifier = Modifier.weight(1f)
                )

                ActionCard(
                    icon = Icons.Default.VerifiedUser,
                    title = stringResource(Res.string.verify_user),
                    subtitle = stringResource(Res.string.verify_another_user),
                    onClick = onVerifyUser,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            val isBusy = isTogglingKeyStorage || backupState == MatrixPort.BackupState.Disabling
            val switchEnabled = !isBusy && isKeyStorageEnabled != null
            val checked = when {
                isKeyStorageEnabled == null -> false
                backupState == MatrixPort.BackupState.Disabling -> false
                else -> isKeyStorageEnabled
            }

            HorizontalDivider(modifier = Modifier.padding(top = Spacing.lg))

            ListItem(
                headlineContent = { Text(stringResource(Res.string.key_storage)) },
                supportingContent = {
                    Text(
                        stringResource(Res.string.key_storage_subtitle),
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                trailingContent = {
                    when {
                        isBusy || isKeyStorageEnabled == null ->
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(20.dp),
                            )

                        else ->
                            Switch(
                                checked = checked,
                                onCheckedChange = { onToggleKeyStorage() },
                                enabled = switchEnabled
                            )
                    }
                },
                modifier = Modifier.clickable(enabled = switchEnabled) { onToggleKeyStorage() }
            )
        }

        if (isKeyStorageEnabled == false) {
            item {
                Text(
                    stringResource(Res.string.key_storage_disabled_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                )
            }
        }

        item {
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(Res.string.share_history_on_invite)) },
                supportingContent = {
                    Text(
                        stringResource(Res.string.share_encrypted_history_with_invited_users_requires_app_restart),
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Default.History,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingContent = {
                    Switch(
                        checked = enableShareHistoryOnInvite,
                        onCheckedChange = onSetEnableShareHistoryOnInvite
                    )
                }
            )
        }

        item {
            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(Res.string.your_devices),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    if (isLoading) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Icon(Icons.Default.Refresh, stringResource(Res.string.refresh))
                    }
                }
            }
        }

        if (devices.isEmpty() && !isLoading) {
            item {
                EmptyState(
                    icon = Icons.Default.DevicesOther,
                    title = stringResource(Res.string.no_devices_found),
                    subtitle = stringResource(Res.string.try_refreshing),
                    modifier = Modifier.padding(Spacing.lg)
                )
            }
        }

        items(devices, key = { it.deviceId }) { device ->
            DeviceCard(
                device = device,
                onVerify = { onVerifyDevice(device.deviceId) }
            )
        }
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                null,
                modifier = Modifier.size(32.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(Spacing.sm))

            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2
            )

            if (badge != null) {
                Spacer(Modifier.height(Spacing.xs))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceSummary,
    onVerify: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (device.verified) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (device.verified) Icons.Default.VerifiedUser else Icons.Default.Smartphone,
                null,
                tint = if (device.verified) AppColors.Verified
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )

            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        device.displayName.ifBlank { device.deviceId },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    device.deviceId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (device.isOwn) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            stringResource(Res.string.this_device),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            if (!device.verified) {
                FilledTonalButton(onClick = onVerify) {
                    Text(stringResource(Res.string.verify))
                }
            } else {
                Icon(
                    Icons.Default.CheckCircle,
                    stringResource(Res.string.verified),
                    tint = AppColors.Verified
                )
            }
        }
    }
}

@Composable
private fun SettingsTab(
    settings: AppSettings,
    schema: SettingsSchema<AppSettings>,
    searchQuery: String,
    onSettingChange: (String, Any?) -> Unit,
    onSettingAction: suspend (KClass<out SettingAction>) -> Unit,
    snackbarHostState: SnackbarHostState,
    remoteStates: Map<String, RemoteFieldState> = emptyMap(),
) {
    val stringResourceProvider = rememberSettingsStringResourceProvider()
    ProvideStringResources(stringResourceProvider) {
        val query = searchQuery.trim()
        val shownSchema = remember(schema, query, stringResourceProvider) {
            if (query.isEmpty()) schema else schema.filteredForSearch(query, stringResourceProvider)
        }
        Box(modifier = Modifier.fillMaxSize()) {
            if (query.isNotEmpty() && shownSchema.fields.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.SearchOff,
                    title = stringResource(Res.string.no_settings_found),
                    subtitle = stringResource(Res.string.try_different_search),
                )
            } else {
                key(query) {
                    AutoSettingsScreen(
                        schema = shownSchema,
                        value = settings,
                        onSet = onSettingChange,
                        onAction = onSettingAction,
                        modifier = Modifier.fillMaxSize(),
                        snackbarHostState = snackbarHostState,
                        remoteStates = remoteStates,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    DisposableEffect(Unit) {
        onDispose { keyboard?.hide() }
    }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        textStyle = MaterialTheme.typography.titleMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        decorationBox = { innerTextField ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.search_settings),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, stringResource(Res.string.clear_search))
                    }
                }
            }
        },
    )
}

