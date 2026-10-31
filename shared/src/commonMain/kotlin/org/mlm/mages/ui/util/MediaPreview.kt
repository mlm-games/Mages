package org.mlm.mages.ui.util

import org.mlm.mages.AttachmentInfo

const val PREVIEW_THUMBNAIL_PX = 1024

private val ANIMATED_IMAGE_MIMES = setOf("image/gif", "image/webp", "image/apng")

fun AttachmentInfo.isAnimatedImage(): Boolean {
    val normalized = mime?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return normalized in ANIMATED_IMAGE_MIMES
}
