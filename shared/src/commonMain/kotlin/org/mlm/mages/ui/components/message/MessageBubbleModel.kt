package org.mlm.mages.ui.components.message

import org.mlm.mages.AttachmentKind
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

data class MessageAttachmentUi(
    val thumbPath: String? = null,
    val kind: AttachmentKind? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)

data class MessageThreadUi(
    val count: Int = 0,
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
    val isEdited: Boolean = false,
    val poll: PollData? = null,
    val thread: MessageThreadUi? = null,
    val variant: MessageBubbleVariant = MessageBubbleVariant.Timeline,
)
