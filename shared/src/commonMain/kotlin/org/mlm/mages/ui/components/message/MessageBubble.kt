package org.mlm.mages.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.github.panpf.sketch.AsyncImage as SketchAsyncImage
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.ReplyPreview
import org.mlm.mages.ReplyPreviewKind
import org.mlm.mages.matrix.SendState
import org.mlm.mages.ui.components.blurHashBackground
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.components.core.FormattedBodyText
import org.mlm.mages.ui.components.core.MentionRef
import org.mlm.mages.ui.components.core.formatDisplayName
import org.mlm.mages.platform.isTgsSticker
import org.mlm.mages.ui.components.rememberBlurHashImage
import org.mlm.mages.ui.components.sticker.TgsSticker
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.components.voice.VoiceMessageBubble
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatDuration
import org.mlm.mages.ui.util.formatTime
import org.mlm.mages.ui.util.secondaryClick
import kotlin.math.min
import org.jetbrains.compose.resources.pluralStringResource

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
    onMentionClick: ((String) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onSecondaryClick: (() -> Unit)? = onLongPress,
    onDoubleClick: (() -> Unit)? = null,
    onReact: ((String) -> Unit)? = null,
    onOpenAttachment: (() -> Unit)? = null,
    onShowPreview: (() -> Unit)? = null,
    onVote: ((String) -> Unit)? = null,
    onEndPoll: (() -> Unit)? = null,
    onReplyPreviewClick: (() -> Unit)? = null,
    onOpenThread: (() -> Unit)? = null,
    onSenderClick: (() -> Unit)? = null,
    headerContent: (@Composable ColumnScope.() -> Unit)? = null,
    footerContent: (@Composable ColumnScope.() -> Unit)? = null,
    emotePaths: Map<String, String> = emptyMap(),
) {
    val isMine = model.isMine
    val horizontalAlignment = if (isMine) Alignment.End else Alignment.Start

    if (model.isSticker) {
        StickerMessage(
            model = model,
            onLongPress = onLongPress,
            onSecondaryClick = onSecondaryClick,
            onDoubleClick = onDoubleClick,
            onReact = onReact,
            onOpen = onOpenAttachment,
            onShowPreview = onShowPreview,
            onReplyPreviewClick = onReplyPreviewClick,
            onSenderClick = onSenderClick,
        )
        return
    }

    val isDm = model.isDm
    val showMessageAvatars = model.showMessageAvatars
    val grouping = model.grouping

    val sender = model.sender
    val senderName = sender?.displayName?.takeIf { it.isNotBlank() }
        ?: sender?.id?.takeIf { it.isNotBlank() }?.let(::formatDisplayName)
    val showSenderInfo = !isMine && senderName != null && (
            if (isDm) { model.showUsernameInDms && !grouping.groupedWithPrev } else { !grouping.groupedWithPrev }
            )
    val showSenderAvatar = showSenderInfo && showMessageAvatars && !sender?.id.isNullOrBlank()

    val isRedacted = model.isRedacted
    val threadCount = model.thread?.count?.takeIf { it > 0 }

    val bubbleContainerColor = if (isRedacted) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    } else if (isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }

    val bubbleTextColor = if (!isRedacted && isMine) {
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
        horizontalAlignment = horizontalAlignment
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
                        senderDisplayName = senderName,
                        senderAvatarUrl = sender?.avatarPath,
                        senderId = sender?.id.orEmpty()
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = senderName.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Text(
                    text = senderName.orEmpty(),
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
            fractionOfParent = 0.9f,
            leading = {
                if (isMine && threadCount != null && onOpenThread != null) {
                    ThreadBadge(
                        count = threadCount,
                        onClick = onOpenThread,
                        modifier = Modifier.padding(end = Spacing.xs)
                    )
                }
            },
            trailing = {
                if (!isMine && threadCount != null && onOpenThread != null) {
                    ThreadBadge(
                        count = threadCount,
                        onClick = onOpenThread,
                        modifier = Modifier.padding(start = Spacing.xs)
                    )
                }
            }
        ) {
            Surface(
                color = bubbleContainerColor,
                shape = bubbleShape(isMine, grouping.groupedWithPrev, grouping.groupedWithNext),
                tonalElevation = if (isRedacted) 0.dp else if (isMine) 3.dp else 1.dp,
            ) {
                val timestampContent = @Composable {
                    MessageTimeAndStatus(
                        timestamp = model.timestamp,
                        isEdited = model.isEdited,
                        textColor = bubbleTextColor,
                        isPinned = model.isPinned,
                        isMine = isMine
                    )
                }

                Layout(
                    modifier = Modifier
                        .combinedClickable(
                            onClick = {},
                            onLongClick = onLongPress,
                            onDoubleClick = onDoubleClick
                        )
                        .secondaryClick(onSecondaryClick)
                        .padding(Spacing.md),
                    content = {
                        model.reply?.let { reply ->
                            if (!reply.body.isNullOrBlank() ||
                                reply.preview != null ||
                                !reply.sender.isNullOrBlank()
                            ) {
                                ReplyPreview(
                                    isMine = isMine,
                                    sender = reply.sender,
                                    body = reply.body,
                                    preview = reply.preview,
                                    previewPath = reply.previewPath,
                                    onClick = onReplyPreviewClick,
                                )
                            }
                        }
                        Column(horizontalAlignment = horizontalAlignment) {
                            when (val attachment = model.attachment) {
                                is MessageAttachmentUi.File -> {
                                    FileAttachmentBubble(attachment, isMine, onOpenAttachment, onLongPress)
                                    if (!attachment.caption.isNullOrBlank()) {
                                        Spacer(Modifier.height(Spacing.xs))
                                        TimestampLayout(
                                            position = TimestampPosition.Aligned,
                                            timestamp = timestampContent,
                                        ) {
                                            FormattedBodyText(
                                                formattedBody = attachment.captionFormattedBody,
                                                fallbackBody = attachment.caption,
                                                color = bubbleTextColor,
                                                containerColor = bubbleContainerColor,
                                                emotePaths = emotePaths,
                                                mentionRefs = model.mentions,
                                                mentionsRoom = model.mentionsRoom,
                                                onMentionClick = onMentionClick
                                            )
                                        }
                                    } else {
                                        Spacer(Modifier.height(Spacing.xs))
                                        MessageTimeAndStatus(
                                            timestamp = model.timestamp,
                                            isEdited = model.isEdited,
                                            textColor = bubbleTextColor,
                                            isPinned = model.isPinned,
                                            isMine = isMine,
                                            modifier = Modifier.align(horizontalAlignment)
                                        )
                                    }
                                }
                                is MessageAttachmentUi.Image -> {
                                    ImageAttachmentBubble(
                                        attachment = attachment,
                                        isMine = isMine,
                                        onOpen = onOpenAttachment,
                                        onShowPreview = onShowPreview,
                                        onLongPress = onLongPress,
                                        timestamp = timestampContent,
                                        containerColor = bubbleContainerColor,
                                        emotePaths = emotePaths,
                                        mentionRefs = model.mentions,
                                        mentionsRoom = model.mentionsRoom,
                                        onMentionClick = onMentionClick
                                    )
                                }
                                is MessageAttachmentUi.Video -> {
                                    VideoAttachmentBubble(
                                        attachment = attachment,
                                        isMine = isMine,
                                        onOpen = onOpenAttachment,
                                        onShowPreview = onShowPreview,
                                        onLongPress = onLongPress,
                                        timestamp = timestampContent,
                                        containerColor = bubbleContainerColor,
                                        emotePaths = emotePaths,
                                        mentionRefs = model.mentions,
                                        mentionsRoom = model.mentionsRoom,
                                        onMentionClick = onMentionClick
                                    )
                                }
                                null -> { /* no attachment */ }
                                is MessageAttachmentUi.Audio -> {
                                    if (attachment.isVoice) {
                                        VoiceMessageBubble(
                                            filePath = attachment.filePath,
                                            durationMs = attachment.durationMs ?: 0L,
                                            waveformData = attachment.waveform,
                                            isMine = isMine,
                                        )
                                    } else {
                                        AudioFileBubble(attachment, isMine, onOpenAttachment, onLongPress)
                                    }
                                    if (!attachment.caption.isNullOrBlank()) {
                                        Spacer(Modifier.height(Spacing.xs))
                                        TimestampLayout(
                                            position = TimestampPosition.Aligned,
                                            timestamp = timestampContent,
                                        ) {
                                            FormattedBodyText(
                                                formattedBody = attachment.captionFormattedBody,
                                                fallbackBody = attachment.caption,
                                                color = bubbleTextColor,
                                                containerColor = bubbleContainerColor,
                                                emotePaths = emotePaths,
                                                mentionRefs = model.mentions,
                                                mentionsRoom = model.mentionsRoom,
                                                onMentionClick = onMentionClick
                                            )
                                        }
                                    } else {
                                        Spacer(Modifier.height(Spacing.xs))
                                        MessageTimeAndStatus(
                                            timestamp = model.timestamp,
                                            isEdited = model.isEdited,
                                            textColor = bubbleTextColor,
                                            isPinned = model.isPinned,
                                            isMine = isMine,
                                            modifier = Modifier.align(horizontalAlignment)
                                        )
                                    }
                                }
                            }

                            if (model.poll != null) {
                                PollBubble(
                                    poll = model.poll,
                                    isMine = isMine,
                                    onVote = { optId -> onVote?.invoke(optId) },
                                    onEndPoll = { onEndPoll?.invoke() }
                                )
                                Spacer(Modifier.height(Spacing.xs))
                                MessageTimeAndStatus(
                                    timestamp = model.timestamp,
                                    isEdited = model.isEdited,
                                    textColor = bubbleTextColor,
                                    isPinned = model.isPinned,
                                    isMine = isMine,
                                    modifier = Modifier.align(horizontalAlignment)
                                )
                            } else if (isRedacted) {
                                TimestampLayout(
                                    position = TimestampPosition.Aligned,
                                    timestamp = timestampContent,
                                ) {
                                    RedactedBody(
                                        text = model.body,
                                        color = bubbleTextColor.copy(alpha = 0.6f),
                                    )
                                }
                            } else if (model.attachment == null && model.body.isNotBlank()) {
                                TimestampLayout(
                                    position = TimestampPosition.Aligned,
                                    timestamp = timestampContent,
                                ) {
                                    FormattedBodyText(
                                        formattedBody = model.formattedBody,
                                        fallbackBody = model.body,
                                        emotePaths = emotePaths,
                                        color = bubbleTextColor,
                                        containerColor = bubbleContainerColor,
                                        mentionRefs = model.mentions,
                                        mentionsRoom = model.mentionsRoom,
                                        onMentionClick = onMentionClick
                                    )
                                }
                            } else if (model.attachment == null && model.body.isBlank()) {
                                MessageTimeAndStatus(
                                    timestamp = model.timestamp,
                                    isEdited = model.isEdited,
                                    textColor = bubbleTextColor,
                                    isPinned = model.isPinned,
                                    isMine = isMine,
                                    modifier = Modifier.align(horizontalAlignment)
                                )
                            }

                            model.linkPreview?.let { preview ->
                                Spacer(Modifier.height(Spacing.xs))
                                LinkPreviewCard(
                                    preview = preview,
                                    imagePath = model.linkPreviewImage,
                                )
                            }

                            if (isMine) {
                                when {
                                    model.sendState == SendState.Failed -> FailedIndicator(
                                        stringResource(Res.string.failed_to_send_check_your_internet)
                                    )
                                    model.editSendState == SendState.Failed -> FailedIndicator(
                                        stringResource(Res.string.failed_to_save_edit)
                                    )
                                    model.redactionSendState == SendState.Failed -> FailedIndicator(
                                        stringResource(Res.string.failed_to_delete_message)
                                    )
                                }
                            }
                        }
                    }
                ) { measurables, constraints ->
                    val hasReply = measurables.size > 1
                    val looseConstraints = constraints.copy(minWidth = 0)

                    if (hasReply) {
                        val replyMeasurable = measurables[0]
                        val mainMeasurable = measurables[1]

                        val replyIntrinsicWidth = replyMeasurable.maxIntrinsicWidth(constraints.maxHeight)
                            .coerceAtMost(constraints.maxWidth)
                            .coerceAtLeast(0)

                        val mainPlaceable = mainMeasurable.measure(looseConstraints)
                        val contentWidth = maxOf(mainPlaceable.width, replyIntrinsicWidth)

                        val replyPlaceable = replyMeasurable.measure(
                            looseConstraints.copy(minWidth = contentWidth, maxWidth = contentWidth)
                        )

                        val spacing = 8.dp.roundToPx()
                        val totalHeight = replyPlaceable.height + spacing + mainPlaceable.height

                        layout(contentWidth, totalHeight) {
                            replyPlaceable.place(0, 0)
                            mainPlaceable.place(
                                x = horizontalAlignment.align(mainPlaceable.width, contentWidth, layoutDirection),
                                y = replyPlaceable.height + spacing
                            )
                        }
                    } else {
                        val mainPlaceable = measurables[0].measure(looseConstraints)
                        layout(mainPlaceable.width, mainPlaceable.height) {
                            mainPlaceable.place(
                                x = horizontalAlignment.align(mainPlaceable.width, mainPlaceable.width, layoutDirection),
                                y = 0
                            )
                        }
                    }
                }
            }
        }

        footerContent?.invoke(this)

        Column(
            modifier = Modifier
                .offset(y = (-10).dp) // To show the reactions over the message
                .align(horizontalAlignment),
            horizontalAlignment = horizontalAlignment
        ) {
            if (model.reactions.isNotEmpty()) {
                ReactionChipsRow(
                    chips = model.reactions,
                    avatarPathsByUserId = model.reactionAvatarsByUserId,
                    imagePaths = model.reactionImagePaths,
                    shortcodes = model.reactionShortcodes,
                    showAvatars = model.showReactionAvatars,
                    onClick = onReact,
                    modifier = Modifier.padding(horizontal = Spacing.xs)
                )
            }
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
internal fun ReplyPreview(
    isMine: Boolean,
    sender: String?,
    body: String?,
    preview: ReplyPreview?,
    previewPath: String? = null,
    showAccent: Boolean = true,
    maxLines: Int = 2,
    onClick: (() -> Unit)? = null,
) {
    val accentHeight = if (
        preview?.kind != null && preview.kind != ReplyPreviewKind.Text
    ) 40.dp else 24.dp
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(Spacing.sm)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showAccent) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(accentHeight)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            preview?.takeIf { it.kind != ReplyPreviewKind.Text }?.let {
                ReplyPreviewVisual(it, previewPath)
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                text = buildString {
                    val previewText = body?.takeIf { it.isNotBlank() } ?: preview?.text
                    if (!sender.isNullOrBlank()) {
                        append(sender)
                        if (!previewText.isNullOrBlank()) append(": ")
                    }
                    append(previewText.orEmpty())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ReplyPreviewVisual(preview: ReplyPreview, resolvedPath: String?) {
    val imageSource = resolvedPath
    val icon = when (preview.kind) {
        ReplyPreviewKind.Image -> Icons.Default.Image
        ReplyPreviewKind.Video -> Icons.Default.Videocam
        ReplyPreviewKind.Audio -> Icons.Default.AudioFile
        ReplyPreviewKind.Voice -> Icons.Default.Mic
        ReplyPreviewKind.File -> Icons.AutoMirrored.Filled.InsertDriveFile
        ReplyPreviewKind.Sticker -> Icons.Default.EmojiEmotions
        ReplyPreviewKind.Poll -> Icons.Default.Poll
        ReplyPreviewKind.Call, ReplyPreviewKind.VoiceCall -> Icons.Default.Call
        ReplyPreviewKind.VideoCall -> Icons.Default.Videocam
        ReplyPreviewKind.Location, ReplyPreviewKind.LiveLocation -> Icons.Default.LocationOn
        ReplyPreviewKind.Encrypted -> Icons.Default.Lock
        ReplyPreviewKind.Redacted -> Icons.Default.Delete
        ReplyPreviewKind.Unsupported -> Icons.Default.Info
        ReplyPreviewKind.Text -> null
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        if (imageSource != null) {
            if (isTgsSticker(preview.sticker?.mime, imageSource)) {
                TgsSticker(
                    source = imageSource,
                    animate = false,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(imageSource)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = if (preview.kind == ReplyPreviewKind.Sticker) {
                        ContentScale.Fit
                    } else {
                        ContentScale.Crop
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun FileAttachmentBubble(
    attachment: MessageAttachmentUi.File,
    isMine: Boolean,
    onOpen: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
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
            .combinedClickable(
                enabled = onOpen != null,
                onClick = { onOpen?.invoke() },
                onLongClick = onLongPress,
            )
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
private fun AudioFileBubble(
    attachment: MessageAttachmentUi.Audio,
    isMine: Boolean,
    onOpen: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    val contentColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSecondaryContainer
    val accentColor = if (isMine) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.secondary
    val details = listOfNotNull(
        attachment.subtitle,
        attachment.durationMs?.let { formatDuration(it) },
    ).joinToString(" • ").takeIf { it.isNotBlank() }

    Box(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(contentColor.copy(alpha = 0.08f))
            .border(1.dp, contentColor.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .combinedClickable(
                enabled = onOpen != null,
                onClick = { onOpen?.invoke() },
                onLongClick = onLongPress,
            )
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

                details?.let { subtitle ->
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
    onLongPress: (() -> Unit)? = null,
    timestamp: @Composable () -> Unit,
    containerColor: Color,
    emotePaths: Map<String, String> = emptyMap(),
    mentionRefs: Map<String, MentionRef> = emptyMap(),
    mentionsRoom: Boolean = false,
    onMentionClick: ((String) -> Unit)? = null,
    onShowPreview: (() -> Unit)? = null,
) {
    val contentColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSecondaryContainer
    val horizontalAlignment = if (isMine) Alignment.End else Alignment.Start

    val previewPath = attachment.previewPath
    val hasCaption = !attachment.caption.isNullOrBlank()
    val isShortCaption = hasCaption && (attachment.caption!!.length < 30)
    val blurhashImage = rememberBlurHashImage(attachment.blurhash)
    val maxMediaWidth = if (isShortCaption) 240.dp else 320.dp
    val previewBlocked = attachment.previewBlocked && previewPath == null

    Column(horizontalAlignment = horizontalAlignment) {
        MediaTile(
            width = attachment.width,
            height = attachment.height,
            maxWidth = maxMediaWidth,
            blurhashImage = blurhashImage,
            onOpen = onOpen,
            onLongPress = onLongPress,
        ) {
            Box(modifier = Modifier.matchParentSize()) {
                if (previewPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalPlatformContext.current)
                            .data(previewPath)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 268.dp)
                    )
                } else if (!previewBlocked) {
                    MediaTilePlaceholder(
                        label = stringResource(Res.string.image),
                        icon = Icons.Default.Image,
                        contentColor = contentColor,
                    )
                }
                if (previewBlocked) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color(0xFF000000).copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextButton(onClick = { onShowPreview?.invoke() }) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = stringResource(Res.string.show),
                            )
                        }
                    }
                }
            }
            if (!hasCaption) {
                TimestampBadge(timestamp)
            }
        }
        if (hasCaption) {
            TimestampLayout(
                position = TimestampPosition.Aligned,
                timestamp = timestamp,
            ) {
                FormattedBodyText(
                    formattedBody = attachment.captionFormattedBody,
                    fallbackBody = attachment.caption!!,
                    color = contentColor,
                    containerColor = containerColor,
                    emotePaths = emotePaths,
                    mentionRefs = mentionRefs,
                    mentionsRoom = mentionsRoom,
                    onMentionClick = onMentionClick
                )
            }
        }
    }
}

@Composable
private fun MediaTile(
    width: Int?,
    height: Int?,
    maxWidth: Dp,
    blurhashImage: ImageBitmap?,
    onOpen: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
    content: @Composable BoxScope.() -> Unit,
) {
    val aspectRatio = if ((width ?: 0) > 0 && (height ?: 0) > 0) {
        (width!!.toFloat() / height!!.toFloat()).coerceIn(0.001f, 10f)
    } else 1.33f

    Box(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .heightIn(min = 120.dp, max = 268.dp)
            .sizeIn(maxHeight = 268.dp)
            .aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(8.dp))
            .blurHashBackground(blurhashImage)
            .combinedClickable(
                enabled = onOpen != null || onLongPress != null,
                onClick = {
                    onOpen?.invoke()
                },
                onLongClick = onLongPress,
            ),
        content = content,
    )
}

@Composable
private fun BoxScope.MediaTilePlaceholder(
    label: String,
    icon: ImageVector,
    contentColor: Color,
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .border(1.dp, contentColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor.copy(alpha = 0.7f),
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun BoxScope.TimestampBadge(timestamp: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(4.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        timestamp()
    }
}

@Composable
private fun VideoAttachmentBubble(
    attachment: MessageAttachmentUi.Video,
    isMine: Boolean,
    onOpen: (() -> Unit)?,
    onLongPress: (() -> Unit)? = null,
    timestamp: @Composable () -> Unit,
    containerColor: Color,
    emotePaths: Map<String, String> = emptyMap(),
    mentionRefs: Map<String, MentionRef> = emptyMap(),
    mentionsRoom: Boolean = false,
    onMentionClick: ((String) -> Unit)? = null,
    onShowPreview: (() -> Unit)? = null,
) {
    val contentColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSecondaryContainer
    val horizontalAlignment = if (isMine) Alignment.End else Alignment.Start

    val previewPath = attachment.previewPath
    val hasCaption = !attachment.caption.isNullOrBlank()
    val isShortCaption = hasCaption && (attachment.caption!!.length < 30)
    val blurhashImage = rememberBlurHashImage(attachment.blurhash)
    val maxMediaWidth = if (isShortCaption) 240.dp else 320.dp
    val previewBlocked = attachment.previewBlocked && previewPath == null

    Column(horizontalAlignment = horizontalAlignment) {
        MediaTile(
            width = attachment.width,
            height = attachment.height,
            maxWidth = maxMediaWidth,
            blurhashImage = blurhashImage,
            onOpen = onOpen,
            onLongPress = onLongPress,
        ) {
            Box(modifier = Modifier.matchParentSize()) {
                if (previewPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalPlatformContext.current)
                            .data(previewPath)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 268.dp)
                    )
                } else if (!previewBlocked) {
                    MediaTilePlaceholder(
                        label = stringResource(Res.string.video),
                        icon = Icons.Default.Videocam,
                        contentColor = contentColor,
                    )
                }
                if (previewBlocked) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color(0xFF000000).copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextButton(onClick = { onShowPreview?.invoke() }) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = stringResource(Res.string.show),
                            )
                        }
                    }
                }
            }

            attachment.durationMs?.let { duration ->
                DurationBadge(duration, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }

            if (!hasCaption) {
                TimestampBadge(timestamp)
            }
        }
        if (hasCaption) {
            TimestampLayout(
                position = TimestampPosition.Aligned,
                timestamp = timestamp,
            ) {
                FormattedBodyText(
                    formattedBody = attachment.captionFormattedBody,
                    fallbackBody = attachment.caption!!,
                    color = contentColor,
                    containerColor = containerColor,
                    emotePaths = emotePaths,
                    mentionRefs = mentionRefs,
                    mentionsRoom = mentionsRoom,
                    onMentionClick = onMentionClick
                )
            }
        }
    }
}

@Composable
private fun StickerMessage(
    model: MessageBubbleModel,
    onLongPress: (() -> Unit)?,
    onSecondaryClick: (() -> Unit)? = onLongPress,
    onDoubleClick: (() -> Unit)?,
    onReact: ((String) -> Unit)?,
    onOpen: (() -> Unit)?,
    onShowPreview: (() -> Unit)?,
    onReplyPreviewClick: (() -> Unit)?,
    onSenderClick: (() -> Unit)?,
) {
    val isMine = model.isMine
    val sticker = model.sticker ?: return
    val previewBlocked = sticker.previewBlocked && sticker.thumbPath == null
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
        if (!isMine && model.sender != null && !grouping.groupedWithPrev) {
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
            if (!replyBody.isNullOrBlank() || reply.preview != null || replySender != null) {
                ReplyPreview(
                    isMine = isMine,
                    sender = replySender,
                    body = replyBody,
                    preview = reply.preview,
                    previewPath = reply.previewPath,
                    onClick = onReplyPreviewClick,
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        val maxStickerSize = 200.dp
        val aspectRatio = if ((sticker.width ?: 0) > 0 && (sticker.height ?: 0) > 0) {
            sticker.width!!.toFloat() / sticker.height!!.toFloat()
        } else 1f
        val blurhashImage = rememberBlurHashImage(sticker.blurhash)

        val timestampContent = @Composable {
            MessageTimeAndStatus(
                timestamp = model.timestamp,
                isEdited = model.isEdited,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                isPinned = model.isPinned,
                isMine = isMine
            )
        }

        TimestampLayout(
            position = TimestampPosition.Overlay,
            timestamp = timestampContent,
            timestampPadding = 2.dp
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = maxStickerSize)
                    .aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
                    .blurHashBackground(blurhashImage)
                    .combinedClickable(
                        onClick = {
                            if (previewBlocked) onShowPreview?.invoke() else onOpen?.invoke()
                        },
                        onLongClick = onLongPress,
                        onDoubleClick = onDoubleClick,
                    )
                    .secondaryClick(onSecondaryClick)
            ) {
                if (sticker.thumbPath != null) {
                    val thumbPath = sticker.thumbPath
                    val mime = sticker.mime?.substringBefore(';')?.trim()?.lowercase()
                    when {
                        isTgsSticker(sticker.mime, thumbPath) -> TgsSticker(
                            source = thumbPath,
                            contentDescription = model.body.takeIf { it.isNotBlank() },
                            animate = true,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        mime == "image/webp" || mime == "image/gif" -> SketchAsyncImage(
                            uri = thumbPath,
                            contentDescription = model.body.takeIf { it.isNotBlank() },
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> AsyncImage(
                            model = ImageRequest.Builder(LocalPlatformContext.current)
                                .data(thumbPath)
                                .crossfade(true)
                                .build(),
                            contentDescription = model.body.takeIf { it.isNotBlank() },
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(maxStickerSize)
                            .blurHashBackground(blurhashImage)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (previewBlocked) {
                                Icons.Default.Visibility
                            } else {
                                Icons.Default.EmojiEmotions
                            },
                            contentDescription = stringResource(
                                if (previewBlocked) Res.string.show else Res.string.picker_sticker
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
            }
        }

        if (!model.reactions.isNullOrEmpty()) {
            ReactionChipsRow(
                chips = model.reactions,
                avatarPathsByUserId = model.reactionAvatarsByUserId,
                imagePaths = model.reactionImagePaths,
                shortcodes = model.reactionShortcodes,
                showAvatars = model.showReactionAvatars,
                onClick = onReact,
                modifier = Modifier.offset(y = (-10).dp).padding(horizontal = Spacing.xs)
            )
        }
    }
}

@Composable
private fun ThreadBadge(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val a11yLabel = pluralStringResource(Res.plurals.reply_count, count, count)
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primary,
        shape = if (count > 1) RoundedCornerShape(50) else CircleShape,
        modifier = modifier.semantics { contentDescription = a11yLabel }
    ) {
        if (count > 1) {
            Row(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Gesture,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        } else {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Gesture,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
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
private fun FailedIndicator(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = stringResource(Res.string.failed),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun PinnedIndicator(tint: Color) {
    Icon(
        imageVector = Icons.Default.PushPin,
        contentDescription = stringResource(Res.string.message_pinned),
        tint = tint,
        modifier = Modifier.size(12.dp)
    )
}

@Composable
fun MessageTimeAndStatus(
    timestamp: Long,
    isEdited: Boolean,
    textColor: Color,
    isPinned: Boolean = false,
    isMine: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (isPinned && !isMine) PinnedIndicator(tint = textColor.copy(alpha = 0.7f))
        Text(
            text = formatTime(timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.7f)
        )
        if (isPinned && isMine) PinnedIndicator(tint = textColor.copy(alpha = 0.7f))
        if (isEdited) {
            Text(
                text = stringResource(Res.string.message_edited_suffix),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun RedactedBody(
    text: String,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = color
        )
    }
}

@Composable
fun BubbleWidthWrapper(
    modifier: Modifier = Modifier,
    fractionOfParent: Float = 0.8f,
    leading: @Composable BoxScope.() -> Unit = {},
    trailing: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    Layout(
        content = {
            Box(content = leading)
            Box(content = content)
            Box(content = trailing)
        },
        modifier = modifier
    ) { measurables, constraints ->
        val maxAllowed = (constraints.maxWidth * fractionOfParent).toInt()
        val leadingPlaceable = measurables[0].measure(Constraints())
        val trailingPlaceable = measurables[2].measure(Constraints())
        val sideWidth = leadingPlaceable.width + trailingPlaceable.width
        val childConstraints = constraints.copy(
            minWidth = 0,
            maxWidth = (min(maxAllowed, constraints.maxWidth) - sideWidth).coerceAtLeast(0)
        )
        val mainPlaceable = measurables[1].measure(childConstraints)
        val totalWidth = sideWidth + mainPlaceable.width
        val totalHeight = maxOf(
            mainPlaceable.height,
            leadingPlaceable.height,
            trailingPlaceable.height
        )
        layout(totalWidth, totalHeight) {
            var x = 0
            leadingPlaceable.placeRelative(x, (totalHeight - leadingPlaceable.height) / 2)
            x += leadingPlaceable.width
            mainPlaceable.placeRelative(x, 0)
            x += mainPlaceable.width
            trailingPlaceable.placeRelative(x, (totalHeight - trailingPlaceable.height) / 2)
        }
    }
}
