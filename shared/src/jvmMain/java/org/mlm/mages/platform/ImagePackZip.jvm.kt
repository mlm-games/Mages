package org.mlm.mages.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

private const val MAX_ZIP_IMAGES = 500

actual val imagePackZipImportSupported: Boolean = true

actual suspend fun extractImagePackZip(
    zipPath: String,
    intoDir: String,
): List<Pair<String, String>> = withContext(Dispatchers.IO) {
    val source = File(zipPath)
    if (!source.isFile) return@withContext emptyList()

    val targetDir = File(intoDir, "image_pack_${System.currentTimeMillis()}").apply { mkdirs() }
    val extracted = mutableListOf<Pair<String, String>>()

    runCatching {
        ZipFile(source).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements() && extracted.size < MAX_ZIP_IMAGES) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                if (name.startsWith(".")) continue
                val mime = imageMimeFor(name) ?: continue

                val destination = File(targetDir, "${extracted.size}/$name")
                destination.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                extracted += destination.absolutePath to mime
            }
        }
    }

    extracted
}

private fun imageMimeFor(name: String): String? =
    when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "apng" -> "image/apng"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        else -> null
    }
