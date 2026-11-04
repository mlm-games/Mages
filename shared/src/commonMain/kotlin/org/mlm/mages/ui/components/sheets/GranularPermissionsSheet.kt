package org.mlm.mages.ui.components.sheets

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.mlm.mages.matrix.RoomPowerLevelChanges
import org.mlm.mages.matrix.RoomPowerLevels
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

@Composable
fun GranularPermissionsSheet(
    powerLevels: RoomPowerLevels?,
    myPowerLevel: Long,
    onUpdatePowerLevels: (RoomPowerLevelChanges) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(stringResource(Res.string.general), stringResource(Res.string.messages), stringResource(Res.string.room_settings), stringResource(Res.string.advanced))

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
        ) {
            Text(
                stringResource(Res.string.room_permissions),
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
                Text(stringResource(Res.string.done))
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
                label = stringResource(Res.string.ban_users),
                icon = painterResource(Res.drawable.ic_block),
                value = powerLevels?.ban ?: 50,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(ban = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = stringResource(Res.string.kick_users),
                icon = painterResource(Res.drawable.ic_exit_to_app),
                value = powerLevels?.kick ?: 50,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(kick = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = stringResource(Res.string.invite_users),
                icon = painterResource(Res.drawable.ic_person_add),
                value = powerLevels?.invite ?: 0,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(invite = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = stringResource(Res.string.redact_messages_others),
                icon = painterResource(Res.drawable.ic_delete),
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
                label = stringResource(Res.string.send_messages_default),
                icon = painterResource(Res.drawable.ic_chat_bubble),
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
                label = stringResource(Res.string.change_room_name),
                icon = painterResource(Res.drawable.ic_edit),
                value = powerLevels?.roomName ?: 50,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(roomName = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = stringResource(Res.string.change_room_avatar),
                icon = painterResource(Res.drawable.ic_image),
                value = powerLevels?.roomAvatar ?: 50,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(roomAvatar = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = stringResource(Res.string.change_topic),
                icon = painterResource(Res.drawable.ic_subject),
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
                label = stringResource(Res.string.default_user_level),
                icon = painterResource(Res.drawable.ic_person),
                value = powerLevels?.usersDefault ?: 0,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(usersDefault = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = stringResource(Res.string.default_state_event_level),
                icon = painterResource(Res.drawable.ic_settings),
                value = powerLevels?.stateDefault ?: 50,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(stateDefault = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = stringResource(Res.string.manage_space_children),
                icon = painterResource(Res.drawable.ic_account_tree),
                value = powerLevels?.spaceChild ?: 100,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(spaceChild = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = stringResource(Res.string.share_live_location),
                icon = painterResource(Res.drawable.ic_location_on),
                value = powerLevels?.beacon ?: 0,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(beacon = it)) }
            )
        }
        item {
            PermissionSliderRow(
                label = stringResource(Res.string.manage_live_location),
                icon = painterResource(Res.drawable.ic_location_on),
                value = powerLevels?.beaconInfo ?: 50,
                canEdit = canEdit,
                onValueChange = { onUpdatePowerLevels(RoomPowerLevelChanges(beaconInfo = it)) }
            )
        }
    }
}

@Composable
private fun PermissionSliderRow(
    label: String,
    icon: Painter,
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
                    stringResource(Res.string.you_don_t_have_permission_to_change_this),
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
        level >= 100 -> stringResource(Res.string.admin) to MaterialTheme.colorScheme.primary
        level >= 50 -> stringResource(Res.string.mod) to MaterialTheme.colorScheme.tertiary
        level > 0 -> stringResource(Res.string.custom) to MaterialTheme.colorScheme.secondary
        else -> stringResource(Res.string.user) to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            stringResource(Res.string.role_chip, text, level),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
