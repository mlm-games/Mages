package org.mlm.mages.ui.components.sheets

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

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
                stringResource(Res.string.edit_room_addresses),
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(Spacing.lg))

            OutlinedTextField(
                value = canonical,
                onValueChange = { canonical = it },
                label = { Text(stringResource(Res.string.primary_address_optional)) },
                placeholder = { Text("#room:server.org") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(Spacing.md))

            Text(
                stringResource(Res.string.alternative_addresses),
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
                    label = { Text(stringResource(Res.string.add_alias)) },
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
                    Icon(painterResource(Res.drawable.ic_add), stringResource(Res.string.add_alias))
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
                                Icon(painterResource(Res.drawable.ic_delete), stringResource(Res.string.remove_alias))
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
                    Text(stringResource(Res.string.cancel))
                }
                Spacer(Modifier.width(Spacing.sm))
                Button(
                    onClick = {
                        val finalCanonical = canonical.ifBlank { null }
                        onUpdate(finalCanonical, aliases)
                    }
                ) {
                    Text(stringResource(Res.string.save))
                }
            }

            Spacer(Modifier.height(Spacing.md))
        }
    }
}
