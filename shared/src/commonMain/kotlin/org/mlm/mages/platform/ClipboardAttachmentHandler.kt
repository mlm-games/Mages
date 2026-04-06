package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import org.mlm.mages.content.TransferItem

interface ClipboardAttachmentHandler {
    fun hasAttachment(): Boolean
    suspend fun getAttachments(): List<TransferItem>
}

@Composable
expect fun rememberClipboardAttachmentHandler(): ClipboardAttachmentHandler
