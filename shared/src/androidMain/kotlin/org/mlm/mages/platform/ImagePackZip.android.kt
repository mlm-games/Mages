package org.mlm.mages.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipFile

actual val imagePackZipImportSupported: Boolean = true

actual suspend fun extractImagePackZip(
    zipPath: String,
    intoDir: String,
): List<Pair<String, String>>? = withContext(Dispatchers.IO) {
    val source = File(zipPath)
    if (!source.isFile) return@withContext null

    val targetDir = File(intoDir, "image_pack_${UUID.randomUUID()}").apply { mkdirs() }
    if (!targetDir.isDirectory) return@withContext null

    val extracted = mutableListOf<Pair<String, String>>()
    var totalBytes = 0L

    try {
        ZipFile(source).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements() && extracted.size < MAX_ZIP_IMAGES) {
                val entry = entries.nextElement()
                if (entry.isDirectory || entry.size <= 0L) continue
                val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                if (name.startsWith(".")) continue
                val mime = imageMimeFor(name) ?: continue
                if (entry.size > MAX_IMAGE_BYTES) continue
                if (totalBytes + entry.size > MAX_TOTAL_BYTES) break

                val remaining = MAX_TOTAL_BYTES - totalBytes
                if (remaining <= 0L) break

                val destination = File(targetDir, "${extracted.size}/$name")
                destination.parentFile?.mkdirs()
                val written = try {
                    zip.getInputStream(entry).use { input ->
                        destination.outputStream().use { output ->
                            copyBounded(input, output, minOf(MAX_IMAGE_BYTES, remaining))
                        }
                    }
                } catch (_: IOException) {
                    destination.delete()
                    continue
                }
                if (written <= 0L) {
                    destination.delete()
                    continue
                }
                totalBytes += written
                extracted += destination.absolutePath to mime
            }
        }
    } catch (_: IOException) {
        targetDir.deleteRecursively()
        return@withContext null
    }

    if (extracted.isEmpty()) targetDir.deleteRecursively()
    extracted
}

private fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw IOException("zip entry exceeds limit")
        output.write(buffer, 0, read)
    }
    return total
}
