package org.mlm.mages.ui.components

import org.mlm.mages.content.TransferItem

fun TransferItem.toMagesAttachment(
    webSourceKind: AttachmentSourceKind,
): AttachmentData {
    val isWebBlobToken = webObjectUrl?.startsWith("webblob:") == true
    val resolvedPath = if (isWebBlobToken) {
        requireNotNull(localPath)
    } else {
        openablePathOrUrl
    }
    val resolvedSourceKind = when {
        isWebBlobToken -> AttachmentSourceKind.WebBlobToken
        webObjectUrl != null -> webSourceKind
        else -> AttachmentSourceKind.LocalPath
    }

    return AttachmentData(
        path = resolvedPath,
        mimeType = mimeType ?: "application/octet-stream",
        fileName = fileName,
        sizeBytes = sizeBytes ?: 0L,
        sourceKind = resolvedSourceKind,
    )
}

fun AttachmentData.toTransferItem(): TransferItem {
    return TransferItem(
        fileName = fileName,
        path = path,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
    )
}
