package org.mlm.mages.platform

internal const val MAX_ZIP_IMAGES = 500
internal const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
internal const val MAX_TOTAL_BYTES = 250L * 1024 * 1024

internal fun imageMimeFor(name: String): String? =
    when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "apng" -> "image/apng"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        else -> null
    }

expect suspend fun extractImagePackZip(
    zipPath: String,
    intoDir: String,
): List<Pair<String, String>>?

expect val imagePackZipImportSupported: Boolean
