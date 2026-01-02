package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.mlmgames.settings.ui.AutoSettingsScreen
import io.github.mlmgames.settings.ui.CategoryConfig
import io.github.mlmgames.settings.ui.LocalStringResourceProvider
import io.github.mlmgames.settings.ui.ProvideStringResources
import org.koin.compose.viewmodel.koinViewModel
import org.mlm.mages.settings.*
import org.mlm.mages.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        AutoSettingsScreen(
            schema = viewModel.schema,
            value = settings,
            onSet = { name, value -> viewModel.updateSetting(name, value) },
            onAction = { actionClass -> viewModel.executeAction(actionClass) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            categoryConfigs = listOf(
                CategoryConfig(Account::class, "Account"),
                CategoryConfig(Appearance::class, "Appearance"),
                CategoryConfig(Notifications::class, "Notifications"),
                CategoryConfig(Privacy::class, "Privacy"),
                CategoryConfig(Calls::class, "Calls"),
                CategoryConfig(Storage::class, "Storage"),
                CategoryConfig(Advanced::class, "Advanced"),
            ),
            snackbarHostState = snackbarHostState
        )
    }
}