package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.browser.document
import org.mlm.mages.ui.components.AttachmentData
import org.w3c.dom.events.Event
import org.w3c.files.File

@Composable
actual fun rememberClipboardAttachmentHandler(): ClipboardAttachmentHandler {
    val handler = remember { WebClipboardAttachmentHandler() }

    DisposableEffect(handler) {
        handler.attach()
        onDispose { handler.detach() }
    }

    return handler
}

private class WebClipboardAttachmentHandler : ClipboardAttachmentHandler {
    private var lastFiles: List<File> = emptyList()

    private val pasteHandler: (Event) -> Unit = { event ->
        lastFiles = extractFilesFromDataTransfer(clipboardDataOf(event))
    }

    fun attach() {
        document.addEventListener("paste", pasteHandler)
    }

    fun detach() {
        document.removeEventListener("paste", pasteHandler)
        lastFiles = emptyList()
    }

    override fun hasAttachment(): Boolean = lastFiles.isNotEmpty()

    override suspend fun getAttachments(): List<AttachmentData> {
        val files = lastFiles
        lastFiles = emptyList()

        val out = mutableListOf<AttachmentData>()
        for (file in files) {
            try {
                out += browserFileToAttachmentData(file)
            } catch (_: Throwable) {
            }
        }
        return out
    }
}
