package org.mlm.mages.ui.components.dialogs

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

@Composable
fun DeclineInviteDialog(
    roomName: String,
    inviterName: String?,
    isLoading: Boolean,
    onDecline: (blockUser: Boolean, reportRoom: Boolean, reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var blockUser by remember(inviterName) { mutableStateOf(inviterName != null) }
    var reportRoom by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(Res.string.decline_invite)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = buildString {
                        append(stringResource(Res.string.decline_the_invite_to))
                        append(roomName)
                        if (inviterName != null) {
                            append(" from ")
                            append(inviterName)
                        }
                        append("?")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (inviterName != null) {
                    DialogToggleRow(
                        label = stringResource(Res.string.block_user_named, inviterName),
                        checked = blockUser,
                        enabled = !isLoading,
                        onCheckedChange = { blockUser = it },
                        icon = {
                            Icon(
                                painterResource(Res.drawable.ic_block),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }

                DialogToggleRow(
                    label = stringResource(Res.string.report_this_room),
                    checked = reportRoom,
                    enabled = !isLoading,
                    onCheckedChange = { reportRoom = it },
                    icon = {
                        Icon(
                            painterResource(Res.drawable.ic_flag),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )

                if (reportRoom) {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text(stringResource(Res.string.reason_optional)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        minLines = 2,
                        maxLines = 4,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDecline(blockUser, reportRoom, reason) },
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                if (isLoading) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onError
                    )
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(stringResource(Res.string.decline))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

@Composable
private fun DialogToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Spacer(Modifier.width(Spacing.sm))
        icon()
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
