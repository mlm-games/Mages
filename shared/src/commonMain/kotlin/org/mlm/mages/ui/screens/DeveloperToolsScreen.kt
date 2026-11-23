package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.platform.currentPlatform
import kotlinx.coroutines.launch
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.mlm.mages.GitInfo
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.PushRuleInfo
import org.mlm.mages.matrix.PushRuleKind
import org.mlm.mages.platform.appVersion
import org.mlm.mages.push.PushHistoryEntry
import org.mlm.mages.push.PushHistoryStore
import org.mlm.mages.platform.rememberClipboardCopy
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.components.settings.SettingSwitchRow
import org.mlm.mages.ui.components.settings.SettingsSection
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.crashApp
import org.mlm.mages.ui.util.formatBytes

private val logLevels = listOf("Verbose", "Debug", "Info", "Warn", "Error", "Assert")

@Composable
private fun ClickableInfoRow(
    title: String,
    value: String?,
    onClick: () -> Unit,
) {
    ListItem(onClick = onClick) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun DeveloperToolsRoute(
    onBack: () -> Unit,
    onOpenMediaCache: () -> Unit,
) {
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val service: MatrixService = koinInject()
    val settings by settingsRepository.flow.collectAsState(initial = AppSettings())
    val account by service.activeAccount.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val copy = rememberClipboardCopy()
    val version = remember { appVersion() }
    var cacheBytes by remember { mutableStateOf<ULong?>(null) }
    var pushRules by remember { mutableStateOf<List<PushRuleInfo>>(emptyList()) }
    val pushHistory by PushHistoryStore.entries.collectAsState()

    LaunchedEffect(Unit) {
        val port = service.portOrNull ?: return@LaunchedEffect
        cacheBytes = runCatching { port.mediaCacheOverview()?.totalBytes }.getOrNull()
        pushRules = port.pushRules().getOrDefault(emptyList())
    }

    DeveloperToolsScreen(
        version = version,
        platform = currentPlatform.name,
        gitBranch = GitInfo.BRANCH,
        gitSha = GitInfo.SHA,
        developerMode = settings.developerMode,
        logLevel = settings.developerLogLevel,
        userId = account?.userId,
        deviceId = account?.deviceId,
        homeserver = account?.homeserver,
        cacheSize = cacheBytes?.let { formatBytes(it.toLong()) },
        onDeveloperModeChange = { enabled ->
            scope.launch { settingsRepository.update { it.copy(developerMode = enabled) } }
        },
        onLogLevelChange = { level ->
            scope.launch { settingsRepository.update { it.copy(developerLogLevel = level) } }
        },
        onCopy = copy,
        onClearCache = {
            scope.launch {
                val port = service.portOrNull ?: return@launch
                if (port.clearMediaCache().isSuccess) {
                    cacheBytes = runCatching { port.mediaCacheOverview()?.totalBytes }.getOrNull()
                }
            }
        },
        onOpenMediaCache = onOpenMediaCache,
        onOptimizeStores = {
            scope.launch { service.portOrNull?.optimizeStores() }
        },
        onTogglePushRule = { kind, ruleId, enabled ->
            scope.launch {
                val result = service.portOrNull?.setPushRuleEnabled(kind, ruleId, enabled)
                if (result?.isSuccess == true) {
                    pushRules = pushRules.map {
                        if (it.kind == kind && it.ruleId == ruleId) it.copy(enabled = enabled) else it
                    }
                }
            }
        },
        pushRules = pushRules,
        pushHistory = pushHistory,
        onCrash = ::crashApp,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperToolsScreen(
    version: String,
    platform: String,
    gitBranch: String,
    gitSha: String,
    developerMode: Boolean,
    logLevel: String,
    userId: String?,
    deviceId: String?,
    homeserver: String?,
    cacheSize: String?,
    pushRules: List<PushRuleInfo>,
    pushHistory: List<PushHistoryEntry>,
    onDeveloperModeChange: (Boolean) -> Unit,
    onLogLevelChange: (String) -> Unit,
    onCopy: (String, Boolean) -> Unit,
    onClearCache: () -> Unit,
    onOpenMediaCache: () -> Unit,
    onOptimizeStores: () -> Unit,
    onTogglePushRule: (PushRuleKind, String, Boolean) -> Unit,
    onCrash: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var logLevelExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.developer_tools_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = Spacing.sm),
        ) {
            item {
                SettingsSection(title = stringResource(Res.string.developer_tools_preferences)) {
                    SettingSwitchRow(
                        icon = painterResource(Res.drawable.ic_admin_panel_settings),
                        title = stringResource(Res.string.setting_developer_mode),
                        subtitle = stringResource(Res.string.setting_developer_mode_description),
                        checked = developerMode,
                        onCheckedChange = onDeveloperModeChange,
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.developer_tools_log_level)) },
                        supportingContent = { Text(logLevel) },
                        trailingContent = {
                            TextButton(onClick = { logLevelExpanded = true }) {
                                Text(stringResource(Res.string.developer_tools_change))
                            }
                        },
                    )
                    DropdownMenu(
                        expanded = logLevelExpanded,
                        onDismissRequest = { logLevelExpanded = false },
                    ) {
                        logLevels.forEach { level ->
                            DropdownMenuItem(
                                text = { Text(level) },
                                onClick = {
                                    onLogLevelChange(level)
                                    logLevelExpanded = false
                                },
                            )
                        }
                    }
                }
            }
            item {
                SettingsSection(title = stringResource(Res.string.developer_tools_session)) {
                    if (userId != null) {
                        ClickableInfoRow(
                            title = stringResource(Res.string.user_id),
                            value = userId,
                            onClick = { onCopy(userId, true) },
                        )
                    }
                    if (deviceId != null) {
                        ClickableInfoRow(
                            title = stringResource(Res.string.developer_tools_device_id),
                            value = deviceId,
                            onClick = { onCopy(deviceId, true) },
                        )
                    }
                    if (homeserver != null) {
                        ClickableInfoRow(
                            title = stringResource(Res.string.homeserver),
                            value = homeserver,
                            onClick = { onCopy(homeserver, false) },
                        )
                    }
                }
            }
            item {
                SettingsSection(title = stringResource(Res.string.developer_tools_build)) {
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.about_version, version)) },
                        supportingContent = { Text(stringResource(Res.string.developer_tools_platform, platform)) },
                    )
                    ClickableInfoRow(
                        title = stringResource(Res.string.developer_tools_git_branch),
                        value = gitBranch,
                        onClick = { onCopy(gitBranch, false) },
                    )
                    ClickableInfoRow(
                        title = stringResource(Res.string.developer_tools_git_sha),
                        value = gitSha,
                        onClick = { onCopy(gitSha, false) },
                    )
                }
            }
            item {
                SettingsSection(title = stringResource(Res.string.developer_tools_cache)) {
                    ClickableInfoRow(
                        title = stringResource(Res.string.developer_tools_media_cache),
                        value = cacheSize,
                        onClick = onOpenMediaCache,
                    )
                    ClickableInfoRow(
                        title = stringResource(Res.string.developer_tools_clear_cache),
                        value = null,
                        onClick = onClearCache,
                    )
                    ClickableInfoRow(
                        title = stringResource(Res.string.developer_tools_vacuum),
                        value = null,
                        onClick = onOptimizeStores,
                    )
                }
            }
            item {
                SettingsSection(title = stringResource(Res.string.developer_tools_push_rules)) {
                    pushRules.forEach { rule ->
                        ListItem(
                            headlineContent = { Text(rule.ruleId) },
                            supportingContent = {
                                Text("${rule.kind.name} · ${rule.actions.joinToString(", ")}")
                            },
                            trailingContent = {
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { enabled ->
                                        onTogglePushRule(rule.kind, rule.ruleId, enabled)
                                    },
                                )
                            },
                        )
                    }
                }
            }
            item {
                SettingsSection(title = stringResource(Res.string.developer_tools_push_history)) {
                    if (pushHistory.isEmpty()) {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(Res.string.developer_tools_no_push_history))
                            },
                        )
                    } else {
                        pushHistory.take(20).forEach { entry ->
                            ClickableInfoRow(
                                title = "${entry.platform} · ${entry.summary}",
                                value = entry.raw.take(160),
                                onClick = { onCopy(entry.raw, true) },
                            )
                        }
                    }
                }
            }
            item {
                SettingsSection(title = stringResource(Res.string.developer_tools_crash)) {
                    ClickableInfoRow(
                        title = stringResource(Res.string.developer_tools_crash),
                        value = null,
                        onClick = onCrash,
                    )
                }
            }
        }
    }
}
