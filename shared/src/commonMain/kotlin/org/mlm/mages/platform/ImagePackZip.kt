package org.mlm.mages.platform

expect suspend fun extractImagePackZip(
    zipPath: String,
    intoDir: String,
): List<Pair<String, String>>?

expect val imagePackZipImportSupported: Boolean
