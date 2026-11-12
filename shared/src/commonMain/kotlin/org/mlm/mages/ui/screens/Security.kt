package org.mlm.mages.ui.screens

import org.jetbrains.compose.resources.painterResource
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
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
import io.github.mlmgames.settings.core.SettingField
import io.github.mlmgames.settings.core.SettingsSchema
import io.github.mlmgames.settings.core.annotations.SettingAction
import io.github.mlmgames.settings.core.annotations.SettingPlatform
import io.github.mlmgames.settings.core.platform.currentPlatform
import io.github.mlmgames.settings.core.remote.RemoteFieldState
import io.github.mlmgames.settings.ui.AutoSettingsScreen
import io.github.mlmgames.settings.ui.CustomTypeHandler
import io.github.mlmgames.settings.ui.ProvideStringResources
import io.github.mlmgames.settings.ui.components.SettingsItem
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.mimeType
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.mlm.mages.matrix.DeviceSummary
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.OwnProfile
import org.mlm.mages.matrix.ProfileField
import org.mlm.mages.matrix.isMatrixUserId
import org.mlm.mages.platform.toTransferItem
import org.mlm.mages.settings.*
import org.mlm.mages.ui.AvatarEdit
import org.mlm.mages.ui.components.core.EditableAvatar
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.core.ReactionGlyph
import org.mlm.mages.ui.components.common.MemberSuggestionGroup
import org.mlm.mages.ui.components.common.MemberSuggestionList
import org.mlm.mages.ui.components.common.rememberDirectoryMembers
import org.mlm.mages.ui.components.QrLinkDeviceDialog
import org.mlm.mages.ui.components.sheets.EmojiPickerSheet
import org.mlm.mages.ui.components.sheets.EnterRecoveryKeySheet
import org.mlm.mages.ui.components.sheets.ReactionImagePickerSheet
import org.mlm.mages.ui.components.sheets.SetupRecoverySheet
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.components.snackbar.snackbarHost
import org.mlm.mages.ui.components.snackbar.rememberErrorPoster
import org.mlm.mages.ui.components.viewer.AvatarViewerHost
import org.mlm.mages.ui.components.viewer.rememberAvatarViewerState
import org.mlm.mages.nav.Route
import org.mlm.mages.ui.theme.AppColors
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.popBack
import org.mlm.mages.ui.viewmodel.QuickReactionsViewModel
import org.mlm.mages.ui.viewmodel.SecurityViewModel
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.*
import kotlin.reflect.KClass

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
    val avatarViewer = rememberAvatarViewerState()

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
                            Icon(painterResource(Res.drawable.ic_arrow_back), stringResource(Res.string.back))
                        }
                    },
                    actions = {
                        AnimatedVisibility(visible = !searchOpen) {
                            if (!settings.showSwitchAccountButton) {
                                IconButton(onClick = onOpenAccountSwitcher) {
                                    Icon(painterResource(Res.drawable.ic_switch_account), stringResource(Res.string.account))
                                }
                            }
                        }
                        AnimatedVisibility(
                            visible = state.selectedTab == SETTINGS_TAB_INDEX && !searchOpen,
                            enter = fadeIn() + scaleIn(initialScale = 0.6f) + expandHorizontally(),
                            exit = fadeOut() + scaleOut(targetScale = 0.6f) + shrinkHorizontally()
                        ) {
                            IconButton(onClick = { searchOpen = true }) {
                                Icon(painterResource(Res.drawable.ic_search), stringResource(Res.string.search_settings))
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
                            icon = { Icon(painterResource(Res.drawable.ic_devices), null) }
                        )
                        Tab(
                            selected = state.selectedTab == 1,
                            onClick = { viewModel.setSelectedTab(1) },
                            text = { Text(stringResource(Res.string.profile)) },
                            icon = { Icon(painterResource(Res.drawable.ic_person), null) }
                        )
                        Tab(
                            selected = state.selectedTab == 2,
                            onClick = { viewModel.setSelectedTab(2) },
                            text = { Text(stringResource(Res.string.settings_tab)) },
                            icon = { Icon(painterResource(Res.drawable.ic_settings), null) }
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
                    onSetEnableShareHistoryOnInvite = viewModel::setEnableShareHistoryOnInvite,
                    onLinkDevice = viewModel::openQrGrant,
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
                    onViewAvatar = {
                        viewModel.openOwnAvatarExternally { path, _ ->
                            avatarViewer.show(
                                path,
                                state.ownProfile?.displayName ?: state.ownProfile?.userId,
                            )
                        }
                    },
                    onViewLocalAvatar = { path ->
                        avatarViewer.show(
                            path,
                            state.ownProfile?.displayName ?: state.ownProfile?.userId,
                        )
                    },
                    onSave = viewModel::saveProfile,
                    onRetry = viewModel::loadProfile,
                    onUnignore = viewModel::unignoreUser,
                    onOpenAccountManagement = { url -> uriHandler.openUri(url) },
                    canSetProfileFields = state.canSetProfileFields,
                    profileFields = state.profileFields,
                    isSavingField = state.isSavingProfileField,
                    onSaveField = viewModel::saveProfileField,
                    onDeleteField = viewModel::deleteProfileField,
                    onSaveStatus = viewModel::saveStatus,
                    onClearStatus = viewModel::clearStatus
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

    if (state.qrGrantActive) {
        QrLinkDeviceDialog(
            phase = state.qrGrantPhase,
            checkCode = state.qrGrantCheckCode,
            verificationUri = state.qrGrantVerificationUri,
            error = state.qrGrantError,
            onQrDecoded = viewModel::startQrGrant,
            onConfirm = viewModel::confirmQrGrant,
            onRetry = viewModel::openQrGrant,
            onCancel = viewModel::closeQrGrant,
        )
    }

    if (showVerifyUserDialog) {
        AlertDialog(
            onDismissRequest = { showVerifyUserDialog = false },
            icon = { Icon(painterResource(Res.drawable.ic_verified_user_filled), null) },
            title = { Text(stringResource(Res.string.verify_user)) },
            text = {
                val directoryMembers = rememberDirectoryMembers(verifyUserId)
                Column {
                    OutlinedTextField(
                        value = verifyUserId,
                        onValueChange = { verifyUserId = it },
                        label = { Text(stringResource(Res.string.user_id)) },
                        placeholder = { Text(stringResource(Res.string.user_id_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    MemberSuggestionList(
                        groups = listOf(
                            MemberSuggestionGroup(
                                label = stringResource(Res.string.people_on_your_server),
                                members = directoryMembers
                            )
                        ),
                        onPick = { verifyUserId = it.userId },
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
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
                    enabled = isMatrixUserId(verifyUserId)
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

    AvatarViewerHost(avatarViewer)
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
    onViewAvatar: () -> Unit,
    onViewLocalAvatar: (String) -> Unit,
    onSave: (String, AvatarEdit) -> Unit,
    onRetry: () -> Unit,
    onUnignore: (String) -> Unit,
    onOpenAccountManagement: (String) -> Unit,
    canSetProfileFields: Boolean,
    profileFields: List<ProfileField>,
    isSavingField: Boolean,
    onSaveField: (String, String) -> Unit,
    onDeleteField: (String) -> Unit,
    onSaveStatus: (emoji: String, text: String) -> Unit,
    onClearStatus: () -> Unit
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
    var statusEditorOpen by remember(accountId) { mutableStateOf(false) }
    var statusEmoji by remember(accountId, profile?.statusEmoji) { mutableStateOf(profile?.statusEmoji.orEmpty()) }
    var statusText by remember(accountId, profile?.statusText) { mutableStateOf(profile?.statusText.orEmpty()) }

    if (isLoading && profile == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularWavyProgressIndicator()
        }
        return
    }

    val current = profile
    if (current == null) {
        EmptyState(
            icon = painterResource(Res.drawable.ic_cloud_off),
            title = stringResource(Res.string.profile_load_failed),
            action = { TextButton(onClick = onRetry) { Text(stringResource(Res.string.retry)) } }
        )
        return
    }

    val pickedAvatarPath = (avatarEdit as? AvatarEdit.Replace)?.path

    val nameChanged = displayName.trim() != current.displayName.orEmpty().trim()
    val canSave = !isSaving && (nameChanged || avatarEdit != AvatarEdit.None)
    val previewPath = when (val edit = avatarEdit) {
        is AvatarEdit.Replace -> edit.path
        AvatarEdit.Remove -> null
        AvatarEdit.None -> avatarPath
    }
    val viewAvatar: (() -> Unit)? = when {
        pickedAvatarPath != null -> ({ onViewLocalAvatar(pickedAvatarPath) })
        previewPath != null -> onViewAvatar
        else -> null
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

    if (statusEditorOpen) {
        AlertDialog(
            onDismissRequest = { statusEditorOpen = false },
            title = { Text(stringResource(Res.string.edit_status)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = statusEmoji,
                        onValueChange = { statusEmoji = it },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.status_placeholder_emoji)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = statusText,
                        onValueChange = { statusText = it },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.status_placeholder_text)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        statusEditorOpen = false
                        onSaveStatus(statusEmoji, statusText)
                    },
                    enabled = statusEmoji.isNotBlank() && statusText.isNotBlank() && !isSaving
                ) {
                    Text(stringResource(Res.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { statusEditorOpen = false }) {
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
                    onAvatarClick = viewAvatar,
                    onEditClick = if (current.canChangeAvatar) {
                        { onPickAvatar { path, mime -> avatarEdit = AvatarEdit.Replace(path, mime) } }
                    } else {
                        null
                    },
                )
                Text(
                    current.userId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                            painterResource(Res.drawable.ic_delete),
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
                val statusLine = listOfNotNull(
                    current.statusEmoji?.takeIf { it.isNotBlank() },
                    current.statusText?.takeIf { it.isNotBlank() }
                ).joinToString(" ")
                ListItem(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (statusLine.isNotBlank()) statusLine else stringResource(Res.string.status_not_set),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (statusLine.isNotBlank()) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (current.callJoinedTsMs != null) {
                                Spacer(Modifier.width(Spacing.sm))
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        stringResource(Res.string.in_call_chip),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    },
                    supportingContent = {
                        Text(stringResource(Res.string.profile_status))
                    },
                    leadingContent = {
                        Icon(
                            painterResource(Res.drawable.ic_tag),
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (statusLine.isNotBlank()) {
                                TextButton(
                                    onClick = onClearStatus,
                                    enabled = !isSaving
                                ) {
                                    Text(stringResource(Res.string.clear_status))
                                }
                            }
                            IconButton(
                                onClick = {
                                    statusEmoji = current.statusEmoji.orEmpty()
                                    statusText = current.statusText.orEmpty()
                                    statusEditorOpen = true
                                },
                                enabled = !isSaving
                            ) {
                                Icon(painterResource(Res.drawable.ic_edit), stringResource(Res.string.edit))
                            }
                        }
                    }
                )
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
                        Icon(painterResource(Res.drawable.ic_add), null)
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
                                Icon(painterResource(Res.drawable.ic_edit), stringResource(Res.string.edit))
                            }
                            IconButton(
                                onClick = { onDeleteField(field.name) },
                                enabled = !isSaving && !isSavingField
                            ) {
                                Icon(painterResource(Res.drawable.ic_delete), stringResource(Res.string.delete))
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
                            painterResource(Res.drawable.ic_open_in_new),
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
                            painterResource(Res.drawable.ic_block),
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
    onSetEnableShareHistoryOnInvite: (Boolean) -> Unit,
    onLinkDevice: () -> Unit,
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
                    icon = painterResource(Res.drawable.ic_key),
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
                    icon = painterResource(Res.drawable.ic_verified_user_filled),
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
                        painterResource(Res.drawable.ic_history),
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

        if (currentPlatform != SettingPlatform.WEB) {
            item {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.qr_link_device)) },
                    supportingContent = {
                        Text(
                            stringResource(Res.string.qr_link_device_subtitle),
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    leadingContent = {
                        Icon(
                            painterResource(Res.drawable.ic_qr_code_2),
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.clickable { onLinkDevice() }
                )
            }
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
                        Icon(painterResource(Res.drawable.ic_refresh), stringResource(Res.string.refresh))
                    }
                }
            }
        }

        if (devices.isEmpty() && !isLoading) {
            item {
                EmptyState(
                    icon = painterResource(Res.drawable.ic_devices_other),
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
    icon: Painter,
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
                if (device.verified) painterResource(Res.drawable.ic_verified_user_filled) else painterResource(Res.drawable.ic_smartphone),
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
                    painterResource(Res.drawable.ic_check_circle),
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
                    icon = painterResource(Res.drawable.ic_search_off),
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
                        customTypeHandlers = listOf(
                            CustomTypeHandler<AppSettings>(
                                typeClass = ReactionEmojiPicker::class,
                                render = { field, _, value, enabled, onSet ->
                                    ReactionEmojiSettingRow(field, value, enabled, onSet)
                                },
                            ),
                        ),
                        remoteStates = remoteStates,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReactionEmojiSettingRow(
    field: SettingField<AppSettings, *>,
    value: AppSettings,
    enabled: Boolean,
    onSet: (String, Any?) -> Unit,
) {
    val pickerViewModel: QuickReactionsViewModel = koinViewModel()
    val pickerState by pickerViewModel.state.collectAsState()
    val packs = pickerState.packs
    var showPicker by remember { mutableStateOf(false) }
    var showPackPicker by remember { mutableStateOf(false) }
    val current = field.get(value)?.toString().orEmpty().ifBlank { "👍" }
    val isImage = isImageReaction(current)
    val currentImage = remember(packs, current) {
        packs.flatMap { it.images }.firstOrNull { it.mxcUrl == current }
    }
    val imageLabel = currentImage?.shortcode
    val trailingGlyph: (@Composable () -> Unit)? = if (isImage) {
        ({
            ReactionGlyph(
                key = current,
                size = 24.dp,
                resolvePreview = { pickerViewModel.preview(it, currentImage?.mime, false) },
            )
        })
    } else {
        null
    }
    val openPacks: (() -> Unit)? = if (packs.isEmpty()) {
        null
    } else {
        ({
            showPicker = false
            showPackPicker = true
        })
    }

    SettingsItem(
        title = stringResource(Res.string.setting_message_double_tap_reaction),
        subtitle = imageLabel ?: if (isImage) stringResource(Res.string.quick_reactions_from_packs) else current,
        description = stringResource(Res.string.setting_message_double_tap_reaction_description),
        enabled = enabled,
        onClick = { showPicker = true },
        trailingContent = trailingGlyph,
    )
    if (showPicker) {
        EmojiPickerSheet(
            onDismiss = { showPicker = false },
            onEmojiSelected = { emoji ->
                showPicker = false
                onSet(field.name, emoji)
            },
            onOpenPacks = openPacks,
        )
    }
    if (showPackPicker) {
        ReactionImagePickerSheet(
            packs = packs,
            isEncryptedRoom = false,
            resolvePreview = { _, mxcUrl, mime, full ->
                pickerViewModel.preview(mxcUrl, mime, full)
            },
            onImageSelected = { image ->
                showPackPicker = false
                onSet(field.name, image.mxcUri)
            },
            onDismiss = { showPackPicker = false },
        )
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
                        Icon(painterResource(Res.drawable.ic_close), stringResource(Res.string.clear_search))
                    }
                }
            }
        },
    )
}

