package org.mlm.mages.ui.util

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.mlm.mages.MessageEvent
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

fun downloadNameHint(event: MessageEvent, declared: String?, mime: String?, fallbackBase: String): String =
    declared?.takeIf { it.isNotBlank() }
        ?: event.body.trim().takeIf { it.isNotBlank() && !it.startsWith("mxc://") && !it.contains('\n') && it.length < 256 }
        ?: "${event.eventId.ifBlank { fallbackBase }}.${mimeToExtension(mime)}"

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
    "application/x-tgsticker" -> "tgs"
    "application/tgs" -> "tgs"
    "image/tgs" -> "tgs"
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
    val name = fileName.lowercase()
    return when {
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".gif") -> "image/gif"
        name.endsWith(".webp") -> "image/webp"
        name.endsWith(".bmp") -> "image/bmp"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".tgs") -> "application/x-tgsticker"
        name.endsWith(".mp4") || name.endsWith(".m4v") -> "video/mp4"
        name.endsWith(".webm") -> "video/webm"
        name.endsWith(".mov") -> "video/quicktime"
        name.endsWith(".mkv") -> "video/x-matroska"
        name.endsWith(".ogv") -> "video/ogg"
        name.endsWith(".avi") -> "video/x-msvideo"
        name.endsWith(".3gp") -> "video/3gpp"
        name.endsWith(".mp3") -> "audio/mpeg"
        name.endsWith(".m4a") -> "audio/mp4"
        name.endsWith(".aac") -> "audio/aac"
        name.endsWith(".ogg") || name.endsWith(".oga") || name.endsWith(".opus") -> "audio/ogg"
        name.endsWith(".flac") -> "audio/flac"
        name.endsWith(".wav") -> "audio/wav"
        name.endsWith(".weba") -> "audio/webm"
        name.endsWith(".aif") || name.endsWith(".aiff") -> "audio/aiff"
        name.endsWith(".pdf") -> "application/pdf"
        name.endsWith(".doc") || name.endsWith(".docx") -> "application/msword"
        name.endsWith(".txt") -> "text/plain"
        name.endsWith(".zip") -> "application/zip"
        else -> "application/octet-stream"
    }
}

/** Percent-decodes a URI component, UTF-8 aware. '+' is left alone. */
fun decodeUrl(s: String): String {
    if ('%' !in s) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%') {
            val bytes = mutableListOf<Byte>()
            var j = i
            while (j < s.length && s[j] == '%' && j + 2 <= s.length - 1) {
                val b = s.substring(j + 1, j + 3).toIntOrNull(16)
                if (b == null) break
                bytes += b.toByte()
                j += 3
            }
            if (bytes.isNotEmpty()) {
                out.append(bytes.toByteArray().decodeToString())
                i = j
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

@OptIn(ExperimentalTime::class)
fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
