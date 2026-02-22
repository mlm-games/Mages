package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mlm.mages.ui.components.AttachmentData
import org.mlm.mages.ui.util.guessMimeType
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@Composable
actual fun rememberClipboardAttachmentHandler(): ClipboardAttachmentHandler {
    return remember { JvmClipboardAttachmentHandler() }
}

private class JvmClipboardAttachmentHandler : ClipboardAttachmentHandler {

    private val clipboard = Toolkit.getDefaultToolkit().systemClipboard

    override fun hasAttachment(): Boolean = try {
        val c = clipboard.getContents(null) ?: return false
        c.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
        c.isDataFlavorSupported(DataFlavor.imageFlavor)
    } catch (_: Exception) { false }

    override suspend fun getAttachments(): List<AttachmentData> =
        withContext(Dispatchers.IO) {
            try {
                val contents = clipboard.getContents(null) ?: return@withContext emptyList()

                if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @Suppress("UNCHECKED_CAST")
                    val files = contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                    return@withContext files
                        ?.filter { it.exists() }
                        ?.map { it.toAttachment() }
                        ?: emptyList()
                }

                if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                    val img = contents.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image
                    return@withContext listOfNotNull(img?.saveToTemp())
                }

                emptyList()
            } catch (_: Exception) { emptyList() }
        }

    private fun File.toAttachment() = AttachmentData(
        path = absolutePath,
        mimeType = guessMimeType(name),
        fileName = name,
        sizeBytes = length()
    )

    private fun java.awt.Image.saveToTemp(): AttachmentData? {
        val bi = when (this) {
            is BufferedImage -> this
            else -> {
                val w = getWidth(null)
                val h = getHeight(null)
                if (w <= 0 || h <= 0) return null
                BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also {
                    it.createGraphics().apply {
                        drawImage(this@saveToTemp, 0, 0, null)
                        dispose()
                    }
                }
            }
        }
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "mages_clipboard"
        ).apply { mkdirs() }
        val f = File(dir, "clipboard_${System.currentTimeMillis()}.png")
        ImageIO.write(bi, "png", f)
        return AttachmentData(
            path = f.absolutePath,
            mimeType = "image/png",
            fileName = "clipboard_image.png",
            sizeBytes = f.length()
        )
    }
}
