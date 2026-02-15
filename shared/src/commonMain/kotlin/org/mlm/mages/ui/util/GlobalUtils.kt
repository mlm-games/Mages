package org.mlm.mages.ui.util

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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


fun guessMimeType(fileName: String): String {
    return when {
        fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        fileName.endsWith(".png", ignoreCase = true) -> "image/png"
        fileName.endsWith(".gif", ignoreCase = true) -> "image/gif"
        fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
        fileName.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
        fileName.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
        fileName.endsWith(".webm", ignoreCase = true) -> "video/webm"
        fileName.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
        fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
        fileName.endsWith(".doc", ignoreCase = true) || fileName.endsWith(".docx", ignoreCase = true) -> "application/msword"
        fileName.endsWith(".txt", ignoreCase = true) -> "text/plain"
        fileName.endsWith(".zip", ignoreCase = true) -> "application/zip"
        else -> "application/octet-stream"
    }
}


@OptIn(ExperimentalTime::class)
fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
