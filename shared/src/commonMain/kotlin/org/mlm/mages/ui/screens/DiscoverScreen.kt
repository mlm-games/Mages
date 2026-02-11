package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mlm.mages.matrix.DirectoryUser
import org.mlm.mages.matrix.PublicRoom
import org.mlm.mages.ui.components.core.StatusBanner
import org.mlm.mages.ui.components.core.BannerType
import org.mlm.mages.ui.viewmodel.DiscoverUi
import org.mlm.mages.ui.viewmodel.DiscoverViewModel
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.*

@Composable
fun DiscoverRoute(
    viewModel: DiscoverViewModel,
    onClose: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DiscoverViewModel.Event.OpenRoom -> onClose()
                is DiscoverViewModel.Event.ShowError -> {
                    // Shown via banner
                }
            }
        }
    }

    DiscoverScreen(
        state = state,
        onQuery = viewModel::setQuery,
        onClose = onClose,
        onOpenUser = { u -> viewModel.openUser(u) },
        onOpenRoom = { r -> viewModel.openRoom(r) },
        onJoinByIdOrAlias = { idOrAlias -> viewModel.joinDirect(idOrAlias) },
        onSelectServer = viewModel::setDirectoryServer,
        onAddCustomServer = viewModel::addCustomServer,
        onLoadMore = viewModel::loadMoreRooms
    )
}

@Composable
fun DiscoverScreen(
    state: DiscoverUi,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    onOpenUser: (DirectoryUser) -> Unit,
    onOpenRoom: (PublicRoom) -> Unit,
    onJoinByIdOrAlias: (String) -> Unit,
    onSelectServer: (String) -> Unit,
    onAddCustomServer: (String) -> Unit,
    onLoadMore: () -> Unit
) {
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.discover)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.search_users_or_rooms)) },
                placeholder = { Text(stringResource(Res.string.search_placeholder)) },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(Res.string.clear))
                        }
                    }
                }
            )

            DirectoryServerSelector(
                currentServer = state.directoryServer,
                availableServers = state.availableServers,
                homeServer = state.homeServer,
                onSelectServer = onSelectServer,
                onAddCustomServer = onAddCustomServer
            )

            if (state.isBusy && !state.isPaging) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                // always show first if available
                state.directJoinCandidate?.let { target ->
                    item(key = "direct_join") {
                        DirectJoinCard(
                            target = target,
                            onJoin = { onJoinByIdOrAlias(target) },
                            isBusy = state.isBusy
                        )
                    }
                }

                if (state.users.isNotEmpty()) {
                    item(key = "users_header") {
                        Text(
                            stringResource(Res.string.users),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    items(state.users, key = { it.userId }) { user ->
                        UserListItem(
                            user = user,
                            onMessage = { onOpenUser(user) }
                        )
                    }
                }

                // Rooms section
                if (state.rooms.isNotEmpty()) {
                    item(key = "rooms_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(Res.string.public_rooms_on, state.directoryServer),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(Res.string.found, state.rooms.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(state.rooms, key = { it.roomId }) { room ->
                        RoomListItem(
                            room = room,
                            onJoin = { onOpenRoom(room) }
                        )
                    }

                    if (state.nextBatch != null) {
                        item(key = "load_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (state.isPaging) {
                                    LoadingIndicator(modifier = Modifier.size(24.dp))
                                } else {
                                    OutlinedButton(onClick = onLoadMore) {
                                        Icon(
                                            Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(Res.string.load_more_rooms))
                                    }
                                }
                            }
                        }
                    }
                }

                if (!state.isBusy &&
                    state.query.isNotBlank() &&
                    state.users.isEmpty() &&
                    state.rooms.isEmpty() &&
                    state.directJoinCandidate == null
                ) {
                    item(key = "empty") {
                        EmptySearchState(query = state.query)
                    }
                }

                if (state.query.isBlank()) {
                    item(key = "hint") {
                        SearchHintCard()
                    }
                }
            }

            StatusBanner(
                message = state.error,
                type = BannerType.ERROR
            )
        }
    }
}

@Composable
private fun DirectoryServerSelector(
    currentServer: String,
    availableServers: List<String>,
    homeServer: String,
    onSelectServer: (String) -> Unit,
    onAddCustomServer: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(Res.string.directory),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = currentServer,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                textStyle = MaterialTheme.typography.bodyMedium
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                availableServers.forEach { server ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(server)
                                if (server == homeServer) {
                                    Text(
                                        stringResource(Res.string.home),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        },
                        onClick = {
                            onSelectServer(server)
                            expanded = false
                        },
                        leadingIcon = {
                            if (server == currentServer) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                }

                HorizontalDivider()

                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.add_custom_server)) },
                    onClick = {
                        expanded = false
                        showCustomDialog = true
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                )
            }
        }
    }

    if (showCustomDialog) {
        CustomServerDialog(
            onDismiss = { showCustomDialog = false },
            onConfirm = { server ->
                onAddCustomServer(server)
                showCustomDialog = false
            }
        )
    }
}

@Composable
private fun CustomServerDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var serverInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.add_custom_server_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(Res.string.enter_homeserver_domain),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = serverInput,
                    onValueChange = { serverInput = it },
                    label = { Text(stringResource(Res.string.server_domain)) },
                    placeholder = { Text(stringResource(Res.string.server_domain_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(serverInput) },
                enabled = serverInput.isNotBlank()
            ) {
                Text(stringResource(Res.string.add_server))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}

@Composable
private fun DirectJoinCard(
    target: String,
    onJoin: () -> Unit,
    isBusy: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        ListItem(
            headlineContent = {
                Text(
                    target,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            supportingContent = {
                Text(stringResource(Res.string.join_this_room))
            },
            leadingContent = {
                Icon(
                    Icons.Default.MeetingRoom,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingContent = {
                Button(
                    onClick = onJoin,
                    enabled = !isBusy
                ) {
                    Text(stringResource(Res.string.join))
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}

@Composable
private fun UserListItem(
    user: DirectoryUser,
    onMessage: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            headlineContent = { Text(user.displayName ?: user.userId) },
            supportingContent = {
                if (!user.displayName.isNullOrBlank()) {
                    Text(user.userId)
                }
            },
            leadingContent = {
                Icon(Icons.Default.Person, contentDescription = null)
            },
            trailingContent = {
                TextButton(onClick = onMessage) {
                    Text(stringResource(Res.string.message))
                }
            }
        )
    }
}

@Composable
private fun RoomListItem(
    room: PublicRoom,
    onJoin: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            headlineContent = {
                Text(room.name ?: room.alias ?: room.roomId)
            },
            supportingContent = {
                Column {
                    room.alias?.let { alias ->
                        if (alias != room.name) {
                            Text(
                                alias,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    room.topic?.takeIf { it.isNotBlank() }?.let { topic ->
                        Text(
                            topic.take(100) + if (topic.length > 100) "..." else "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2
                        )
                    }
//                    room.numJoinedMembers?.let { members ->
//                        Text(
//                            "$members members",
//                            style = MaterialTheme.typography.labelSmall,
//                            color = MaterialTheme.colorScheme.onSurfaceVariant
//                        )
//                    }
                }
            },
            leadingContent = {
                Icon(Icons.Default.Group, contentDescription = null)
            },
            trailingContent = {
                TextButton(onClick = onJoin) {
                    Text(stringResource(Res.string.join))
                }
            }
        )
    }
}

@Composable
private fun EmptySearchState(query: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(Res.string.no_results_for, query),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(Res.string.try_different_search_term),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SearchHintCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(Res.string.search_tips),
                style = MaterialTheme.typography.titleMedium
            )

            SearchHintRow(
                icon = Icons.Default.Tag,
                example = "#linux:matrix.org",
                description = stringResource(Res.string.join_room_by_alias)
            )

            SearchHintRow(
                icon = Icons.Default.Person,
                example = "@username:server.com",
                description = stringResource(Res.string.start_dm_with_user)
            )

            SearchHintRow(
                icon = Icons.Default.Search,
                example = "programming",
                description = stringResource(Res.string.search_rooms_by_topic)
            )

            SearchHintRow(
                icon = Icons.Default.Link,
                example = "https://matrix.to/#/...",
                description = stringResource(Res.string.paste_matrix_link)
            )
        }
    }
}

@Composable
private fun SearchHintRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    example: String,
    description: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column {
            Text(
                example,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}