package org.mlm.mages

import kotlinx.serialization.Serializable
import org.mlm.mages.matrix.PollData
import org.mlm.mages.matrix.SendState

@Serializable
data class RoomSummary(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val isDm: Boolean = false,
    val isEncrypted: Boolean = false,
)

@Serializable
data class MessageEvent(
    var itemId: String,
    var eventId: String,
    var roomId: String,
    var sender: String,
    var senderDisplayName: String?,
    var senderAvatarUrl: String?,
    var body: String,
    var timestampMs: Long,
    var sendState: SendState?,
    var txnId: String?,
    var replyToEventId: String?,
    var replyToSender: String?,
    var replyToSenderDisplayName: String?,
    var replyToBody: String?,
    var attachment: AttachmentInfo?,
    var threadRootEventId: String?,
    var isEdited: Boolean,
    var pollData: PollData?
)


@Serializable
enum class AttachmentKind { Image, Video, File }

@Serializable
data class EncFile(
    val url: String,
    val json: String
)

@Serializable
data class AttachmentInfo(
    val kind: AttachmentKind,
    val mxcUri: String,
    val mime: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumbnailMxcUri: String? = null,
    val encrypted: EncFile? = null,
    val thumbnailEncrypted: EncFile? = null,
)