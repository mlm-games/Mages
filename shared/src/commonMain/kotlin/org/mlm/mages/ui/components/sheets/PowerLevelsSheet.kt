package org.mlm.mages.ui.components.sheets

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.RoomPowerLevels
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.Composable

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
                stringResource(Res.string.manage_permissions),
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
                            stringResource(Res.string.default_permissions),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            stringResource(Res.string.power_levels_summary, pl.ban, pl.kick, pl.redact, pl.invite),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            Text(
                stringResource(Res.string.members_room),
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
                                    painter = when {
                                        memberPowerLevel >= 100 -> painterResource(Res.drawable.ic_admin_panel_settings)
                                        memberPowerLevel >= 50 -> painterResource(Res.drawable.ic_shield)
                                        else -> painterResource(Res.drawable.ic_person)
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
                                    stringResource(Res.string.tap_to_edit),
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
                Text(stringResource(Res.string.close))
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
            title = { Text(stringResource(Res.string.change_role_for, member.displayName ?: member.userId)) },
            text = {
                Column {
                    RoleOption(
                        label = stringResource(Res.string.admin_100),
                        selected = currentLevel >= 100,
                        onClick = {
                            onUpdatePowerLevel(member.userId, 100)
                            showRoleDialog = false
                        }
                    )
                    RoleOption(
                        label = stringResource(Res.string.moderator_50),
                        selected = currentLevel in 50..99,
                        onClick = {
                            onUpdatePowerLevel(member.userId, 50)
                            showRoleDialog = false
                        }
                    )
                    RoleOption(
                        label = stringResource(Res.string.user_0),
                        selected = currentLevel < 50,
                        onClick = {
                            onUpdatePowerLevel(member.userId, 0)
                            showRoleDialog = false
                        }
                    )
                    RoleOption(
                        label = stringResource(Res.string.custom),
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
                    Text(stringResource(Res.string.cancel))
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
            title = { Text(stringResource(Res.string.set_custom_power_level)) },
            text = {
                Column {
                    Text(
                        stringResource(Res.string.custom_power_level_for, member.displayName ?: member.userId),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        stringResource(Res.string.current_power_level, customLevel.toInt()),
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
                        stringResource(Res.string.level_with_label, customLevel.toInt(), getRoleLabel(customLevel.toLong())),
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
                    Text(stringResource(Res.string.set))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCustomLevelDialog = false
                    selectedMember = null
                }) {
                    Text(stringResource(Res.string.cancel))
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
                    painterResource(Res.drawable.ic_admin_panel_settings),
                    contentDescription = stringResource(Res.string.selected),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else null,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun getRoleLabel(powerLevel: Long): String = when {
    powerLevel >= 100 -> stringResource(Res.string.admin)
    powerLevel >= 50 -> stringResource(Res.string.moderator)
    powerLevel > 0 -> stringResource(Res.string.role_custom_level, powerLevel)
    else -> stringResource(Res.string.user)
}
