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
import org.mlm.mages.matrix.RoomJoinRule
import org.mlm.mages.matrix.SpaceInfo
import org.mlm.mages.ui.theme.Spacing
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun JoinRuleSpacePickerSheet(
    rule: RoomJoinRule,
    spaces: List<SpaceInfo>,
    initiallyAllowedSpaceIds: List<String>,
    onSave: (RoomJoinRule, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(rule, initiallyAllowedSpaceIds) {
        mutableStateOf(initiallyAllowedSpaceIds.toSet())
    }

    val asksToKnock = rule == RoomJoinRule.KnockRestricted

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
                .padding(bottom = Spacing.xxl),
        ) {
            Text(
                if (asksToKnock) stringResource(Res.string.ask_to_join_with_space_members) else stringResource(Res.string.space_members_can_join),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                if (asksToKnock) {
                    stringResource(Res.string.members_of_the_selected_spaces_can_ask_to_join_a_room_admin_approves_each_request)
                } else {
                    stringResource(Res.string.members_of_the_selected_spaces_can_join_without_an_invite)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.lg))

            if (spaces.isEmpty()) {
                Text(
                    stringResource(Res.string.no_spaces_available_join_or_create_a_space_first_then_set_this_access_level),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(spaces, key = { it.roomId }) { space ->
                        val isSelected = space.roomId in selected
                        Surface(
                            onClick = {
                                selected = if (isSelected) {
                                    selected - space.roomId
                                } else {
                                    selected + space.roomId
                                }
                            },
                            shape = MaterialTheme.shapes.medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Spacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painterResource(Res.drawable.ic_workspaces),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(Spacing.md))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        space.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        space.topic ?: space.roomId,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        painterResource(Res.drawable.ic_check),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.cancel))
                }
                Spacer(Modifier.width(Spacing.sm))
                Button(
                    onClick = { onSave(rule, selected.toList()) },
                    enabled = spaces.isNotEmpty() && selected.isNotEmpty(),
                ) {
                    Text(stringResource(Res.string.save))
                }
            }
        }
    }
}
