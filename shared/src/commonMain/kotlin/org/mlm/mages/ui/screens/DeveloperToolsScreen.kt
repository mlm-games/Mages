package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import org.mlm.mages.platform.rememberClipboardCopy
import org.mlm.mages.platform.appVersion
import org.mlm.mages.push.PushHistoryEntry
import org.mlm.mages.push.PushHistoryStore
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.components.settings.SettingSwitchRow
import org.mlm.mages.ui.components.settings.SettingsSection
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.crashApp
import org.mlm.mages.ui.util.formatBytes

private val logLevels = listOf("Verbose", "Debug", "Info", "Warn", "Error", "Assert")

private data class CopyTarget(val title: String, val value: String, val sensitive: Boolean = false)
private data class ActionTarget(val title: String, val value: String? = null, val onClick: () -> Unit)

private fun LazyListScope.devSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    item { SettingsSection(title = title, content = content) }
}

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
private fun InfoRow(title: String, value: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
    )
}

@Composable
private fun CopyTargetRow(target: CopyTarget, onCopy: (String, Boolean) -> Unit) {
    ClickableInfoRow(
        title = target.title,
        value = target.value,
        onClick = { onCopy(target.value, target.sensitive) },
    )
}

@Composable
private fun LogLevelRow(
    logLevel: String,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onChange: (String) -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(Res.string.developer_tools_log_level)) },
        supportingContent = { Text(logLevel) },
        trailingContent = {
            TextButton(onClick = onExpand) {
                Text(stringResource(Res.string.developer_tools_change))
            }
        },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        logLevels.forEach { level ->
            DropdownMenuItem(
                text = { Text(level) },
                onClick = {
                    onChange(level)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun PushRuleRow(
    rule: PushRuleInfo,
    onToggle: (PushRuleKind, String, Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(rule.ruleId) },
        supportingContent = { Text("${rule.kind.name} · ${rule.actions.joinToString(", ")}") },
        trailingContent = {
            Switch(
                checked = rule.enabled,
                onCheckedChange = { enabled -> onToggle(rule.kind, rule.ruleId, enabled) },
            )
        },
    )
}

@Composable
private fun PushHistoryRow(
    entry: PushHistoryEntry,
    onCopy: (String, Boolean) -> Unit,
) {
    ClickableInfoRow(
        title = "${entry.platform} · ${entry.summary}",
        value = entry.raw.take(160),
        onClick = { onCopy(entry.raw, true) },
    )
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

    val sessionTargets = listOfNotNull(
        userId?.let { CopyTarget(stringResource(Res.string.user_id), it, sensitive = true) },
        deviceId?.let { CopyTarget(stringResource(Res.string.developer_tools_device_id), it, sensitive = true) },
        homeserver?.let { CopyTarget(stringResource(Res.string.homeserver), it) },
    )
    val buildTargets = listOf(
        CopyTarget(stringResource(Res.string.developer_tools_git_branch), gitBranch),
        CopyTarget(stringResource(Res.string.developer_tools_git_sha), gitSha),
    )
    val cacheActions = listOf(
        ActionTarget(stringResource(Res.string.developer_tools_media_cache), cacheSize, onOpenMediaCache),
        ActionTarget(stringResource(Res.string.developer_tools_clear_cache), onClick = onClearCache),
        ActionTarget(stringResource(Res.string.developer_tools_vacuum), onClick = onOptimizeStores),
    )
    val preferencesTitle = stringResource(Res.string.developer_tools_preferences)
    val sessionTitle = stringResource(Res.string.developer_tools_session)
    val buildTitle = stringResource(Res.string.developer_tools_build)
    val cacheTitle = stringResource(Res.string.developer_tools_cache)
    val pushRulesTitle = stringResource(Res.string.developer_tools_push_rules)
    val pushHistoryTitle = stringResource(Res.string.developer_tools_push_history)
    val crashTitle = stringResource(Res.string.developer_tools_crash)

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
            devSection(preferencesTitle) {
                SettingSwitchRow(
                    icon = painterResource(Res.drawable.ic_admin_panel_settings),
                    title = stringResource(Res.string.setting_developer_mode),
                    subtitle = stringResource(Res.string.setting_developer_mode_description),
                    checked = developerMode,
                    onCheckedChange = onDeveloperModeChange,
                )
                LogLevelRow(
                    logLevel = logLevel,
                    expanded = logLevelExpanded,
                    onExpand = { logLevelExpanded = true },
                    onDismiss = { logLevelExpanded = false },
                    onChange = onLogLevelChange,
                )
            }
            devSection(sessionTitle) {
                sessionTargets.forEach { CopyTargetRow(it, onCopy) }
            }
            devSection(buildTitle) {
                InfoRow(
                    title = stringResource(Res.string.about_version, version),
                    value = stringResource(Res.string.developer_tools_platform, platform),
                )
                buildTargets.forEach { CopyTargetRow(it, onCopy) }
            }
            devSection(cacheTitle) {
                cacheActions.forEach { target ->
                    ClickableInfoRow(target.title, target.value, target.onClick)
                }
            }
            devSection(pushRulesTitle) {
                pushRules.forEach { rule -> PushRuleRow(rule, onTogglePushRule) }
            }
            devSection(pushHistoryTitle) {
                if (pushHistory.isEmpty()) {
                    ListItem(headlineContent = {
                        Text(stringResource(Res.string.developer_tools_no_push_history))
                    })
                } else {
                    pushHistory.take(20).forEach { entry -> PushHistoryRow(entry, onCopy) }
                }
            }
            devSection(crashTitle) {
                ClickableInfoRow(stringResource(Res.string.developer_tools_crash), null, onCrash)
            }
        }
    }
}
