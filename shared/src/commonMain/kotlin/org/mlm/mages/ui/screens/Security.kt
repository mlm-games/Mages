package org.mlm.mages.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.mlmgames.settings.core.SettingsSchema
import io.github.mlmgames.settings.core.annotations.SettingAction
import io.github.mlmgames.settings.ui.AutoSettingsScreen
import io.github.mlmgames.settings.ui.CategoryConfig
import org.koin.compose.koinInject
import org.mlm.mages.matrix.DeviceSummary
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.Presence
import org.mlm.mages.settings.*
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.sheets.EnterRecoveryKeySheet
import org.mlm.mages.ui.components.sheets.SetupRecoverySheet
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.components.snackbar.snackbarHost
import org.mlm.mages.ui.components.snackbar.rememberErrorPoster
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.popBack
import org.mlm.mages.ui.viewmodel.SecurityViewModel
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.*
import kotlin.reflect.KClass

private sealed interface SecuritySheet {
    data class SetupRecovery(val isChange: Boolean) : SecuritySheet
    data object EnterRecoveryKey : SecuritySheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    viewModel: SecurityViewModel,
    backStack: NavBackStack<NavKey>,
    onOpenAccountSwitcher: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val snackbarManager: SnackbarManager = koinInject()
    val postError = rememberErrorPoster(snackbarManager)
    val settingsSnackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current

    var verifyUserId by remember { mutableStateOf("") }
    var showVerifyUserDialog by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf<SecuritySheet?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

    LaunchedEffect(Unit) {
        viewModel.loadSecurityData()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(Res.string.security_settings),
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { backStack.popBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenAccountSwitcher) {
                            Icon(Icons.Default.SwitchAccount, stringResource(Res.string.account))
                        }
                    }
                )

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
//                                if (state.recoveryState == MatrixPort.RecoveryState.Incomplete) {
//                                    Spacer(Modifier.width(2.dp))
//                                    Badge { Text("!") }
//                                }
                            }
                        },
                        icon = { Icon(Icons.Default.Devices, null) }
                    )
                    Tab(
                        selected = state.selectedTab == 1,
                        onClick = { viewModel.setSelectedTab(1) },
                        text = { Text(stringResource(Res.string.privacy)) },
                        icon = { Icon(Icons.Default.PrivacyTip, null) }
                    )
                    Tab(
                        selected = state.selectedTab == 2,
                        onClick = { viewModel.setSelectedTab(2) },
                        text = { Text(stringResource(Res.string.settings_tab)) },
                        icon = { Icon(Icons.Default.Settings, null) }
                    )
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
                    accountManagementUrl = state.accountManagementUrl,
                    recoveryState = state.recoveryState,
                    backupState = state.backupState,
                    isKeyStorageEnabled = state.isKeyStorageEnabled,
                    isTogglingKeyStorage = state.isTogglingKeyStorage,
                    isCurrentDeviceVerified = isCurrentDeviceVerified,
                    onRefresh = viewModel::refreshDevices,
                    onVerifyDevice = viewModel::startSelfVerify,
                    onVerifyUser = { showVerifyUserDialog = true },
                    onSetupRecovery = { activeSheet = SecuritySheet.SetupRecovery(isChange = false) },
                    onChangeRecovery = { activeSheet = SecuritySheet.SetupRecovery(isChange = true) },
                    onEnterRecoveryKey = { activeSheet = SecuritySheet.EnterRecoveryKey },
                    onToggleKeyStorage = viewModel::toggleKeyStorage,
                    onOpenAccountManagement = { url -> uriHandler.openUri(url) }
                )
                1 -> PrivacyTab(
                    ignoredUsers = state.ignoredUsers,
                    onUnignore = viewModel::unignoreUser
                )
                2 -> SettingsTab(
                    settings = settings,
                    schema = viewModel.settingsSchema,
                    currentPresence = state.presence.currentPresence,
                    statusMessage = state.presence.statusMessage,
                    isSavingPresence = state.presence.isSaving,
                    onPresenceChange = viewModel::setPresence,
                    onStatusChange = viewModel::setStatusMessage,
                    onSavePresence = viewModel::savePresence,
                    onSettingChange = viewModel::updateSetting,
                    onSettingAction = viewModel::executeSettingAction,
                    snackbarHostState = settingsSnackbarHostState
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
private fun DevicesTab(
    devices: List<DeviceSummary>,
    isLoading: Boolean,
    accountManagementUrl: String?,
    recoveryState: MatrixPort.RecoveryState,
    backupState: MatrixPort.BackupState,
    isKeyStorageEnabled: Boolean?,
    isTogglingKeyStorage: Boolean,
    isCurrentDeviceVerified: Boolean,
    onRefresh: () -> Unit,
    onVerifyDevice: (String) -> Unit,
    onVerifyUser: () -> Unit,
    onSetupRecovery: () -> Unit,
    onChangeRecovery: () -> Unit,
    onEnterRecoveryKey: () -> Unit,
    onToggleKeyStorage: () -> Unit,
    onOpenAccountManagement: (String) -> Unit
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

        if (accountManagementUrl != null) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.manage_account)) },
                    leadingContent = {
                        Icon(
                            Icons.Default.Person,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.clickable { onOpenAccountManagement(accountManagementUrl) }
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
                tint = if (device.verified) Color(0xFF4CAF50)
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
                            "This device",
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
                    "Verified",
                    tint = Color(0xFF4CAF50)
                )
            }
        }
    }
}

@Composable
private fun PrivacyTab(
    ignoredUsers: List<String>,
    onUnignore: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg)
    ) {
        Text(
            stringResource(Res.string.ignored_users),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(Spacing.md))

        if (ignoredUsers.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Block,
                title = stringResource(Res.string.no_ignored_users),
                subtitle = stringResource(Res.string.ignored_users_subtitle)
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(ignoredUsers) { mxid ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Person, null)
                            Spacer(Modifier.width(Spacing.md))
                            Text(mxid, Modifier.weight(1f))
                            TextButton(onClick = { onUnignore(mxid) }) {
                                Text(stringResource(Res.string.unignore))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(
    settings: AppSettings,
    schema: SettingsSchema<AppSettings>,
    currentPresence: Presence,
    statusMessage: String,
    isSavingPresence: Boolean,
    onPresenceChange: (Presence) -> Unit,
    onStatusChange: (String) -> Unit,
    onSavePresence: () -> Unit,
    onSettingChange: (String, Any) -> Unit,
    onSettingAction: suspend (KClass<out SettingAction>) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AutoSettingsScreen(
            schema = schema,
            value = settings,
            onSet = onSettingChange,
            onAction = onSettingAction,
            modifier = Modifier.fillMaxSize(),
            categoryConfigs = listOf(
                CategoryConfig(Account::class, stringResource(Res.string.account)),
                CategoryConfig(Appearance::class, stringResource(Res.string.appearance)),
                CategoryConfig(Notifications::class, stringResource(Res.string.notifications)),
                CategoryConfig(Privacy::class, stringResource(Res.string.privacy)),
                CategoryConfig(Calls::class, stringResource(Res.string.calls)),
                CategoryConfig(Storage::class, stringResource(Res.string.storage)),
                CategoryConfig(Advanced::class, stringResource(Res.string.advanced)),
            ),
            snackbarHostState = snackbarHostState
        )
    }
}

@Composable
private fun PresenceOption(
    presence: Presence,
    currentPresence: Presence,
    title: String,
    color: Color,
    onClick: () -> Unit
) {
    val isSelected = presence == currentPresence

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = color,
            shape = CircleShape,
            modifier = Modifier.size(12.dp)
        ) {}

        Spacer(Modifier.width(Spacing.md))

        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )

        if (isSelected) {
            Icon(
                Icons.Default.CheckCircle,
                "Selected",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
