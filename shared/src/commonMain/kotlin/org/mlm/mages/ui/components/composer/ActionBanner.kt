package org.mlm.mages.ui.components.composer

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.mlm.mages.MessageEvent
import org.mlm.mages.ui.components.message.ReplyPreview
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.toReplyPreview
import org.jetbrains.compose.resources.stringResource

@Composable
fun ActionBanner(
    replyingTo: MessageEvent?,
    editing: MessageEvent?,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit,
    resolvedPreviewPath: String? = null,
) {
    val isEditing = editing != null
    val event = editing ?: replyingTo
    if (event != null) {
        val replyTargetName = event.senderDisplayName?.takeIf { it.isNotBlank() } ?: event.sender
        Surface(
            color = if (isEditing) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(32.dp)
                        .background(
                            if (isEditing) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            RoundedCornerShape(2.dp)
                        )
                )
                Spacer(Modifier.width(Spacing.md))

                Icon(
                    if (isEditing) painterResource(Res.drawable.ic_edit) else painterResource(Res.drawable.ic_reply),
                    contentDescription = null,
                    tint = if (isEditing) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.sm))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when {
                            !isEditing -> stringResource(Res.string.replying_to_named, replyTargetName)
                            event.attachment != null -> stringResource(Res.string.editing_caption)
                            else -> stringResource(Res.string.editing)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isEditing) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    ReplyPreview(
                        isMine = false,
                        sender = null,
                        body = null,
                        preview = event.toReplyPreview(),
                        previewPath = resolvedPreviewPath,
                        showAccent = false,
                        maxLines = 1,
                    )
                }

                IconButton(
                    onClick = if (isEditing) onCancelEdit else onCancelReply,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_close),
                        if (isEditing) stringResource(Res.string.cancel_edit) else stringResource(Res.string.cancel_reply),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
