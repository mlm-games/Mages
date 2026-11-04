package org.mlm.mages.ui.components.sheets

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

@Composable
fun RoomNotificationSheet(
    currentMode: RoomNotificationMode?,
    isLoading: Boolean,
    onModeChange: (RoomNotificationMode) -> Unit,
    onDismiss: () -> Unit,
    isSpace: Boolean = false,
    inheritanceLabel: String? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xxl)
        ) {
            Text(
                stringResource(Res.string.notification_settings),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
            )

            if (isSpace) {
                Text(
                    text = stringResource(Res.string.space_notifications_scope),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        bottom = Spacing.sm
                    )
                )
            }

            if (inheritanceLabel != null) {
                Text(
                    text = inheritanceLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        bottom = Spacing.sm
                    )
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            } else {
                NotificationOption(
                    icon = painterResource(Res.drawable.ic_notifications),
                    title = stringResource(Res.string.all_messages),
                    subtitle = stringResource(Res.string.get_notified_for_every_message),
                    isSelected = currentMode == RoomNotificationMode.AllMessages,
                    onClick = { onModeChange(RoomNotificationMode.AllMessages); onDismiss() }
                )

                NotificationOption(
                    icon = painterResource(Res.drawable.ic_alternate_email),
                    title = stringResource(Res.string.mentions_and_keywords_only),
                    subtitle = stringResource(Res.string.only_notify_when_mentioned_or_keywords_match),
                    isSelected = currentMode == RoomNotificationMode.MentionsAndKeywordsOnly,
                    onClick = { onModeChange(RoomNotificationMode.MentionsAndKeywordsOnly); onDismiss() }
                )

                NotificationOption(
                    icon = painterResource(Res.drawable.ic_notifications_off),
                    title = stringResource(Res.string.mute),
                    subtitle = stringResource(
                        if (isSpace) Res.string.no_notifications_from_this_space
                        else Res.string.no_notifications_from_this_room
                    ),
                    isSelected = currentMode == RoomNotificationMode.Mute,
                    onClick = { onModeChange(RoomNotificationMode.Mute); onDismiss() }
                )
            }
        }
    }
}

@Composable
private fun NotificationOption(
    icon: Painter,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                title,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(
                icon,
                null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            if (isSelected) {
                Icon(
                    painterResource(Res.drawable.ic_check),
                    stringResource(Res.string.selected),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        modifier = Modifier.clickable { onClick() },
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.surface
        )
    )
}