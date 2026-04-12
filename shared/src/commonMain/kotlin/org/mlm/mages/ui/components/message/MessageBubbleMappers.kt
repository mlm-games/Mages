package org.mlm.mages.ui.components.message

import org.mlm.mages.AttachmentKind
import org.mlm.mages.MessageEvent
import org.mlm.mages.ui.util.formatBytes

private fun String.looksLikeFileName(): Boolean {
    val value = trim()
    return value.matches(Regex(""".+\.[A-Za-z0-9]{2,5}$""")) ||
        value.matches(Regex("""(?i)(img|vid|pxl|dsc|screenshot)[-_ ]?\d+.*"""))
}

private fun String.normalizedAttachmentLabel(): String =
    trim()
        .lowercase()
        .substringBeforeLast('.', this)
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(Regex("\\s+"), " ")

private fun MessageEvent.toMediaCaption(): String? {
    val text = body.trim()
    val fileName = attachment?.fileName?.trim()

    if (text.isEmpty()) return null
    if (!fileName.isNullOrBlank() && text.normalizedAttachmentLabel() == fileName.normalizedAttachmentLabel()) {
        return null
    }
    if (text.looksLikeFileName()) return null

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
        )
    }
}

fun MessageEvent.toBubbleModel(
    ctx: MessageBubbleRenderContext
): MessageBubbleModel {
    val stickerData = sticker?.let {
        MessageStickerUi(
            thumbPath = ctx.resolvedPreviewPath ?: it.thumbnailMxcUri ?: it.mxcUri,
            width = it.width,
            height = it.height,
            mime = it.mime,
        )
    }
    return MessageBubbleModel(
        eventId = eventId,
        isMine = ctx.isMine,
        body = if (stickerData != null) "" else body,
        formattedBody = formattedBody,
        sender = if (ctx.senderVisible) MessageSenderUi(
            id = sender,
            displayName = senderDisplayName,
            avatarPath = ctx.avatarPath,
        ) else null,
        timestamp = timestampMs,
        isDm = ctx.isDm,
        showMessageAvatars = ctx.showMessageAvatars,
        showUsernameInDms = ctx.showUsernameInDms,
        grouping = MessageGroupingUi(
            groupedWithPrev = ctx.groupedWithPrev,
            groupedWithNext = ctx.groupedWithNext,
        ),
        reactions = ctx.reactions,
        reply = MessageReplyUi(
            sender = replyToSenderDisplayName,
            body = replyToBody,
        ),
        sendState = sendState,
        attachment = toAttachmentUi(
            resolvedPreviewPath = ctx.resolvedPreviewPath,
            resolvedAudioPath = ctx.resolvedAudioPath,
            resolvedAudioWaveform = ctx.resolvedAudioWaveform,
        ),
        sticker = stickerData,
        isSticker = stickerData != null,
        isEdited = isEdited,
        poll = pollData,
        thread = ctx.threadCount?.let { count -> MessageThreadUi(count) },
        variant = ctx.variant,
    )
}
