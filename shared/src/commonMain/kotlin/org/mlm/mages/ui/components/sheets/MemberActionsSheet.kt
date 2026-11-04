package org.mlm.mages.ui.components.sheets

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import mages.shared.generated.resources.*
import org.koin.compose.koinInject
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MemberSummary
import org.mlm.mages.ui.ActionAvailabilityUi
import org.mlm.mages.ui.ActionPresentationUi
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.AppColors
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch
import org.mlm.mages.nav.matrixToUserLink
import org.mlm.mages.platform.ShareContent
import org.mlm.mages.platform.ShareOutcome
import org.mlm.mages.platform.rememberShareHandler
import org.mlm.mages.ui.components.snackbar.SnackbarManager

@Composable
fun MemberActionsSheet(
    member: MemberSummary,
    onDismiss: () -> Unit,
    onStartDm: () -> Unit,
    onKick: (reason: String?) -> Unit,
    onBan: (reason: String?) -> Unit,
    onUnban: (reason: String?) -> Unit,
    onIgnore: () -> Unit,
    onVerify: (() -> Unit)? = null,
    onAvatarClick: (() -> Unit)? = null,
    verified: Boolean = false,
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

    val service: MatrixService = koinInject()
    var sharedRoomCount by remember(member.userId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(member.userId) {
        sharedRoomCount = runCatching {
            service.portOrNull?.mutualRooms(member.userId)?.count?.toInt()
        }.getOrNull()
    }

    val scope = rememberCoroutineScope()
    val shareHandler = rememberShareHandler()
    val snackbarManager: SnackbarManager = koinInject()
    val copiedLabel = stringResource(Res.string.copied_to_clipboard)
    val shareFailedLabel = stringResource(Res.string.share_failed)
    val profileLink = remember(member.userId) { matrixToUserLink(member.userId) }

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
                    size = Sizes.avatarMedium,
                    onClick = if (member.avatarUrl.isNullOrBlank()) null else onAvatarClick
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
                    sharedRoomCount?.takeIf { it > 0 }?.let { count ->
                        Text(
                            pluralStringResource(Res.plurals.shared_rooms_count, count, count),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider()

            // Actions
            if (dmAction.presentation != ActionPresentationUi.Hidden) {
                ActionItem(
                    icon = painterResource(Res.drawable.ic_chat),
                    title = stringResource(Res.string.send_direct_message),
                    subtitle = if (dmAction.presentation == ActionPresentationUi.Disabled) {
                        dmAction.reason
                    } else {
                        null
                    },
                    enabled = dmAction.isEnabled,
                    onClick = { onStartDm(); onDismiss() }
                )
            }

            if (onVerify != null) {
                ActionItem(
                    icon = painterResource(Res.drawable.ic_verified_user_filled),
                    title = stringResource(Res.string.verify_user),
                    subtitle = stringResource(Res.string.start_an_emoji_verification_with_them),
                    onClick = { onVerify(); onDismiss() }
                )
            } else if (verified) {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.verified), color = AppColors.Verified) },
                    leadingContent = { Icon(painterResource(Res.drawable.ic_verified_user_filled), null, tint = AppColors.Verified) }
                )
            }

            if (profileLink != null) {
                ActionItem(
                    icon = painterResource(Res.drawable.ic_share),
                    title = stringResource(Res.string.share_profile),
                    subtitle = profileLink,
                    onClick = {
                        val link = profileLink
                        scope.launch {
                            when (shareHandler(ShareContent(text = link))) {
                                ShareOutcome.Shared -> Unit
                                ShareOutcome.Copied -> snackbarManager.show(copiedLabel)
                                ShareOutcome.Failed -> snackbarManager.showError(shareFailedLabel)
                            }
                        }
                    }
                )
            }

            ActionItem(
                icon = painterResource(Res.drawable.ic_block),
                title = stringResource(Res.string.ignore_user),
                subtitle = stringResource(Res.string.hide_their_messages_everywhere),
                onClick = { onIgnore(); onDismiss() }
            )

            if (canModerate) {
                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))

                Text(
                    stringResource(Res.string.moderation),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )

                if (isBanned) {
                    ActionItem(
                        icon = painterResource(Res.drawable.ic_cancel),
                        title = stringResource(Res.string.unban_user),
                        subtitle = unbanAction.takeIf { it.presentation == ActionPresentationUi.Disabled }?.reason ?: stringResource(Res.string.allow_them_to_rejoin),
                        enabled = unbanAction.isEnabled,
                        onClick = { onUnban(null); onDismiss() }
                    )
                } else {
                    if (kickAction.presentation != ActionPresentationUi.Hidden) {
                        ActionItem(
                            icon = painterResource(Res.drawable.ic_exit_to_app),
                            title = stringResource(Res.string.remove_from_room),
                            subtitle = kickAction.takeIf { it.presentation == ActionPresentationUi.Disabled }?.reason ?: stringResource(Res.string.kick_user_from_this_room),
                            enabled = kickAction.isEnabled,
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { showKickDialog = true }
                        )
                    }

                    if (banAction.presentation != ActionPresentationUi.Hidden) {
                        ActionItem(
                            icon = painterResource(Res.drawable.ic_block),
                            title = stringResource(Res.string.ban_from_room),
                            subtitle = banAction.takeIf { it.presentation == ActionPresentationUi.Disabled }?.reason ?: stringResource(Res.string.permanently_remove_and_prevent_rejoining),
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
            title = stringResource(Res.string.remove_user_named, member.displayName ?: member.userId),
            message = stringResource(Res.string.they_will_be_removed_from_this_room_but_can_rejoin_if_invited),
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
            title = stringResource(Res.string.ban_user_named, member.displayName ?: member.userId),
            message = stringResource(Res.string.they_will_be_removed_and_won_t_be_able_to_rejoin_unless_unbanned),
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
    icon: Painter,
    title: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
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
                    label = { Text(stringResource(Res.string.reason_optional)) },
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
                Text(stringResource(Res.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}