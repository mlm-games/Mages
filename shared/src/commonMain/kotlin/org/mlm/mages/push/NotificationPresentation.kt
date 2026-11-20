package org.mlm.mages.push

import org.mlm.mages.AttachmentInfo
import org.mlm.mages.AttachmentKind
import org.mlm.mages.StickerInfo
import org.mlm.mages.captionOr
import org.mlm.mages.matrix.ClassifiedNotification
import org.mlm.mages.matrix.RenderedNotification
import org.mlm.mages.matrix.classify
import org.mlm.mages.matrix.notificationSummary

const val NOTIFICATION_NAME_MAX_CHARS = 40

fun truncateNotificationName(name: String): String =
    if (name.length <= NOTIFICATION_NAME_MAX_CHARS) {
        name
    } else {
        name.take(NOTIFICATION_NAME_MAX_CHARS - 1) + "…"
    }

data class NotificationPresentation(
    val title: String,
    val body: String,
    /** Used instead of [body] when a preview image is actually attached. */
    val bodyWithMedia: String,
    val media: NotificationMedia?,
    val accent: Accent
) {
    enum class Accent { Neutral, Mention, Call, Invite }

    companion object {
        private const val BODY_MAX_CHARS = 240

        suspend fun of(
            notification: RenderedNotification,
            showPreview: Boolean,
            redactedBody: String,
            /** True when the platform labels the message with its sender itself. */
            senderShownByPlatform: Boolean = false
        ): NotificationPresentation {
            val content = notification.content.classify()
            val sender = truncateNotificationName(notification.sender)
            val roomName = truncateNotificationName(notification.roomName)

            val title = when (content) {
                is ClassifiedNotification.Call, ClassifiedNotification.Invite -> sender
                else ->
                    if (notification.isDm || notification.sender == notification.roomName) sender else roomName
            }

            val accent = when {
                content is ClassifiedNotification.Call -> Accent.Call
                content is ClassifiedNotification.Invite -> Accent.Invite
                notification.hasMention -> Accent.Mention
                else -> Accent.Neutral
            }

            // The sender is only worth naming once: the title already is the sender
            // in a DM, a reaction and a call name their subject on their own, and a
            // platform that labels the line carries it without help.
            val prefixSender = !senderShownByPlatform &&
                title != sender &&
                content !is ClassifiedNotification.Reaction &&
                content !is ClassifiedNotification.Call &&
                content !is ClassifiedNotification.Invite

            val body = when {
                !showPreview -> redactedBody
                prefixSender -> "$sender: ${notificationSummary(content)}".take(BODY_MAX_CHARS)
                else -> notificationSummary(content).take(BODY_MAX_CHARS)
            }

            // A preview image stands in for the "Sent an image" label, so the
            // label is only worth showing when there is no image. Whatever text
            // the sender actually wrote is the caption and survives either way.
            val caption = (content as? ClassifiedNotification.Media)
                ?.let { it.attachment.captionOr(it.body) }
                .orEmpty()
            val bodyWithMedia = when {
                !showPreview -> redactedBody
                caption.isEmpty() -> ""
                prefixSender -> "$sender: $caption".take(BODY_MAX_CHARS)
                else -> caption.take(BODY_MAX_CHARS)
            }

            return NotificationPresentation(title, body, bodyWithMedia, mediaOf(content), accent)
        }

        private fun mediaOf(content: ClassifiedNotification): NotificationMedia? {
            val attachment = when (content) {
                is ClassifiedNotification.Media -> content.attachment
                is ClassifiedNotification.Sticker -> content.sticker.asAttachment()
                else -> return null
            }

            val previewable = when (attachment.kind) {
                AttachmentKind.Image, AttachmentKind.Video -> true
                AttachmentKind.Audio -> attachment.isVoice != true
                AttachmentKind.File -> false
            }
            if (!previewable) return null

            return NotificationMedia(attachment)
        }

        private fun StickerInfo.asAttachment() = AttachmentInfo(
            kind = AttachmentKind.Image,
            mxcUri = mxcUri,
            mime = mime,
            sizeBytes = sizeBytes,
            width = width,
            height = height,
            thumbnailMxcUri = thumbnailMxcUri,
            encrypted = encrypted,
            thumbnailEncrypted = thumbnailEncrypted
        )
    }
}

/**
 * The full attachment, not just an mxc URI: in an encrypted room the URI alone
 * is undecryptable and the fetch fails, so the encryption keys travel with it.
 */
data class NotificationMedia(val attachment: AttachmentInfo)
