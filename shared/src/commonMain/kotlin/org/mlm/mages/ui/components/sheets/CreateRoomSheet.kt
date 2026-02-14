package org.mlm.mages.ui.components.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.ui.theme.Spacing

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
            aliasCheckMessage = "Invalid characters in address"
            return@LaunchedEffect
        }

        aliasAvailability = AliasAvailability.Checking
        aliasCheckMessage = "Checking availability..."
        delay(400)

        scope.launch {
            val fullAlias = "#${roomAlias}:$serverDomain"
            val resolved = runCatching {
                matrixPort.resolveRoomId(fullAlias)
            }.getOrNull()

            if (resolved != null) {
                aliasAvailability = AliasAvailability.Taken
                aliasCheckMessage = "This address is already in use"
            } else {
                aliasAvailability = AliasAvailability.Available
                aliasCheckMessage = "This address is available"
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
            Text("New room", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = name.isBlank() && isPublic,
                supportingText = if (name.isBlank() && isPublic) {
                    { Text("Room name is required for public rooms") }
                } else null
            )

            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it },
                label = { Text("Topic (optional)") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(checked = isPublic, onCheckedChange = { isPublic = it })
                Text("Make room public (visible in room directory)")
            }

            AnimatedVisibility(visible = isPublic) {
                Column {
                    OutlinedTextField(
                        value = roomAlias,
                        onValueChange = { roomAlias = slugifyInput(it) },
                        label = { Text("Room address") },
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
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                                AliasAvailability.Available -> {
                                    Icon(Icons.Default.Check, contentDescription = "Available", tint = MaterialTheme.colorScheme.primary)
                                }
                                AliasAvailability.Taken, AliasAvailability.Invalid -> {
                                    Icon(Icons.Default.Warning, contentDescription = "Error", tint = MaterialTheme.colorScheme.error)
                                }
                                else -> {}
                            }
                        }
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
                    label = { Text("@user:server (optional)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        val v = inviteeInput.trim()
                        if (isValidMxid(v) && v !in invitees) {
                            invitees = invitees + v
                            inviteeInput = ""
                        }
                    },
                    enabled = isValidMxid(inviteeInput.trim())
                ) {
                    Icon(Icons.Default.Add, "Add")
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
                                    Icon(Icons.Default.Close, "Remove", Modifier.size(14.dp))
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

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !isCreating) {
                    Text("Cancel")
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
                                errorMessage = e.message ?: "Failed to create room"
                                isCreating = false
                            }
                        }
                    },
                    enabled = canCreate
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Create")
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}

private fun isValidMxid(s: String) = s.startsWith("@") && ":" in s && s.length > 3

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
