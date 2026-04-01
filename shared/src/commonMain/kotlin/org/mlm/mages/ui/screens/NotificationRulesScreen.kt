package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.matrix.displayName
import org.mlm.mages.notifications.NotificationSettingsRepository
import org.mlm.mages.notifications.NotificationToggles

@Composable
fun NotificationRulesScreen(
    matrixPort: MatrixPort,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = remember(matrixPort) { NotificationSettingsRepository(matrixPort) }
    val scope = rememberCoroutineScope()

    var toggles by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var defaultDmUnencrypted by remember { mutableStateOf<RoomNotificationMode?>(null) }
    var defaultDmEncrypted by remember { mutableStateOf<RoomNotificationMode?>(null) }
    var defaultGroupUnencrypted by remember { mutableStateOf<RoomNotificationMode?>(null) }
    var defaultGroupEncrypted by remember { mutableStateOf<RoomNotificationMode?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            toggles = repo.readAll()
            defaultDmUnencrypted = repo.getDefaultRoomNotificationMode(false, true).getOrNull()
            defaultDmEncrypted = repo.getDefaultRoomNotificationMode(true, true).getOrNull()
            defaultGroupUnencrypted = repo.getDefaultRoomNotificationMode(false, false).getOrNull()
            defaultGroupEncrypted = repo.getDefaultRoomNotificationMode(true, false).getOrNull()
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification rules") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = "Server-backed rules",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                items(NotificationToggles.all) { toggle ->
                    val checked = toggles[toggle.id] ?: toggle.defaultUiValue
                    SwitchRow(
                        title = toggle.label,
                        subtitle = toggle.description,
                        checked = checked,
                        onCheckedChange = { enabled ->
                            val previous = toggles[toggle.id] ?: toggle.defaultUiValue
                            toggles = toggles + (toggle.id to enabled)

                            scope.launch {
                                val result = repo.setToggleEnabled(toggle, enabled)
                                if (result.isFailure) {
                                    toggles = toggles + (toggle.id to previous)
                                }
                            }
                        },
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Default room behavior",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                item {
                    NotificationModeDropdown(
                        title = "Default for DMs (unencrypted)",
                        value = defaultDmUnencrypted,
                        onValueChange = { mode ->
                            val previous = defaultDmUnencrypted
                            defaultDmUnencrypted = mode
                            scope.launch {
                                val result = repo.setDefaultRoomNotificationMode(false, true, mode)
                                if (result.isFailure) {
                                    defaultDmUnencrypted = previous
                                }
                            }
                        },
                    )
                }

                item {
                    NotificationModeDropdown(
                        title = "Default for DMs (encrypted)",
                        value = defaultDmEncrypted,
                        onValueChange = { mode ->
                            val previous = defaultDmEncrypted
                            defaultDmEncrypted = mode
                            scope.launch {
                                val result = repo.setDefaultRoomNotificationMode(true, true, mode)
                                if (result.isFailure) {
                                    defaultDmEncrypted = previous
                                }
                            }
                        },
                    )
                }

                item {
                    NotificationModeDropdown(
                        title = "Default for groups (unencrypted)",
                        value = defaultGroupUnencrypted,
                        onValueChange = { mode ->
                            val previous = defaultGroupUnencrypted
                            defaultGroupUnencrypted = mode
                            scope.launch {
                                val result = repo.setDefaultRoomNotificationMode(false, false, mode)
                                if (result.isFailure) {
                                    defaultGroupUnencrypted = previous
                                }
                            }
                        },
                    )
                }

                item {
                    NotificationModeDropdown(
                        title = "Default for groups (encrypted)",
                        value = defaultGroupEncrypted,
                        onValueChange = { mode ->
                            val previous = defaultGroupEncrypted
                            defaultGroupEncrypted = mode
                            scope.launch {
                                val result = repo.setDefaultRoomNotificationMode(true, false, mode)
                                if (result.isFailure) {
                                    defaultGroupEncrypted = previous
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun NotificationModeDropdown(
    title: String,
    value: RoomNotificationMode?,
    onValueChange: (RoomNotificationMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        RoomNotificationMode.AllMessages,
        RoomNotificationMode.MentionsAndKeywordsOnly,
        RoomNotificationMode.Mute,
    )
    val hasError = value == null
    val currentValue = value ?: RoomNotificationMode.AllMessages

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (hasError) {
                Text(
                    text = "Failed to load",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Box {
            TextButton(
                onClick = { expanded = true },
                enabled = !hasError,
            ) {
                Text(if (hasError) "Error" else currentValue.displayName)
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.displayName) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
