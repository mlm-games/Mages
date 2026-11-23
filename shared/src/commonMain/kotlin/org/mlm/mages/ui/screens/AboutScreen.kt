package org.mlm.mages.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import io.github.mlmgames.settings.core.platform.currentPlatform
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.GitInfo
import org.mlm.mages.platform.appVersion
import org.mlm.mages.ui.components.settings.SettingsSection
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.MultipleTapToUnlock

private const val DEVELOPER_TOOLS_TAP_COUNT = 7
private const val SOURCE_URL = "https://github.com/mlm-games/Mages"
private const val ISSUES_URL = "https://github.com/mlm-games/Mages/issues"

@Composable
fun AboutRoute(
    onBack: () -> Unit,
    onOpenDeveloperTools: () -> Unit,
) {
    val version = remember { appVersion() }
    AboutScreen(
        version = version,
        platform = currentPlatform.name,
        gitSha = GitInfo.SHA,
        onBack = onBack,
        onOpenDeveloperTools = onOpenDeveloperTools,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    version: String,
    platform: String,
    gitSha: String,
    onBack: () -> Unit,
    onOpenDeveloperTools: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unlocker = remember { MultipleTapToUnlock(DEVELOPER_TOOLS_TAP_COUNT) }
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.setting_about), fontWeight = FontWeight.SemiBold) },
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
                SettingsSection(title = stringResource(Res.string.setting_about)) {
                    ListItem(
                        modifier = Modifier.clickable(enabled = !unlocked) {
                            if (unlocker.unlock(scope)) {
                                unlocked = true
                                onOpenDeveloperTools()
                            }
                        },
                        headlineContent = { Text(stringResource(Res.string.about_version, version)) },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.about_build_info)) },
                        supportingContent = { Text("$platform · ${gitSha.take(7)}") },
                    )
                }
            }
            item {
                SettingsSection(title = stringResource(Res.string.about_links)) {
                    ListItem(
                        modifier = Modifier.clickable { uriHandler.openUri(SOURCE_URL) },
                        headlineContent = { Text(stringResource(Res.string.about_source_code)) },
                        supportingContent = { Text(SOURCE_URL.removePrefix("https://")) },
                    )
                    ListItem(
                        modifier = Modifier.clickable { uriHandler.openUri(ISSUES_URL) },
                        headlineContent = { Text(stringResource(Res.string.about_report_bug)) },
                        supportingContent = { Text(ISSUES_URL.removePrefix("https://")) },
                    )
                }
            }
        }
    }
}
