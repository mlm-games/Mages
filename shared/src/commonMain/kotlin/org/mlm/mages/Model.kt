
package org.mlm.mages

import kotlinx.serialization.Serializable
import org.mlm.mages.matrix.EventType
import org.mlm.mages.matrix.PollData
import org.mlm.mages.matrix.ReactionSummary
import org.mlm.mages.matrix.SendState

@Serializable
data class MessageEvent(
    var itemId: String,
    var eventId: String,
    var roomId: String,
    var sender: String,
    var senderDisplayName: String? = null,
    var senderAvatarUrl: String? = null,
    var body: String,
    var formattedBody: String? = null,
    var timestampMs: Long,
    var sendState: SendState? = null,
    var txnId: String? = null,
    var replyToEventId: String? = null,
    var replyToSender: String? = null,
    var replyToSenderDisplayName: String? = null,
    var replyToBody: String? = null,
    var attachment: AttachmentInfo? = null,
    var threadRootEventId: String? = null,
    var isEdited: Boolean = false,
    var pollData: PollData? = null,
    var reactions: List<ReactionSummary> = emptyList(),
    var eventType: EventType = EventType.Message,
    var liveLocation: LiveLocationEvent? = null,
)

@Serializable
data class RoomSummary(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val isDm: Boolean = false,
    val isEncrypted: Boolean = false,
)

@Serializable
data class LiveLocationEvent(
    val userId: String,
    val geoUri: String,
    val tsMs: Long,
    val isLive: Boolean,
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
