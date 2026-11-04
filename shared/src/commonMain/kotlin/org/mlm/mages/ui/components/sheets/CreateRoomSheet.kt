package org.mlm.mages.ui.components.sheets

import org.jetbrains.compose.resources.painterResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.isMatrixUserId
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

enum class AliasAvailability {
    Unknown,
    Checking,
    Available,
    Taken,
    Invalid
}

@Composable
fun CreateRoomSheet(
    matrixPort: MatrixPort,
    onCreate: (name: String?, topic: String?, invitees: List<String>, isPublic: Boolean, roomAlias: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var inviteeInput by remember { mutableStateOf("") }
    var invitees by remember { mutableStateOf(listOf<String>()) }
    var isPublic by remember { mutableStateOf(false) }
    var roomAlias by remember { mutableStateOf("") }
    var aliasAvailability by remember { mutableStateOf(AliasAvailability.Unknown) }
    var aliasCheckMessage by remember { mutableStateOf<String?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val serverDomain = remember {
        matrixPort.whoami()?.substringAfter(":") ?: ""
    }

    // Auto-generates alias from name when public is checked and alias is empty
    LaunchedEffect(name, isPublic) {
        if (isPublic && roomAlias.isEmpty() && name.isNotBlank()) {
            roomAlias = slugify(name)
        }
    }

    LaunchedEffect(roomAlias, serverDomain, isPublic) {
        if (roomAlias.isBlank() || serverDomain.isBlank()) {
            aliasAvailability = AliasAvailability.Unknown
            aliasCheckMessage = null
            return@LaunchedEffect
        }

        if (!isValidAlias(roomAlias)) {
            aliasAvailability = AliasAvailability.Invalid
            aliasCheckMessage = getString(Res.string.invalid_characters_in_address)
            return@LaunchedEffect
        }

        aliasAvailability = AliasAvailability.Checking
        aliasCheckMessage = getString(Res.string.checking_availability)
        delay(400)

        scope.launch {
            val fullAlias = "#${roomAlias}:$serverDomain"
            val resolved = runCatching {
                matrixPort.resolveRoomId(fullAlias)
            }.getOrNull()

            if (resolved != null) {
                aliasAvailability = AliasAvailability.Taken
                aliasCheckMessage = getString(Res.string.this_address_is_already_in_use)
            } else {
                aliasAvailability = AliasAvailability.Available
                aliasCheckMessage = getString(Res.string.this_address_is_available)
            }
        }
    }

    LaunchedEffect(isPublic) {
        if (!isPublic) {
            roomAlias = ""
            aliasAvailability = AliasAvailability.Unknown
            aliasCheckMessage = null
        }
    }

    val canCreate = remember(name, isPublic, roomAlias, aliasAvailability, isCreating) {
        !isCreating &&
        (name.isNotBlank() || isPublic) && // Need at least name or public room with alias
        (!isPublic || (roomAlias.isNotBlank() && aliasAvailability == AliasAvailability.Available))
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // Shrinks with the IME so the fields stay reachable and the action row stays on screen.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Text(stringResource(Res.string.new_room_action), style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = name.isBlank() && isPublic,
                    supportingText = if (name.isBlank() && isPublic) {
                        { Text(stringResource(Res.string.room_name_is_required_for_public_rooms)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    )
                )

                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text(stringResource(Res.string.topic_optional)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = isPublic, onCheckedChange = { isPublic = it })
                    Text(stringResource(Res.string.make_room_public_visible_in_room_directory))
                }

                AnimatedVisibility(visible = isPublic) {
                    Column {
                        OutlinedTextField(
                            value = roomAlias,
                            onValueChange = { roomAlias = slugifyInput(it) },
                            label = { Text(stringResource(Res.string.room_address)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            isError = aliasAvailability == AliasAvailability.Taken || aliasAvailability == AliasAvailability.Invalid,
                            prefix = { Text("#", style = MaterialTheme.typography.bodyLarge) },
                            suffix = { Text(":$serverDomain", style = MaterialTheme.typography.bodyMedium) },
                            supportingText = {
                                aliasCheckMessage?.let { message ->
                                    Text(
                                        text = message,
                                        color = when (aliasAvailability) {
                                            AliasAvailability.Available -> MaterialTheme.colorScheme.primary
                                            AliasAvailability.Taken, AliasAvailability.Invalid -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            },
                            trailingIcon = {
                                when (aliasAvailability) {
                                    AliasAvailability.Checking -> {
                                        CircularWavyProgressIndicator(modifier = Modifier.size(20.dp))
                                    }
                                    AliasAvailability.Available -> {
                                        Icon(painterResource(Res.drawable.ic_check), contentDescription = stringResource(Res.string.available), tint = MaterialTheme.colorScheme.primary)
                                    }
                                    AliasAvailability.Taken, AliasAvailability.Invalid -> {
                                        Icon(painterResource(Res.drawable.ic_warning), contentDescription = stringResource(Res.string.error), tint = MaterialTheme.colorScheme.error)
                                    }
                                    else -> {}
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            )
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = inviteeInput,
                        onValueChange = { inviteeInput = it },
                        label = { Text(stringResource(Res.string.at_user_server_optional)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        )
                    )
                    IconButton(
                        onClick = {
                            val v = inviteeInput.trim()
                            if (isMatrixUserId(v) && v !in invitees) {
                                invitees = invitees + v
                                inviteeInput = ""
                            }
                        },
                        enabled = isMatrixUserId(inviteeInput)
                    ) {
                        Icon(painterResource(Res.drawable.ic_add), stringResource(Res.string.add))
                    }
                }

                if (invitees.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        invitees.forEach { mxid ->
                            InputChip(
                                selected = false,
                                onClick = {},
                                label = { Text(mxid) },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { invitees = invitees - mxid },
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Icon(painterResource(Res.drawable.ic_close), stringResource(Res.string.remove), Modifier.size(14.dp))
                                    }
                                }
                            )
                        }
                    }
                }

                errorMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !isCreating) {
                    Text(stringResource(Res.string.cancel))
                }
                Spacer(Modifier.width(Spacing.sm))
                Button(
                    onClick = {
                        isCreating = true
                        errorMessage = null
                        scope.launch {
                            try {
                                onCreate(
                                    name.ifBlank { null },
                                    topic.ifBlank { null },
                                    invitees,
                                    isPublic,
                                    roomAlias.ifBlank { null }
                                )
                            } catch (e: Exception) {
                                errorMessage = e.message ?: getString(Res.string.failed_to_create_room)
                                isCreating = false
                            }
                        }
                    },
                    enabled = canCreate
                ) {
                    if (isCreating) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(Res.string.create))
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}


private fun isValidAlias(alias: String): Boolean {
    // Room aliases should only contain lowercase letters, numbers, hyphens, and underscores
    return alias.isNotBlank() && alias.matches(Regex("^[a-z0-9_-]+$"))
}

private fun slugifyInput(input: String): String {
    // Allow user to type, but sanitize for the actual value
    return input.lowercase()
        .replace(Regex("[^a-z0-9_-]"), "-")
        .replace(Regex("-+$"), "") // Remove trailing hyphens
}

private fun slugify(input: String): String {
    return input.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9\\s-]"), "") // Remove special chars except spaces and hyphens
        .replace(Regex("\\s+"), "-")          // Replace spaces with hyphens
        .replace(Regex("-+"), "-")             // Collapse multiple hyphens
        .trim('-')
}
