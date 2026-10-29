package org.mlm.mages.platform

import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.io.ByteArraySeekableSource
import no.synth.kmpzip.io.IOException
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipFile

actual val imagePackZipImportSupported: Boolean = true

actual suspend fun extractImagePackZip(
    zipPath: String,
    intoDir: String,
): List<Pair<String, String>>? {
    val archive = retrieveWebBlob(zipPath) ?: return null
    clearWebBlob(zipPath)
    val zip = try {
        ZipFile(ByteArraySeekableSource(archive))
    } catch (_: Exception) {
        return null
    }

    val extracted = mutableListOf<Pair<String, String>>()
    var totalBytes = 0L
    try {
        for (entry in zip.entries) {
            if (extracted.size >= MAX_ZIP_IMAGES) break
            if (entry.isDirectory || entry.size <= 0L) continue
            val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
            if (name.startsWith(".")) continue
            val mime = imageMimeFor(name) ?: continue
            if (entry.size > MAX_IMAGE_BYTES) continue
            if (totalBytes + entry.size > MAX_TOTAL_BYTES) break

            val remaining = MAX_TOTAL_BYTES - totalBytes
            if (remaining <= 0L) break

            val bytes = try {
                readEntry(zip, entry, minOf(MAX_IMAGE_BYTES, remaining))
            } catch (_: Exception) {
                continue
            }
            if (bytes.isEmpty()) continue

            totalBytes += bytes.size
            extracted += stageWebBlob(bytes, mime, name) to mime
        }
    } catch (_: Exception) {
        return null
    } finally {
        runCatching { zip.close() }
    }
    return extracted
}

private fun readEntry(zip: ZipFile, entry: ZipEntry, limit: Long): ByteArray {
    val input = zip.getInputStream(entry)
    try {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) throw IOException("zip entry made no progress")
            total += read
            if (total > limit) throw IOException("zip entry exceeds limit")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    } finally {
        input.close()
    }
}
