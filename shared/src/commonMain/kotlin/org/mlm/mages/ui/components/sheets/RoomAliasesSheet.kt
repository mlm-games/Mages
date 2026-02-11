package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.theme.Spacing

@Composable
fun RoomAliasesSheet(
    canonicalAlias: String?,
    altAliases: List<String>,
    onUpdate: (String?, List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var canonical by remember { mutableStateOf(canonicalAlias ?: "") }
    var newAlias by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf(altAliases.toMutableList()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
        ) {
            Text(
                "Edit Room Addresses",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(Spacing.lg))

            OutlinedTextField(
                value = canonical,
                onValueChange = { canonical = it },
                label = { Text("Primary address (optional)") },
                placeholder = { Text("#room:server.org") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(Spacing.md))

            Text(
                "Alternative addresses",
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(Modifier.height(Spacing.sm))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                OutlinedTextField(
                    value = newAlias,
                    onValueChange = { newAlias = it },
                    label = { Text("Add alias") },
                    placeholder = { Text("#alias:server.org") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        if (newAlias.isNotBlank() && !aliases.contains(newAlias)) {
                            aliases = (aliases + newAlias).toMutableList()
                            newAlias = ""
                        }
                    },
                    enabled = newAlias.isNotBlank()
                ) {
                    Icon(Icons.Default.Add, "Add alias")
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(aliases) { alias ->
                    ElevatedCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.md),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                alias,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    aliases = aliases.filter { it != alias }.toMutableList()
                                }
                            ) {
                                Icon(Icons.Default.Delete, "Remove alias")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(Spacing.sm))
                Button(
                    onClick = {
                        val finalCanonical = canonical.ifBlank { null }
                        onUpdate(finalCanonical, aliases)
                    }
                ) {
                    Text("Save")
                }
            }

            Spacer(Modifier.height(Spacing.md))
        }
    }
}
