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
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.matrix.SendState
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.core.MarkdownText
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.components.voice.VoiceMessageBubble
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatDuration
import org.mlm.mages.ui.util.formatTime
import kotlin.math.min

@Composable
fun TimelineSenderAvatar(
    senderDisplayName: String?,
    senderAvatarUrl: String?,
    senderId: String,
    size: Dp = Sizes.iconMedium,
) {
    Avatar(
        name = senderDisplayName ?: senderId,
        avatarPath = senderAvatarUrl,
        size = size
    )
}

@Composable
fun MessageBubble(
    model: MessageBubbleModel,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    onReact: ((String) -> Unit)? = null,
    onOpenAttachment: (() -> Unit)? = null,
    onVote: ((String) -> Unit)? = null,
    onEndPoll: (() -> Unit)? = null,
    onReplyPreviewClick: (() -> Unit)? = null,
    onOpenThread: (() -> Unit)? = null,
    onSenderClick: (() -> Unit)? = null,
    headerContent: (@Composable ColumnScope.() -> Unit)? = null,
    footerContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val isMine = model.isMine

    if (model.isSticker) {
        StickerMessage(
            model = model,
            onLongPress = onLongPress,
            onReact = onReact,
            onOpen = onOpenAttachment,
            onReplyPreviewClick = onReplyPreviewClick,
            onSenderClick = onSenderClick,
        )
        return
    }

    val isDm = model.isDm
    val showMessageAvatars = model.showMessageAvatars
    val grouping = model.grouping

    val showSenderInfo = !isMine && !model.sender?.displayName.isNullOrBlank() && (
            if (isDm) { model.showUsernameInDms } else { !grouping.groupedWithPrev }
            )
    val showSenderAvatar = showSenderInfo && showMessageAvatars && !model.sender.id.isNullOrBlank()

    val renderedBody = model.formattedBody.toMarkdownMentionsOrNull() ?: model.body
    val bubbleTextColor = if (isMine) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = Spacing.md,
                vertical = if (grouping.groupedWithPrev) 1.dp else 3.dp
            ),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        if (showSenderInfo) {
            if (showSenderAvatar) {
                Row(
                    modifier = Modifier
                        .clickable(enabled = onSenderClick != null, onClick = { onSenderClick?.invoke() })
                        .padding(horizontal = Spacing.md, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimelineSenderAvatar(
                        senderDisplayName = model.sender.displayName,
                        senderAvatarUrl = model.sender.avatarPath,
                        senderId = model.sender.id
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = model.sender.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Text(
                    text = model.sender.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable(enabled = onSenderClick != null, onClick = { onSenderClick?.invoke() })
                        .padding(horizontal = Spacing.md, vertical = 2.dp)
                )
            }
        }

        headerContent?.invoke(this)

        BubbleWidthWrapper(
            fractionOfParent = 0.75f
        ) {
            Surface(
                color = if (isMine) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                shape = bubbleShape(isMine, grouping.groupedWithPrev, grouping.groupedWithNext),
                tonalElevation = if (isMine) 3.dp else 1.dp,
                modifier = Modifier
                    .combinedClickable(onClick = {}, onLongClick = onLongPress)
            ) {
                Column(Modifier.padding(Spacing.md)) {
                    model.reply?.let { reply ->
                        if (!reply.body.isNullOrBlank()) {
                            ReplyPreview(isMine, reply.sender, reply.body, onReplyPreviewClick)
                            Spacer(Modifier.height(Spacing.sm))
                        }
                    }

                    when (val attachment = model.attachment) {
                        is MessageAttachmentUi.File -> {
                            FileAttachmentBubble(attachment, isMine, onOpenAttachment)
                        }
                        is MessageAttachmentUi.Image -> {
                            ImageAttachmentBubble(attachment, isMine, onOpenAttachment)
                        }
                        is MessageAttachmentUi.Video -> {
                            VideoAttachmentBubble(attachment, isMine, onOpenAttachment)
                        }
                        null -> { /* no attachment */ }
                        is MessageAttachmentUi.Audio -> {
                            VoiceMessageBubble(
                                filePath = attachment.filePath,
                                durationMs = attachment.durationMs ?: 0L,
                                waveformData = attachment.waveform,
                                isMine = isMine,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                        }
                    }

                    if (model.poll != null) {
                        PollBubble(
                            poll = model.poll,
                            isMine = isMine,
                            onVote = { optId -> onVote?.invoke(optId) },
                            onEndPoll = { onEndPoll?.invoke() }
                        )
                    } else if (model.attachment == null && model.body.isNotBlank()) {
                        MarkdownText(
                            text = renderedBody,
                            color = bubbleTextColor,
                        )
                    }

                    if (model.isEdited) {
                        Text(
                            text = "(edited)",
                            style = MaterialTheme.typography.labelSmall,
                            color = bubbleTextColor.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Text(
                        text = formatTime(model.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = bubbleTextColor.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = Spacing.xs)
                    )

                    if (isMine && model.sendState == SendState.Failed) {
                        FailedIndicator()
                    }
                }
            }
        }

        footerContent?.invoke(this)

        if (model.reactions.isNotEmpty()) {
            ReactionChipsRow(
                chips = model.reactions,
                onClick = onReact,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        model.thread?.let { thread ->
            if (thread.count > 0 && onOpenThread != null) {
                if (model.reactions.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                }
                ThreadIndicator(
                    count = thread.count,
                    onClick = onOpenThread
                )
            }
        }
    }
}

private fun String?.toMarkdownMentionsOrNull(): String? {
    if (this.isNullOrBlank()) return null
    val mentionRegex = Regex("""<a\s+href="(https://matrix\.to/#/@[^"]+)">(.*?)</a>""", RegexOption.IGNORE_CASE)
    if (!mentionRegex.containsMatchIn(this)) return null

    val markdown = mentionRegex.replace(this) { match ->
        val href = match.groupValues[1]
        val label = match.groupValues[2]
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
        "[$label]($href)"
    }

    return markdown
        .replace("<br>", "\n", ignoreCase = true)
        .replace("<br/>", "\n", ignoreCase = true)
        .replace("<br />", "\n", ignoreCase = true)
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
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
private fun FileAttachmentBubble(
    attachment: MessageAttachmentUi.File,
    isMine: Boolean,
    onOpen: (() -> Unit)?,
) {
    val contentColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSecondaryContainer
    val accentColor = if (isMine) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.secondary

    Box(
        modifier = Modifier
            .widthIn(max = 320.dp)
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

            Column(modifier = Modifier.widthIn(max = 240.dp)) {
                Text(
                    text = attachment.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                attachment.subtitle?.let { subtitle ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageAttachmentBubble(
    attachment: MessageAttachmentUi.Image,
    isMine: Boolean,
    onOpen: (() -> Unit)?,
) {
    val contentColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSecondaryContainer

    val previewPath = attachment.previewPath

    if (previewPath != null) {
        val aspectRatio = if ((attachment.width ?: 0) > 0 && (attachment.height ?: 0) > 0) {
            attachment.width!!.toFloat() / attachment.height!!.toFloat()
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
                    .data(previewPath)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 300.dp)
            )
        }
    } else {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(contentColor.copy(alpha = 0.08f))
                .border(1.dp, contentColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
        ) {
            Text(
                text = stringResource(Res.string.image),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )
        }
    }
}

@Composable
private fun VideoAttachmentBubble(
    attachment: MessageAttachmentUi.Video,
    isMine: Boolean,
    onOpen: (() -> Unit)?,
) {
    val contentColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSecondaryContainer

    val previewPath = attachment.previewPath

    if (previewPath != null) {
        val aspectRatio = if ((attachment.width ?: 0) > 0 && (attachment.height ?: 0) > 0) {
            attachment.width!!.toFloat() / attachment.height!!.toFloat()
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
                    .data(previewPath)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 300.dp)
            )

            attachment.durationMs?.let { duration ->
                DurationBadge(duration, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }
    } else {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(contentColor.copy(alpha = 0.08f))
                .border(1.dp, contentColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
        ) {
            Text(
                text = stringResource(Res.string.video),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )
        }
    }
}

@Composable
private fun StickerMessage(
    model: MessageBubbleModel,
    onLongPress: (() -> Unit)?,
    onReact: ((String) -> Unit)?,
    onOpen: (() -> Unit)?,
    onReplyPreviewClick: (() -> Unit)?,
    onSenderClick: (() -> Unit)?,
) {
    val isMine = model.isMine
    val sticker = model.sticker ?: return
    val grouping = model.grouping

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isMine) 72.dp else Spacing.md,
                end = if (isMine) Spacing.md else 72.dp,
                top = if (grouping.groupedWithPrev) 2.dp else Spacing.sm,
                bottom = 2.dp,
            ),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
    ) {
        if (!isMine && model.sender != null && grouping.groupedWithPrev != true) {
            val sender = model.sender
            Text(
                text = sender.displayName ?: sender.id ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(bottom = 2.dp)
                    .then(
                        if (onSenderClick != null) Modifier.clickable { onSenderClick() }
                        else Modifier
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        model.reply?.let { reply ->
            val replyBody = reply.body
            val replySender = reply.sender
            if (replyBody != null || replySender != null) {
                ReplyPreview(isMine, replySender ?: "", replyBody ?: "", onReplyPreviewClick)
                Spacer(Modifier.height(4.dp))
            }
        }

        val maxStickerSize = 200.dp
        val aspectRatio = if ((sticker.width ?: 0) > 0 && (sticker.height ?: 0) > 0) {
            sticker.width!!.toFloat() / sticker.height!!.toFloat()
        } else 1f

        Box(
            modifier = Modifier
                .widthIn(max = maxStickerSize)
                .aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
                .combinedClickable(
                    onClick = { onOpen?.invoke() },
                    onLongClick = onLongPress,
                )
        ) {
            if (sticker.thumbPath != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(sticker.thumbPath)
                        .crossfade(true)
                        .build(),
                    contentDescription = model.body.takeIf { it.isNotBlank() },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(maxStickerSize)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.EmojiEmotions,
                        contentDescription = "Sticker",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Text(
                text = formatTime(model.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            if (model.isEdited) {
                Text(
                    text = "(edited)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }

        if (!model.reactions.isNullOrEmpty()) {
            ReactionChipsRow(
                chips = model.reactions,
                onClick = onReact,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
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
