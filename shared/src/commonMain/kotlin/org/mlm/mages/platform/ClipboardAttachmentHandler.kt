package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import org.mlm.mages.ui.components.AttachmentData

interface ClipboardAttachmentHandler {
    fun hasAttachment(): Boolean
    suspend fun getAttachments(): List<AttachmentData>
}

@Composable
expect fun rememberClipboardAttachmentHandler(): ClipboardAttachmentHandler
