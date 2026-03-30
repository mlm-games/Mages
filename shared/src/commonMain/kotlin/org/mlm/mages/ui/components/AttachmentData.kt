package org.mlm.mages.ui.components

import kotlinx.serialization.Serializable

@Serializable
enum class OutgoingMediaMode {
    Attachment,
    Sticker,
}

@Serializable
data class AttachmentData(
    val path: String,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long,
    val sourceKind: AttachmentSourceKind = AttachmentSourceKind.LocalPath,
    val mode: OutgoingMediaMode = OutgoingMediaMode.Attachment,
    val altText: String = fileName,
)

enum class AttachmentSourceKind {
    LocalPath,
    WebObjectUrl,
    WebBlobToken,
}