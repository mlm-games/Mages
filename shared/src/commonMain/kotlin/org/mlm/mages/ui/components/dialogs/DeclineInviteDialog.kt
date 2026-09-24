package org.mlm.mages.ui.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.theme.Spacing

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
        title = { Text("Decline invite") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = buildString {
                        append("Decline the invite to ")
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
                        label = "Block $inviterName",
                        checked = blockUser,
                        enabled = !isLoading,
                        onCheckedChange = { blockUser = it },
                        icon = {
                            Icon(
                                Icons.Default.Block,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }

                DialogToggleRow(
                    label = "Report this room",
                    checked = reportRoom,
                    enabled = !isLoading,
                    onCheckedChange = { reportRoom = it },
                    icon = {
                        Icon(
                            Icons.Default.Flag,
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
                        label = { Text("Reason (optional)") },
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
                Text("Decline")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel")
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
