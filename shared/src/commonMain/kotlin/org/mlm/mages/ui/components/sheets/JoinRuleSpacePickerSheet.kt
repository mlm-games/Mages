package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Workspaces
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
                if (asksToKnock) "Ask to join with space members" else "Space members can join",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                if (asksToKnock) {
                    "Members of the selected spaces can ask to join. A room admin approves each request."
                } else {
                    "Members of the selected spaces can join without an invite."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.lg))

            if (spaces.isEmpty()) {
                Text(
                    "No spaces available. Join or create a space first, then set this access level.",
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
                                    Icons.Default.Workspaces,
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
                                        Icons.Default.Check,
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
