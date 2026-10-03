package org.mlm.mages.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import mages.shared.generated.resources.*
import org.mlm.mages.ui.LastMessageType
import org.mlm.mages.ui.RoomListItemUi
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.theme.Limits
import org.mlm.mages.ui.util.secondaryClick
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.Res

@Composable
fun RoomListItem(
    item: RoomListItemUi,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val clickModifier = if (onLongClick != null) {
        Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .secondaryClick(onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }

    Surface(
        modifier = modifier.fillMaxWidth().then(clickModifier),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Avatar(
                    name = item.name,
                    avatarPath = item.avatarUrl,
                    size = 52.dp,
                    shape = CircleShape
                )

                if (item.unreadCount > 0) {
                    Badge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp)
                    ) {
                        Text(
                            if (item.unreadCount > Limits.unreadBadgeCap) "${Limits.unreadBadgeCap}+" else item.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                } else if (item.hasUnreadMessages) {
                    Badge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            "•",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                if (item.parentSpaces.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 2.dp, y = 2.dp)
                            .size(18.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Workspaces,
                            contentDescription = stringResource(Res.string.spaces),
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (item.hasUnreadMessages)
                            FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (item.isFavourite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Star,
                            contentDescription = stringResource(Res.string.favourite_room),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (item.isEncrypted) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = stringResource(Res.string.encrypted),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    if (item.isSharingLocation) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = stringResource(Res.string.sharing_live_location),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    if (item.parentSpaces.isNotEmpty()) {
                        Spacer(Modifier.width(Spacing.sm))
                        val first = item.parentSpaces.first()
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Avatar(
                                name = first.name ?: first.spaceId,
                                avatarPath = first.avatarUrl,
                                size = 16.dp,
                                shape = CircleShape
                            )
                        }
                        if (item.parentSpaces.size > 1) {
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = "+${item.parentSpaces.size - 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val preview = formatMessagePreview(
                        type = item.lastMessageType,
                        body = item.lastMessageBody ?: item.lastMessageLabel?.let { stringResource(it) },
                        sender = item.lastMessageSender,
                        isDm = item.isDm
                    )

                    preview.icon?.let { icon ->
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                    }

                    Text(
                        text = preview.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (item.hasUnreadMessages)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (item.hasUnreadMessages)
                            FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.width(Spacing.sm))

            val timeLabel = item.lastMessageTs?.let { formatRelativeTime(it) }
            if (timeLabel != null) {
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.hasUnreadMessages)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun InviteListItem(
    item: RoomListItemUi,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Avatar(
                    name = item.name,
                    avatarPath = item.avatarUrl,
                    size = 52.dp,
                    shape = CircleShape
                )

//                Badge(
//                    modifier = Modifier
//                        .align(Alignment.BottomEnd)
//                        .offset(x = 6.dp, y = 6.dp)
//                ) {
//                    Text(
//                        "Hey!",
//                        style = MaterialTheme.typography.labelSmall
//                    )
//                }
            }

            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(2.dp))

                Text(
                    text = stringResource(Res.string.invited_you_to_join),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Show topic if we have one (reusing lastMessageBody field for now)
                val topic = item.lastMessageBody ?: item.lastMessageLabel?.let { stringResource(it) }
                if (!topic.isNullOrBlank()) {
                    Text(
                        text = topic,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(Spacing.md))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onDecline,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(Res.string.decline))
                }

                Button(onClick = onAccept) {
                    Text(stringResource(Res.string.accept))
                }
            }
        }
    }
}

private data class MessagePreview(
    val text: String,
    val icon: ImageVector? = null
)

@Composable
private fun formatMessagePreview(
    type: LastMessageType,
    body: String?,
    sender: String?,
    isDm: Boolean
): MessagePreview {
    val senderPrefix = if (!isDm && sender != null) {
        "${formatSenderName(sender)}: "
    } else ""

    return when (type) {
        LastMessageType.Text -> {
            val text = body?.take(Limits.previewCharsMedium)?.replace('\n', ' ') ?: stringResource(Res.string.no_messages_yet)
            MessagePreview(text = senderPrefix + text)
        }

        LastMessageType.Image -> MessagePreview(
            text = senderPrefix + stringResource(Res.string.photo),
            icon = Icons.Default.Image
        )

        LastMessageType.Video -> MessagePreview(
            text = senderPrefix + stringResource(Res.string.video),
            icon = Icons.Default.Videocam
        )

        LastMessageType.Audio -> MessagePreview(
            text = senderPrefix + stringResource(Res.string.audio_message),
            icon = Icons.Default.Mic
        )

        LastMessageType.File -> MessagePreview(
            text = senderPrefix + (body?.takeIf { !it.startsWith("mxc://") } ?: stringResource(Res.string.file)),
            icon = Icons.Default.AttachFile
        )

        LastMessageType.Sticker -> MessagePreview(
            text = senderPrefix + stringResource(Res.string.picker_sticker),
            icon = Icons.Default.EmojiEmotions
        )

        LastMessageType.Location -> MessagePreview(
            text = senderPrefix + stringResource(Res.string.location),
            icon = Icons.Default.LocationOn
        )

        LastMessageType.Poll -> MessagePreview(
            text = senderPrefix + stringResource(Res.string.picker_poll),
            icon = Icons.Default.Poll
        )

        LastMessageType.Call -> MessagePreview(
            text = senderPrefix + stringResource(Res.string.call),
            icon = Icons.Default.Call
        )

        LastMessageType.Encrypted -> MessagePreview(
            text = stringResource(Res.string.encrypted_message),
            icon = Icons.Default.Lock
        )

        LastMessageType.Redacted -> MessagePreview(
            text = senderPrefix + stringResource(Res.string.message_deleted)
        )

        LastMessageType.Membership -> MessagePreview(
            text = body?.take(Limits.previewCharsMedium)?.replace('\n', ' ') ?: stringResource(Res.string.membership_changed)
        )

        LastMessageType.Unknown -> MessagePreview(
            text = body?.take(Limits.previewCharsMedium)?.replace('\n', ' ') ?: stringResource(Res.string.encrypted_or_unknown_message)
        )
    }
}

private fun formatSenderName(sender: String): String {
    return sender
        .removePrefix("@")
        .substringBefore(":")
        .take(15)
}

@OptIn(ExperimentalTime::class)
@Composable
fun formatRelativeTime(timestamp: Long): String {
    fun pad2(value: Int): String = value.toString().padStart(2, '0')

    val now = Clock.System.now()
    val messageTime = Instant.fromEpochMilliseconds(timestamp)
    val duration = now - messageTime

    val localNow = now.toLocalDateTime(TimeZone.currentSystemDefault())
    val localMessage = messageTime.toLocalDateTime(TimeZone.currentSystemDefault())

    return when {
        duration.inWholeMinutes < 1 -> "now"
        duration.inWholeHours < 1 -> "${duration.inWholeMinutes}m"
        localNow.date == localMessage.date -> {
            "${pad2(localMessage.hour)}:${pad2(localMessage.minute)}"
        }

        localNow.date.minus(1, DateTimeUnit.DAY) == localMessage.date -> stringResource(Res.string.yesterday)
        duration.inWholeDays < 7 -> {
            localMessage.dayOfWeek.name.lowercase()
                .replaceFirstChar { it.uppercase() }
                .take(3)
        }

        localNow.year == localMessage.year -> {
            "${localMessage.day} " +
                    localMessage.month.name.lowercase()
                        .replaceFirstChar { it.uppercase() }
                        .take(3)
        }

        else -> {
            "${localMessage.day}/${localMessage.month.number}/${localMessage.year.toString().takeLast(2)}"
        }
    }
}
