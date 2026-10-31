package org.mlm.mages.ui.util

import org.mlm.mages.AttachmentInfo
import org.mlm.mages.MatrixService

const val PREVIEW_THUMBNAIL_PX = 1024

private const val MAX_ORIGINAL_PREVIEW_BYTES = 16L * 1024 * 1024

private val ANIMATED_IMAGE_MIMES = setOf("image/gif", "image/webp", "image/apng")

fun AttachmentInfo.isAnimatedImage(): Boolean {
    val normalized = mime?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return normalized in ANIMATED_IMAGE_MIMES
}

suspend fun MatrixService.previewThumbnail(attachment: AttachmentInfo): Result<String> {
    if (attachment.isAnimatedImage()) return port.downloadAttachmentToCache(attachment)
    val thumbnail = thumbnailToCache(attachment, PREVIEW_THUMBNAIL_PX, PREVIEW_THUMBNAIL_PX, crop = false)
    if (thumbnail.isSuccess) return thumbnail
    val sizeBytes = attachment.sizeBytes ?: return thumbnail
    if (sizeBytes > MAX_ORIGINAL_PREVIEW_BYTES) return thumbnail
    return port.downloadAttachmentToCache(attachment)
}
