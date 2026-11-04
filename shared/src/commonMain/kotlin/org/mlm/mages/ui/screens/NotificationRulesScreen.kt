package org.mlm.mages.ui.screens

import org.jetbrains.compose.resources.painterResource
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

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
    var keywords by remember { mutableStateOf<List<String>>(emptyList()) }
    var showAddKeyword by remember { mutableStateOf(false) }
    var keywordInput by remember { mutableStateOf("") }
    var keywordAddFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            toggles = repo.readAll()
            defaultDmUnencrypted = repo.getDefaultRoomNotificationMode(false, true).getOrNull()
            defaultDmEncrypted = repo.getDefaultRoomNotificationMode(true, true).getOrNull()
            defaultGroupUnencrypted = repo.getDefaultRoomNotificationMode(false, false).getOrNull()
            defaultGroupEncrypted = repo.getDefaultRoomNotificationMode(true, false).getOrNull()
            keywords = matrixPort.getNotificationKeywords()
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.setting_notification_rules)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = stringResource(Res.string.back))
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
                        text = stringResource(Res.string.server_backed_rules),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                items(NotificationToggles.all) { toggle ->
                    val checked = toggles[toggle.id] ?: toggle.defaultUiValue
                    SwitchRow(
                        title = stringResource(toggle.label),
                        subtitle = stringResource(toggle.description),
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
                        text = stringResource(Res.string.default_room_behavior),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                item {
                    NotificationModeDropdown(
                        title = stringResource(Res.string.default_for_dms_unencrypted),
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
                        title = stringResource(Res.string.default_for_dms_encrypted),
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
                        title = stringResource(Res.string.default_for_groups_unencrypted),
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
                        title = stringResource(Res.string.default_for_groups_encrypted),
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

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(Res.string.notification_keywords),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Text(
                        text = stringResource(Res.string.notification_keywords_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (keywords.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(Res.string.notification_keywords_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                } else {
                    items(keywords) { keyword ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = keyword,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    val previous = keywords
                                    keywords = keywords - keyword
                                    scope.launch {
                                        val result = matrixPort.removeNotificationKeyword(keyword)
                                        if (result.isFailure) {
                                            keywords = previous
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    painterResource(Res.drawable.ic_delete),
                                    contentDescription = stringResource(Res.string.remove),
                                )
                            }
                        }
                    }
                }

                item {
                    TextButton(
                        onClick = {
                            keywordInput = ""
                            showAddKeyword = true
                        },
                    ) {
                        Text(stringResource(Res.string.notification_keywords_add))
                    }
                }
            }
        }
    }

    if (showAddKeyword) {
        AlertDialog(
            onDismissRequest = { showAddKeyword = false },
            title = { Text(stringResource(Res.string.notification_keywords_add)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = keywordInput,
                        onValueChange = { keywordInput = it },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.notification_keywords_hint)) },
                    )
                    if (keywordAddFailed) {
                        Text(
                            text = stringResource(Res.string.error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val keyword = keywordInput.trim()
                        if (keyword.isEmpty()) return@TextButton
                        scope.launch {
                            val result = matrixPort.addNotificationKeyword(keyword)
                            if (result.isSuccess) {
                                if (keyword !in keywords) keywords = keywords + keyword
                                showAddKeyword = false
                                keywordAddFailed = false
                            } else {
                                keywordAddFailed = true
                            }
                        }
                    },
                ) {
                    Text(stringResource(Res.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddKeyword = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
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
                    text = stringResource(Res.string.failed_to_load),
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
                Text(if (hasError) stringResource(Res.string.error) else currentValue.displayName())
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.displayName()) },
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
