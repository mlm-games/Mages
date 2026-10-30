package org.mlm.mages.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal actual suspend fun readTgsBytes(source: String): ByteArray? = withContext(Dispatchers.IO) {
    if (source.startsWith("data:", ignoreCase = true)) {
        return@withContext decodeBase64DataUri(source)
    }
    val path = source.removePrefix("file://")
    val file = File(path)
    if (!file.isFile || file.length() > MaxTgsCompressedBytes.toLong()) return@withContext null
    runCatching { file.readBytes() }
        .getOrNull()
        ?.takeIf { it.size <= MaxTgsCompressedBytes }
}
