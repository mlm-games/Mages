package org.mlm.mages.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.mlm.mages.AttachmentKind
import org.mlm.mages.matrix.PollData
import org.mlm.mages.matrix.ReactionChip
import org.mlm.mages.matrix.SendState
import org.mlm.mages.ui.components.core.MarkdownText
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatDuration
import org.mlm.mages.ui.util.formatTime
import java.io.File
import kotlin.math.min

@Composable
fun MessageBubble(
    isMine: Boolean,
    body: String,
    sender: String?,
    timestamp: Long,
    groupedWithPrev: Boolean,
    groupedWithNext: Boolean,
    isDm: Boolean,
    modifier: Modifier = Modifier,
    reactionChips: List<ReactionChip> = emptyList(),
    eventId: String? = null,
    replyPreview: String? = null,
    replySender: String? = null,
    sendState: SendState? = null,
    thumbPath: String? = null,
    attachmentKind: AttachmentKind? = null,
    attachmentWidth: Int? = null,
    attachmentHeight: Int? = null,
    durationMs: Long? = null,
    lastReadByOthersTs: Long? = null,
    onLongPress: (() -> Unit)? = null,
    onReact: ((String) -> Unit)? = null,
    onOpenAttachment: (() -> Unit)? = null,
    isEdited: Boolean = false,
    poll: PollData? = null,
    onVote: ((String) -> Unit)? = null,
    onEndPoll: (() -> Unit)? = null,
    onReplyPreviewClick: (() -> Unit)? = null,
    threadCount: Int? = null,
    onOpenThread: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = Spacing.md,
                vertical = if (groupedWithPrev) 1.dp else 3.dp
            ),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        if (!isMine && !isDm && !groupedWithPrev && !sender.isNullOrBlank()) {
            Text(
                text = sender,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = 2.dp)
            )
        }

        BubbleWidthWrapper(fractionOfParent = 0.75f) {
            Surface(
                color = if (isMine) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                shape = bubbleShape(isMine, groupedWithPrev, groupedWithNext),
                tonalElevation = if (isMine) 3.dp else 1.dp,
                modifier = Modifier
                    .combinedClickable(onClick = {}, onLongClick = onLongPress)
            ) {
                Column(Modifier.padding(Spacing.md)) {
                    if (!replyPreview.isNullOrBlank()) {
                        ReplyPreview(isMine, replySender, replyPreview, onReplyPreviewClick)
                        Spacer(Modifier.height(Spacing.sm))
                    }

                    AttachmentThumbnail(
                        thumbPath = thumbPath,
                        attachmentKind = attachmentKind,
                        durationMs = durationMs,
                        isMine = isMine,
                        onOpen = onOpenAttachment,
                        attachmentWidth = attachmentWidth,
                        attachmentHeight = attachmentHeight
                    )

                    if (poll != null) {
                        PollBubble(
                            poll = poll,
                            isMine = isMine,
                            onVote = { optId -> onVote?.invoke(optId) },
                            onEndPoll = { onEndPoll?.invoke() }
                        )
                    } else if (body.isNotBlank()) {
                        MarkdownText(
                            text = body,
                            color = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isEdited) {
                        Text(
                            text = "(edited)",
                            style = MaterialTheme.typography.labelSmall,
                            color = (if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Text(
                        text = formatTime(timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = (if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = Spacing.xs)
                    )

                    if (isMine && sendState == SendState.Failed) {
                        FailedIndicator()
                    }
                }
            }
        }

        if (reactionChips.isNotEmpty()) {
            ReactionChipsRow(
                chips = reactionChips,
                onClick = onReact,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        if (threadCount != null && threadCount > 0 && onOpenThread != null) {
            if (reactionChips.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
            }
            ThreadIndicator(
                count = threadCount,
                onClick = onOpenThread
            )
        }
    }
}

private fun bubbleShape(isMine: Boolean, groupedWithPrev: Boolean, groupedWithNext: Boolean) = RoundedCornerShape(
    topStart = if (!isMine && groupedWithPrev) 4.dp else 16.dp,
    topEnd = if (isMine && groupedWithPrev) 4.dp else 16.dp,
    bottomStart = if (!isMine && groupedWithNext) 4.dp else 16.dp,
    bottomEnd = if (isMine && groupedWithNext) 4.dp else 16.dp
)

@Composable
private fun ReplyPreview(isMine: Boolean, sender: String?, body: String, onClick: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    ) {
        Row(modifier = Modifier.padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = buildString {
                    if (!sender.isNullOrBlank()) { append(sender); append(": ") }
                    append(body)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AttachmentThumbnail(
    thumbPath: String?,
    attachmentKind: AttachmentKind?,
    durationMs: Long?,
    isMine: Boolean,
    onOpen: (() -> Unit)?,
    attachmentWidth: Int? = null,
    attachmentHeight: Int? = null
) {
    if (attachmentKind == null) return

    val contentColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.surface
    val accentColor = if (isMine) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.secondary

    if (attachmentKind == AttachmentKind.File) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(contentColor.copy(alpha = 0.08f))
                .border(1.dp, contentColor.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                .clickable(enabled = onOpen != null) { onOpen?.invoke() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor.copy(alpha = 0.15f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Tap to open",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = contentColor
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        return
    }

    if (thumbPath != null) {
        val aspectRatio = if ((attachmentWidth ?: 0) > 0 && (attachmentHeight ?: 0) > 0) {
            attachmentWidth!!.toFloat() / attachmentHeight!!.toFloat()
        } else null

        Box(
            modifier = Modifier
                .heightIn(min = 120.dp, max = 300.dp)
                .sizeIn(maxHeight = 300.dp)
                .then(
                    if (aspectRatio != null) Modifier.aspectRatio(aspectRatio) else Modifier
                )
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = onOpen != null) { onOpen?.invoke() }
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(File(thumbPath))
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 300.dp)
            )

            if (attachmentKind == AttachmentKind.Video && durationMs != null) {
                DurationBadge(durationMs, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
    } else {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(contentColor.copy(alpha = 0.08f))
                .border(1.dp, contentColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
        ) {
            Text(
                text = if (attachmentKind == AttachmentKind.Video) "Video" else "Image",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ThreadIndicator(
    count: Int,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Forum,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (count == 1) "Reply" else "$count replies",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun DurationBadge(ms: Long, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
    ) {
        Text(
            text = formatDuration(ms),
            color = MaterialTheme.colorScheme.surface,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun FailedIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Failed to send. Check your internet?",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
fun BubbleWidthWrapper(
    modifier: Modifier = Modifier,
    fractionOfParent: Float = 0.8f,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val maxAllowed = (constraints.maxWidth * fractionOfParent).toInt()
        val childConstraints = constraints.copy(
            minWidth = 0,
            maxWidth = min(maxAllowed, constraints.maxWidth)
        )
        val placeable = measurables.first().measure(childConstraints)
        layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }
}
