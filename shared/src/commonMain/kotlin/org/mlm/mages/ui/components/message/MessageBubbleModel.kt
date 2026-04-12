package org.mlm.mages.ui.components.message

import org.mlm.mages.matrix.PollData
import org.mlm.mages.matrix.ReactionSummary
import org.mlm.mages.matrix.SendState

enum class MessageBubbleVariant {
    Timeline,
    ThreadRoot,
    ThreadReply,
}

data class MessageSenderUi(
    val id: String?,
    val displayName: String?,
    val avatarPath: String?,
)

data class MessageGroupingUi(
    val groupedWithPrev: Boolean = false,
    val groupedWithNext: Boolean = false,
)

data class MessageReplyUi(
    val sender: String?,
    val body: String?,
)

sealed interface MessageAttachmentUi {
    data class File(
        val fileName: String?,
        val mime: String?,
        val sizeBytes: Long?,
        val title: String,
        val subtitle: String?,
    ) : MessageAttachmentUi

    data class Image(
        val previewPath: String?,
        val width: Int?,
        val height: Int?,
        val caption: String?,
    ) : MessageAttachmentUi

    data class Video(
        val previewPath: String?,
        val width: Int?,
        val height: Int?,
        val durationMs: Long?,
        val caption: String?,
    ) : MessageAttachmentUi

    data class Audio(
        val filePath: String?,
        val durationMs: Long?,
        val waveform: List<Float>,
    ) : MessageAttachmentUi
}

data class MessageThreadUi(
    val count: Int = 0,
)

data class MessageStickerUi(
    val thumbPath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val mime: String? = null,
)

data class MessageBubbleRenderContext(
    val isMine: Boolean,
    val isDm: Boolean,
    val avatarPath: String?,
    val groupedWithPrev: Boolean,
    val groupedWithNext: Boolean,
    val showMessageAvatars: Boolean = true,
    val showUsernameInDms: Boolean = false,
    val reactions: List<ReactionSummary> = emptyList(),
    val threadCount: Int? = null,
    val variant: MessageBubbleVariant = MessageBubbleVariant.Timeline,
    val resolvedPreviewPath: String? = null,
    val resolvedAudioPath: String? = null,
    val resolvedAudioWaveform: List<Float> = emptyList(),
    val senderVisible: Boolean = true,
)

data class MessageBubbleModel(
    val eventId: String? = null,
    val isMine: Boolean,
    val body: String,
    val formattedBody: String? = null,
    val sender: MessageSenderUi? = null,
    val timestamp: Long,
    val isDm: Boolean,
    val showMessageAvatars: Boolean = true,
    val showUsernameInDms: Boolean = false,
    val grouping: MessageGroupingUi = MessageGroupingUi(),
    val reactions: List<ReactionSummary> = emptyList(),
    val reply: MessageReplyUi? = null,
    val sendState: SendState? = null,
    val attachment: MessageAttachmentUi? = null,
    val sticker: MessageStickerUi? = null,
    val isSticker: Boolean = false,
    val isEdited: Boolean = false,
    val poll: PollData? = null,
    val thread: MessageThreadUi? = null,
    val variant: MessageBubbleVariant = MessageBubbleVariant.Timeline,
)
