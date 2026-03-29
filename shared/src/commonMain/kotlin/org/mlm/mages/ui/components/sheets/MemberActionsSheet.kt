package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.ui.ActionAvailabilityUi
import org.mlm.mages.ui.ActionPresentationUi
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing

@Composable
fun MemberActionsSheet(
    member: MemberSummary,
    onDismiss: () -> Unit,
    onStartDm: () -> Unit,
    onKick: (reason: String?) -> Unit,
    onBan: (reason: String?) -> Unit,
    onUnban: (reason: String?) -> Unit,
    onIgnore: () -> Unit,
    dmAction: ActionAvailabilityUi = ActionAvailabilityUi.Enabled,
    kickAction: ActionAvailabilityUi = ActionAvailabilityUi.Enabled,
    banAction: ActionAvailabilityUi = ActionAvailabilityUi.Enabled,
    unbanAction: ActionAvailabilityUi = ActionAvailabilityUi.Enabled,
    isBanned: Boolean = false
) {
    val canModerate = kickAction.presentation != ActionPresentationUi.Hidden || 
                      banAction.presentation != ActionPresentationUi.Hidden ||
                      (isBanned && unbanAction.presentation != ActionPresentationUi.Hidden)
    
    var showKickDialog by remember { mutableStateOf(false) }
    var showBanDialog by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xxl)
        ) {
            // Member header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(
                    name = member.displayName ?: member.userId,
                    avatarPath = member.avatarUrl,
                    size = Sizes.avatarMedium
                )
                Spacer(Modifier.width(Spacing.md))
                Column {
                    Text(
                        member.displayName ?: member.userId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        member.userId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            // Actions
            if (dmAction.presentation != ActionPresentationUi.Hidden) {
                ActionItem(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = "Send direct message",
                    subtitle = if (dmAction.presentation == ActionPresentationUi.Disabled) {
                        dmAction.reason
                    } else {
                        null
                    },
                    enabled = dmAction.isEnabled,
                    onClick = { onStartDm(); onDismiss() }
                )
            }

            ActionItem(
                icon = Icons.Default.Block,
                title = "Ignore user",
                subtitle = "Hide their messages everywhere",
                onClick = { onIgnore(); onDismiss() }
            )

            if (canModerate) {
                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))

                Text(
                    "Moderation",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )

                if (isBanned) {
                    ActionItem(
                        icon = Icons.Default.RemoveCircle,
                        title = "Unban user",
                        subtitle = unbanAction.takeIf { it.presentation == ActionPresentationUi.Disabled }?.reason ?: "Allow them to rejoin",
                        enabled = unbanAction.isEnabled,
                        onClick = { onUnban(null); onDismiss() }
                    )
                } else {
                    if (kickAction.presentation != ActionPresentationUi.Hidden) {
                        ActionItem(
                            icon = Icons.AutoMirrored.Filled.ExitToApp,
                            title = "Remove from room",
                            subtitle = kickAction.takeIf { it.presentation == ActionPresentationUi.Disabled }?.reason ?: "Kick user from this room",
                            enabled = kickAction.isEnabled,
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { showKickDialog = true }
                        )
                    }

                    if (banAction.presentation != ActionPresentationUi.Hidden) {
                        ActionItem(
                            icon = Icons.Default.Block,
                            title = "Ban from room",
                            subtitle = banAction.takeIf { it.presentation == ActionPresentationUi.Disabled }?.reason ?: "Permanently remove and prevent rejoining",
                            enabled = banAction.isEnabled,
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { showBanDialog = true }
                        )
                    }
                }
            }
        }
    }

    // Kick confirmation dialog
    if (showKickDialog) {
        ConfirmModerationDialog(
            title = "Remove ${member.displayName ?: member.userId}",
            message = "They will be removed from this room but can rejoin if invited.",
            reasonValue = reason,
            onReasonChange = { reason = it },
            onConfirm = {
                onKick(reason.ifBlank { null })
                onDismiss()
            },
            onDismiss = { showKickDialog = false }
        )
    }

    // Ban confirmation dialog
    if (showBanDialog) {
        ConfirmModerationDialog(
            title = "Ban ${member.displayName ?: member.userId}",
            message = "They will be removed and won't be able to rejoin unless unbanned.",
            reasonValue = reason,
            onReasonChange = { reason = it },
            isDestructive = true,
            onConfirm = {
                onBan(reason.ifBlank { null })
                onDismiss()
            },
            onDismiss = { showBanDialog = false }
        )
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val resolvedTint =
        if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant

    ListItem(
        headlineContent = { Text(title, color = resolvedTint) },
        supportingContent = subtitle?.let {
            {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingContent = { Icon(icon, null, tint = resolvedTint) },
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled = enabled) { onClick() }
    )
}

@Composable
private fun ConfirmModerationDialog(
    title: String,
    message: String,
    reasonValue: String,
    onReasonChange: (String) -> Unit,
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(message)
                OutlinedTextField(
                    value = reasonValue,
                    onValueChange = onReasonChange,
                    label = { Text("Reason (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (isDestructive)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors()
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}