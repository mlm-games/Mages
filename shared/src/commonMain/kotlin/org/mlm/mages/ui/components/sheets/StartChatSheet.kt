package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mlm.mages.matrix.DirectoryUser
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartChatSheet(
    matrixPort: MatrixPort,
    onDismiss: () -> Unit,
    onCreateRoom: () -> Unit,
    onOpenDirectory: () -> Unit,
    onDmCreated: (roomId: String, displayName: String?) -> Unit,
    onJoinByAddress: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<DirectoryUser>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var dmInProgress by remember { mutableStateOf<String?>(null) } // userId being started
    var joinAddress by remember { mutableStateOf("") }
    var showJoinField by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val term = query.trim()
        if (term.isBlank() || term.length < 2) {
            searchResults = emptyList()
            searchError = null
            isSearching = false
            return@LaunchedEffect
        }
        delay(300)
        if (query.trim() != term) return@LaunchedEffect

        isSearching = true
        searchError = null
        try {
            val results = mutableListOf<DirectoryUser>()
            val searched = runCatching { matrixPort.searchUsers(term, 20) }.getOrDefault(emptyList())
            results.addAll(searched)

            if (term.startsWith("@") && term.contains(":") && results.none { it.userId == term }) {
                runCatching { matrixPort.getUserProfile(term) }.getOrNull()?.let { profile ->
                    results.add(0, profile)
                }
            }
            searchResults = results.distinctBy { it.userId }
        } catch (e: Exception) {
            searchError = e.message ?: "Search failed"
            searchResults = emptyList()
        } finally {
            isSearching = false
        }
    }

    val isSearchActive = query.isNotBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = Spacing.lg)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                "Start chat",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = Spacing.sm)
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search for someone… e.g. @user:server") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )

            if (isSearching) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            searchError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isSearchActive) {
                    if (searchResults.isEmpty() && !isSearching) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            Icon(Icons.Default.Person, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("No users found", style = MaterialTheme.typography.titleMedium)
                            Text("Try a different term or enter full @user:server", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            contentPadding = PaddingValues(vertical = Spacing.sm)
                        ) {
                            items(searchResults, key = { it.userId }) { user ->
                                val isLoading = dmInProgress == user.userId
                                ListItem(
                                    headlineContent = { Text(user.displayName ?: user.userId) },
                                    supportingContent = {
                                        if (!user.displayName.isNullOrBlank() && user.displayName != user.userId) {
                                            Text(user.userId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    leadingContent = {
                                        Avatar(name = user.displayName ?: user.userId, avatarPath = user.avatarUrl, size = Sizes.avatarSmall)
                                    },
                                    trailingContent = {
                                        if (isLoading) {
                                            CircularWavyProgressIndicator(modifier = Modifier.size(20.dp))
                                        } else {
                                            Icon(Icons.AutoMirrored.Filled.Chat, null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    },
                                    modifier = Modifier.clickable(enabled = dmInProgress == null) {
                                        dmInProgress = user.userId
                                        scope.launch {
                                            try {
                                                val roomId = matrixPort.ensureDm(user.userId)
                                                if (roomId != null) {
                                                    onDmCreated(roomId, user.displayName ?: user.userId)
                                                } else {
                                                    searchError = "Failed to start conversation"
                                                }
                                            } catch (e: Exception) {
                                                searchError = e.message ?: "Failed to start conversation"
                                            } finally {
                                                dmInProgress = null
                                            }
                                        }
                                    }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        item {
                            ActionButton(
                                icon = Icons.Default.Add,
                                label = "New room",
                                description = "Create a new room",
                                onClick = {
                                    onDismiss()
                                    onCreateRoom()
                                }
                            )
                        }
                        item {
                            ActionButton(
                                icon = Icons.Default.Search,
                                label = "Room directory",
                                description = "Browse public rooms",
                                onClick = {
                                    onDismiss()
                                    onOpenDirectory()
                                }
                            )
                        }
                        item {
                            ActionButton(
                                icon = Icons.Default.Link,
                                label = "Join room by address",
                                description = "e.g. #room-name:matrix.org",
                                onClick = { showJoinField = !showJoinField }
                            )
                            if (showJoinField) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = Spacing.md, bottom = Spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                                ) {
                                    OutlinedTextField(
                                        value = joinAddress,
                                        onValueChange = { joinAddress = it },
                                        placeholder = { Text("#alias:server or !roomId:server") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        isError = joinAddress.isNotBlank() && !looksLikeRoomIdOrAlias(joinAddress)
                                    )
                                    Button(
                                        onClick = {
                                            val target = joinAddress.trim()
                                            if (looksLikeRoomIdOrAlias(target)) {
                                                onJoinByAddress(target)
                                                onDismiss()
                                            }
                                        },
                                        enabled = looksLikeRoomIdOrAlias(joinAddress.trim())
                                    ) { Text("Join") }
                                }
                            }
                        }
                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))
                        }
                        item {
                            Text(
                                "Tip: Search above to start a direct message. Enter a full Matrix ID like @alice:matrix.org for exact lookup.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.sm))
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

private fun looksLikeRoomIdOrAlias(value: String): Boolean {
    val trimmed = value.trim()
    return (trimmed.startsWith("#") || trimmed.startsWith("!")) && trimmed.contains(":")
}
