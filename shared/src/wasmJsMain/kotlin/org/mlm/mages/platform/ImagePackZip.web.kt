package org.mlm.mages.platform

actual val imagePackZipImportSupported: Boolean = false

actual suspend fun extractImagePackZip(
    zipPath: String,
    intoDir: String,
): List<Pair<String, String>> = emptyList()
