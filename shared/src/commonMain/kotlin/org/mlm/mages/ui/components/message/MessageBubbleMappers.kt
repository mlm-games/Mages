package org.mlm.mages.ui.components.message

import org.mlm.mages.AttachmentKind
import org.mlm.mages.MessageEvent
import org.mlm.mages.ui.components.timeline.TimelineContent
import org.mlm.mages.ui.util.formatBytes

private fun MessageEvent.toMediaCaption(): String? {
    val text = body.trim()
    val fileName = attachment?.fileName?.trim()

    if (text.isEmpty()) return null
    if (fileName == null || text == fileName) {
        return null
    }

    return text
}

private fun buildAttachmentSubtitle(mime: String?, sizeBytes: Long?): String? {
    val parts = buildList {
        formatBytes(sizeBytes)?.let { add(it) }
        mime?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

private fun MessageEvent.toAttachmentUi(
    resolvedPreviewPath: String?,
    resolvedAudioPath: String?,
    resolvedAudioWaveform: List<Float>,
): MessageAttachmentUi? {
    val info = attachment ?: return null

    return when (val kind = info.kind) {
        AttachmentKind.File -> MessageAttachmentUi.File(
            fileName = info.fileName,
            mime = info.mime,
            sizeBytes = info.sizeBytes,
            title = info.fileName?.takeIf { it.isNotBlank() }
                ?: body.trim().ifBlank { "File" },
            subtitle = buildAttachmentSubtitle(info.mime, info.sizeBytes),
            caption = toMediaCaption(),
        )
        AttachmentKind.Image -> MessageAttachmentUi.Image(
            previewPath = resolvedPreviewPath ?: info.thumbnailMxcUri,
            width = info.width,
            height = info.height,
            caption = toMediaCaption(),
        )
        AttachmentKind.Video -> MessageAttachmentUi.Video(
            previewPath = resolvedPreviewPath ?: info.thumbnailMxcUri,
            width = info.width,
            height = info.height,
            durationMs = info.durationMs,
            caption = toMediaCaption(),
        )
        AttachmentKind.Audio -> MessageAttachmentUi.Audio(
            filePath = resolvedAudioPath,
            durationMs = info.durationMs,
            waveform = resolvedAudioWaveform.ifEmpty { info.waveform.orEmpty() },
            caption = toMediaCaption(),
            fileName = info.fileName,
            mime = info.mime,
            sizeBytes = info.sizeBytes,
            title = info.fileName?.takeIf { it.isNotBlank() }
                ?: body.trim().ifBlank { "Audio" },
            subtitle = buildAttachmentSubtitle(info.mime, info.sizeBytes),
            // MSC3245 voice marker
            isVoice = info.isVoice == true,
        )
    }
}

internal fun TimelineContent.Bubble.toBubbleModel(
    ctx: MessageBubbleRenderContext
): MessageBubbleModel {
    val event = event
    val stickerData = event.sticker?.let {
        MessageStickerUi(
            thumbPath = ctx.resolvedPreviewPath ?: it.thumbnailMxcUri ?: it.mxcUri,
            width = it.width,
            height = it.height,
            mime = it.mime,
        )
    }
    return MessageBubbleModel(
        eventId = event.eventId,
        isMine = ctx.isMine,
        body = if (stickerData != null) "" else event.body,
        formattedBody = event.formattedBody,
        sender = if (ctx.senderVisible) MessageSenderUi(
            id = event.sender,
            displayName = event.senderDisplayName,
            avatarPath = ctx.avatarPath,
        ) else null,
        timestamp = event.timestampMs,
        isDm = ctx.isDm,
        showMessageAvatars = ctx.showMessageAvatars,
        showUsernameInDms = ctx.showUsernameInDms,
        grouping = MessageGroupingUi(
            groupedWithPrev = ctx.groupedWithPrev,
            groupedWithNext = ctx.groupedWithNext,
        ),
        reactions = ctx.reactions,
        reactionAvatarsByUserId = ctx.reactionAvatarsByUserId,
        showReactionAvatars = ctx.showReactionAvatars,
        reply = MessageReplyUi(
            sender = event.replyToSenderDisplayName,
            body = event.replyToBody,
        ),
        sendState = event.sendState,
        attachment = event.toAttachmentUi(
            resolvedPreviewPath = ctx.resolvedPreviewPath,
            resolvedAudioPath = ctx.resolvedAudioPath,
            resolvedAudioWaveform = ctx.resolvedAudioWaveform,
        ),
        sticker = stickerData,
        isSticker = stickerData != null,
        isEdited = event.isEdited,
        poll = event.pollData,
        thread = ctx.threadCount?.let { count -> MessageThreadUi(count) },
        variant = ctx.variant,
    )
}
