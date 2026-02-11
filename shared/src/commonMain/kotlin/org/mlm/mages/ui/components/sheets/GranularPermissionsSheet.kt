package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.matrix.RoomPowerLevelChanges
import org.mlm.mages.matrix.RoomPowerLevels
import org.mlm.mages.ui.theme.Spacing

@Composable
fun GranularPermissionsSheet(
    powerLevels: RoomPowerLevels?,
    myPowerLevel: Long,
    onUpdatePowerLevels: (RoomPowerLevelChanges) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("General", "Messages", "Room Settings", "Advanced")

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
        ) {
            Text(
                "Room Permissions",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(Spacing.md))

            // Tab selector
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))

            // Content based on selected tab
            when (selectedTab) {
                0 -> GeneralPermissionsTab(powerLevels, myPowerLevel, onUpdatePowerLevels)
                1 -> MessagePermissionsTab(powerLevels, myPowerLevel, onUpdatePowerLevels)
                2 -> RoomSettingsTab(powerLevels, myPowerLevel, onUpdatePowerLevels)
                3 -> AdvancedPermissionsTab(powerLevels, myPowerLevel, onUpdatePowerLevels)
            }

            Spacer(Modifier.height(Spacing.lg))

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Done")
            }

            Spacer(Modifier.height(Spacing.md))
        }
    }
}

@Composable
private fun GeneralPermissionsTab(
    powerLevels: RoomPowerLevels?,
    myPowerLevel: Long,
    onUpdatePowerLevels: (RoomPowerLevelChanges) -> Unit
) {
    val canEdit = myPowerLevel >= (powerLevels?.stateDefault ?: 50)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item {
            PermissionSliderRow(
                label = "Ban users",
                icon = Icons.Default.Block,
                value = powerLevels?.ban ?: 50,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(ban = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = "Kick users",
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                value = powerLevels?.kick ?: 50,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(kick = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = "Invite users",
                icon = Icons.Default.PersonAdd,
                value = powerLevels?.invite ?: 0,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(invite = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = "Redact messages (others)",
                icon = Icons.Default.Delete,
                value = powerLevels?.redact ?: 50,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(redact = it)) }
            )
        }
    }
}

@Composable
private fun MessagePermissionsTab(
    powerLevels: RoomPowerLevels?,
    myPowerLevel: Long,
    onUpdatePowerLevels: (RoomPowerLevelChanges) -> Unit
) {
    val canEdit = myPowerLevel >= (powerLevels?.stateDefault ?: 50)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item {
            PermissionSliderRow(
                label = "Send messages (default)",
                icon = Icons.AutoMirrored.Filled.Message,
                value = powerLevels?.eventsDefault ?: 0,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(eventsDefault = it)) }
            )
        }
    }
}

@Composable
private fun RoomSettingsTab(
    powerLevels: RoomPowerLevels?,
    myPowerLevel: Long,
    onUpdatePowerLevels: (RoomPowerLevelChanges) -> Unit
) {
    val canEdit = myPowerLevel >= (powerLevels?.stateDefault ?: 50)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item {
            PermissionSliderRow(
                label = "Change room name",
                icon = Icons.Default.Edit,
                value = powerLevels?.roomName ?: 50,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(roomName = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = "Change room avatar",
                icon = Icons.Default.Image,
                value = powerLevels?.roomAvatar ?: 50,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(roomAvatar = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = "Change topic",
                icon = Icons.AutoMirrored.Filled.Subject,
                value = powerLevels?.roomTopic ?: 50,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(roomTopic = it)) }
            )
        }
    }
}

@Composable
private fun AdvancedPermissionsTab(
    powerLevels: RoomPowerLevels?,
    myPowerLevel: Long,
    onUpdatePowerLevels: (RoomPowerLevelChanges) -> Unit
) {
    val canEdit = myPowerLevel >= (powerLevels?.stateDefault ?: 50)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item {
            PermissionSliderRow(
                label = "Default user level",
                icon = Icons.Default.Person,
                value = powerLevels?.usersDefault ?: 0,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(usersDefault = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = "Default state event level",
                icon = Icons.Default.Settings,
                value = powerLevels?.stateDefault ?: 50,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(stateDefault = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = "Manage space children",
                icon = Icons.Default.AccountTree,
                value = powerLevels?.spaceChild ?: 100,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(spaceChild = it)) }
            )
        }
    }
}

@Composable
private fun PermissionSliderRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Long,
    canEdit: Boolean,
    onValueChange: (Long) -> Unit
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                RoleBadge(level = sliderValue.toLong())
            }

            Spacer(Modifier.height(Spacing.sm))

            if (canEdit) {
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onValueChange(sliderValue.toLong()) },
                    valueRange = 0f..100f,
                    steps = 99
                )
            } else {
                Text(
                    "You don't have permission to change this",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RoleBadge(level: Long) {
    val (text, color) = when {
        level >= 100 -> "Admin" to MaterialTheme.colorScheme.primary
        level >= 50 -> "Mod" to MaterialTheme.colorScheme.tertiary
        level > 0 -> "Custom" to MaterialTheme.colorScheme.secondary
        else -> "User" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            "$text ($level)",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
