package org.mlm.mages.platform

import androidx.compose.runtime.Composable

data class ShareContent(
    val text: String? = null,
    val subject: String? = null,

    // TODO? Backwards-compatible single-file (remove later)
    val filePath: String? = null,
    val mimeType: String? = null,

    val filePaths: List<String> = emptyList(),
    val mimeTypes: List<String?> = emptyList(),
) {
    /** Normalized list of files, regardless of whether caller used filePath or filePaths. */
    val allFilePaths: List<String>
        get() = when {
            filePaths.isNotEmpty() -> filePaths
            !filePath.isNullOrBlank() -> listOf(filePath)
            else -> emptyList()
        }

    /** Best-effort MIME type for intent. If mixed/unknown, return "* / *". */
    val effectiveMimeType: String
        get() {
            val types = when {
                mimeTypes.isNotEmpty() -> mimeTypes
                mimeType != null -> listOf(mimeType)
                else -> emptyList()
            }.filterNotNull().map { it.trim() }.filter { it.isNotBlank() }.distinct()

            return when {
                types.isEmpty() -> "*/*"
                types.size == 1 -> types.first()
                else -> "*/*"
            }
        }
}

@Composable
expect fun rememberShareHandler(): (ShareContent) -> Unit