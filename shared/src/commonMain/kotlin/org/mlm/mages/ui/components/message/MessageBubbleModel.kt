package org.mlm.mages.ui.components.message

import org.mlm.mages.LinkPreview
import org.mlm.mages.ReplyPreview
import org.mlm.mages.matrix.PollData
import org.mlm.mages.matrix.ReactionSummary
import org.mlm.mages.matrix.SendState
import org.mlm.mages.ui.components.core.MentionRef

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
    val preview: ReplyPreview? = null,
    val previewPath: String? = null,
)

sealed interface MessageAttachmentUi {
    data class File(
        val fileName: String?,
        val mime: String?,
        val sizeBytes: Long?,
        val title: String,
        val subtitle: String?,
        val caption: String? = null,
        val captionFormattedBody: String? = null,
    ) : MessageAttachmentUi

    data class Image(
        val previewPath: String?,
        val width: Int?,
        val height: Int?,
        val caption: String?,
        val captionFormattedBody: String? = null,
        val blurhash: String? = null,
    ) : MessageAttachmentUi

    data class Video(
        val previewPath: String?,
        val width: Int?,
        val height: Int?,
        val durationMs: Long?,
        val caption: String?,
        val captionFormattedBody: String? = null,
        val blurhash: String? = null,
    ) : MessageAttachmentUi

    data class Audio(
        val filePath: String?,
        val durationMs: Long?,
        val waveform: List<Float>,
        val caption: String? = null,
        val captionFormattedBody: String? = null,
        val fileName: String? = null,
        val mime: String? = null,
        val sizeBytes: Long? = null,
        val title: String,
        val subtitle: String? = null,
        val isVoice: Boolean = false,
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
    val blurhash: String? = null,
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
    val reactionAvatarsByUserId: Map<String, String> = emptyMap(),
    val showReactionAvatars: Boolean = false,
    /** Resolved MSC4027 image reactions, keyed by their mxc reaction key. */
    val reactionImagePaths: Map<String, String> = emptyMap(),
    /** Shortcodes for MSC4027 image reactions, where the key is a known pack image. */
    val reactionShortcodes: Map<String, String> = emptyMap(),
    val threadCount: Int? = null,
    val variant: MessageBubbleVariant = MessageBubbleVariant.Timeline,
    val resolvedPreviewPath: String? = null,
    val resolvedReplyPreviewPath: String? = null,
    val resolvedAudioPath: String? = null,
    val resolvedAudioWaveform: List<Float> = emptyList(),
    val resolvedLinkPreview: LinkPreview? = null,
    val resolvedLinkPreviewImage: String? = null,
    val resolvedMentions: Map<String, MentionRef> = emptyMap(),
    val senderVisible: Boolean = true,
    val isPinned: Boolean = false,
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
    val reactionAvatarsByUserId: Map<String, String> = emptyMap(),
    val showReactionAvatars: Boolean = false,
    val reactionImagePaths: Map<String, String> = emptyMap(),
    val reactionShortcodes: Map<String, String> = emptyMap(),
    val reply: MessageReplyUi? = null,
    val sendState: SendState? = null,
    val attachment: MessageAttachmentUi? = null,
    val sticker: MessageStickerUi? = null,
    val isSticker: Boolean = false,
    val isEdited: Boolean = false,
    val isPinned: Boolean = false,
    val isRedacted: Boolean = false,
    val poll: PollData? = null,
    val thread: MessageThreadUi? = null,
    val linkPreview: LinkPreview? = null,
    val linkPreviewImage: String? = null,
    val variant: MessageBubbleVariant = MessageBubbleVariant.Timeline,
    val mentions: Map<String, MentionRef> = emptyMap(),
    val mentionsRoom: Boolean = false,
)
