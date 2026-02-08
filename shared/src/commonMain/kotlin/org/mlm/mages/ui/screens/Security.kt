package org.mlm.mages.ui.screens

import androidx.compose.animation.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.mlmgames.settings.core.SettingsSchema
import io.github.mlmgames.settings.core.annotations.SettingAction
import io.github.mlmgames.settings.ui.AutoSettingsScreen
import io.github.mlmgames.settings.ui.CategoryConfig
import org.mlm.mages.matrix.DeviceSummary
import org.mlm.mages.matrix.Presence
import org.mlm.mages.settings.*
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.dialogs.RecoveryDialog
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.popBack
import org.mlm.mages.ui.viewmodel.SecurityViewModel
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.*
import kotlin.reflect.KClass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    viewModel: SecurityViewModel,
    backStack: NavBackStack<NavKey>,
    onOpenAccountSwitcher: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var verifyUserId by remember { mutableStateOf("") }
    var showVerifyUserDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(Res.string.security_settings), fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { backStack.popBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenAccountSwitcher) {
                            Icon(
                                Icons.Default.SwitchAccount,
                                stringResource(Res.string.account),
                            )
                        }
                    }
                )

                TabRow(selectedTabIndex = state.selectedTab) {
                    Tab(
                        selected = state.selectedTab == 0,
                        onClick = { viewModel.setSelectedTab(0) },
                        text = { Text(stringResource(Res.string.devices)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                    onRefresh = viewModel::refreshDevices,
                    onVerifyDevice = viewModel::startSelfVerify,
                    onVerifyUser = { showVerifyUserDialog = true },
                    onOpenRecovery = viewModel::openRecoveryDialog
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
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }

    // Recovery Key Dialog
    if (state.showRecoveryDialog) {
        RecoveryDialog(
            keyValue = state.recoveryKeyInput,
            onChange = viewModel::setRecoveryKey,
            onCancel = viewModel::closeRecoveryDialog,
            onConfirm = viewModel::submitRecoveryKey
        )
    }

    // Verify User Dialog
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
    onRefresh: () -> Unit,
    onVerifyDevice: (String) -> Unit,
    onVerifyUser: () -> Unit,
    onOpenRecovery: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                ActionCard(
                    icon = Icons.Default.Key,
                    title = stringResource(Res.string.recovery),
                    onClick = onOpenRecovery,
                    modifier = Modifier.weight(1f)
                )
                ActionCard(
                    icon = Icons.Default.VerifiedUser,
                    title = stringResource(Res.string.verify_user),
                    onClick = onVerifyUser,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(Res.string.your_devices),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
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
                    subtitle = stringResource(Res.string.try_refreshing)
                )
            }
        }

        items(devices.filter { !it.isOwn }, key = { it.deviceId }) { device ->
            DeviceCard(device = device, onVerify = { onVerifyDevice(device.deviceId) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceSummary,
    onVerify: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (device.verified)
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (device.verified) Icons.Default.VerifiedUser
                else Icons.Default.Smartphone,
                null,
                tint = if (device.verified) Color(0xFF4CAF50)
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )

            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.displayName.ifBlank { device.deviceId },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    device.deviceId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        Text(stringResource(Res.string.ignored_users), style = MaterialTheme.typography.titleMedium)
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
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
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
                CategoryConfig(Calls::class, stringResource(Res.string.privacy)),
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