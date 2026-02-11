package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.RoomPowerLevels
import org.mlm.mages.ui.theme.Spacing

@Composable
fun PowerLevelsSheet(
    members: List<MemberSummary>,
    powerLevels: RoomPowerLevels?,
    myPowerLevel: Long,
    onUpdatePowerLevel: (String, Long) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMember by remember { mutableStateOf<MemberSummary?>(null) }
    var showRoleDialog by remember { mutableStateOf(false) }
    var showCustomLevelDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
        ) {
            Text(
                "Manage Permissions",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(Spacing.md))

            // Current defaults info
            powerLevels?.let { pl ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(Spacing.md)) {
                        Text(
                            "Default permissions",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "Ban: ${pl.ban}, Kick: ${pl.kick}, Redact: ${pl.redact}, Invite: ${pl.invite}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            Text(
                "Members",
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(Modifier.height(Spacing.sm))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(members) { member ->
                    val memberPowerLevel = powerLevels?.users?.get(member.userId) ?: 0L
                    val canEdit = myPowerLevel > memberPowerLevel && myPowerLevel >= 100

                    ElevatedCard(
                        onClick = {
                            if (canEdit) {
                                selectedMember = member
                                showRoleDialog = true
                            }
                        },
                        enabled = canEdit
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.md),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                Icon(
                                    imageVector = when {
                                        memberPowerLevel >= 100 -> Icons.Default.AdminPanelSettings
                                        memberPowerLevel >= 50 -> Icons.Default.Shield
                                        else -> Icons.Default.Person
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        memberPowerLevel >= 100 -> MaterialTheme.colorScheme.primary
                                        memberPowerLevel >= 50 -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Column {
                                    Text(
                                        member.displayName ?: member.userId,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        getRoleLabel(memberPowerLevel),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (canEdit) {
                                Text(
                                    "Tap to edit",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close")
            }

            Spacer(Modifier.height(Spacing.md))
        }
    }

    // Role selection dialog
    if (showRoleDialog && selectedMember != null) {
        val member = selectedMember!!
        val currentLevel = powerLevels?.users?.get(member.userId) ?: 0L

        AlertDialog(
            onDismissRequest = {
                showRoleDialog = false
                selectedMember = null
            },
            title = { Text("Change role for ${member.displayName ?: member.userId}") },
            text = {
                Column {
                    RoleOption(
                        label = "Admin (100)",
                        selected = currentLevel >= 100,
                        onClick = {
                            onUpdatePowerLevel(member.userId, 100)
                            showRoleDialog = false
                        }
                    )
                    RoleOption(
                        label = "Moderator (50)",
                        selected = currentLevel in 50..99,
                        onClick = {
                            onUpdatePowerLevel(member.userId, 50)
                            showRoleDialog = false
                        }
                    )
                    RoleOption(
                        label = "User (0)",
                        selected = currentLevel < 50,
                        onClick = {
                            onUpdatePowerLevel(member.userId, 0)
                            showRoleDialog = false
                        }
                    )
                    RoleOption(
                        label = "Custom",
                        selected = currentLevel > 0 && currentLevel != 50L && currentLevel != 100L,
                        onClick = {
                            showRoleDialog = false
                            showCustomLevelDialog = true
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showRoleDialog = false
                    selectedMember = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCustomLevelDialog && selectedMember != null) {
        val member = selectedMember!!
        var customLevel by remember {
            mutableStateOf((powerLevels?.users?.get(member.userId) ?: 0L).toFloat())
        }

        AlertDialog(
            onDismissRequest = {
                showCustomLevelDialog = false
                selectedMember = null
            },
            title = { Text("Set custom power level") },
            text = {
                Column {
                    Text(
                        "Enter a custom power level for ${member.displayName ?: member.userId}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        "Current: ${customLevel.toInt()}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Slider(
                        value = customLevel,
                        onValueChange = { customLevel = it },
                        valueRange = 0f..100f,
                        steps = 99
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "Level ${customLevel.toInt()}: ${getRoleLabel(customLevel.toLong())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdatePowerLevel(member.userId, customLevel.toLong())
                        showCustomLevelDialog = false
                        selectedMember = null
                    }
                ) {
                    Text("Set")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCustomLevelDialog = false
                    selectedMember = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun RoleOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = if (selected) {
            {
                Icon(
                    Icons.Default.AdminPanelSettings,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else null,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

private fun getRoleLabel(powerLevel: Long): String = when {
    powerLevel >= 100 -> "Admin"
    powerLevel >= 50 -> "Moderator"
    powerLevel > 0 -> "Custom ($powerLevel)"
    else -> "User"
}
