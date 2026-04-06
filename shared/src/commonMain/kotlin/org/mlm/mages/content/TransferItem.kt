package org.mlm.mages.content

data class TransferItem(
    val fileName: String,
    val path: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val webObjectUrl: String? = null,
) {
    init {
        require(path != null || webObjectUrl != null) {
            "TransferItem needs either a local path or a web object URL"
        }
    }

    val localPath: String?
        get() = path?.takeIf { it.isNotBlank() }

    val openablePathOrUrl: String
        get() = webObjectUrl ?: requireNotNull(localPath)
}
