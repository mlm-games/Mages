package org.mlm.mages.ui.util

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

fun <T : NavKey> NavBackStack<T>.popBack() {
    if (size > 1) {
        removeAt(lastIndex)
    }
}

fun mimeToExtension(mime: String?): String = when (mime) {
    // Office formats
    "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
    "application/vnd.ms-powerpoint" -> "ppt"
    "application/msword" -> "doc"
    "application/vnd.ms-excel" -> "xls"
    // Common formats
    "application/pdf" -> "pdf"
    "application/zip" -> "zip"
    "application/x-rar-compressed" -> "rar"
    "application/x-7z-compressed" -> "7z"
    "text/plain" -> "txt"
    "text/html" -> "html"
    "application/json" -> "json"
    // Images
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/svg+xml" -> "svg"
    // Audio/Video
    "video/mp4" -> "mp4"
    "video/webm" -> "webm"
    "audio/mpeg" -> "mp3"
    "audio/ogg" -> "ogg"
    "audio/wav" -> "wav"
    // Fallback
    else -> mime?.substringAfterLast('/')
        ?.takeIf { it.length in 1..10 && it.all { c -> c.isLetterOrDigit() } }
        ?: "bin"
}
