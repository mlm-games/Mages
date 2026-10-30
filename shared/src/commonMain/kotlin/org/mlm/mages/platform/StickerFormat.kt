package org.mlm.mages.platform

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal const val MaxTgsCompressedBytes = 8 * 1024 * 1024

internal fun isTgsMime(mime: String?): Boolean {
    val normalized = mime?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return normalized == "application/x-tgsticker" ||
        normalized == "application/tgs" ||
        normalized == "image/tgs"
}

internal fun isTgsSticker(mime: String?, source: String? = null): Boolean {
    if (isTgsMime(mime)) return true
    val value = source ?: return false
    if (
        value.startsWith("data:application/x-tgsticker", ignoreCase = true) ||
        value.startsWith("data:application/tgs", ignoreCase = true) ||
        value.startsWith("data:image/tgs", ignoreCase = true)
    ) return true
    return value.substringBefore('?').substringBefore('#').endsWith(".tgs", ignoreCase = true)
}

internal expect suspend fun readTgsBytes(source: String): ByteArray?

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeBase64DataUri(source: String): ByteArray? {
    if (!source.startsWith("data:", ignoreCase = true)) return null
    val comma = source.indexOf(',')
    if (comma < 0) return null
    if (!source.substring(5, comma).endsWith(";base64", ignoreCase = true)) return null
    val payload = source.substring(comma + 1).filterNot { it.isWhitespace() }
    if (payload.length.toLong() * 3 / 4 > MaxTgsCompressedBytes.toLong() + 4) return null
    return runCatching { Base64.decode(payload) }
        .getOrNull()
        ?.takeIf { it.size <= MaxTgsCompressedBytes }
}
