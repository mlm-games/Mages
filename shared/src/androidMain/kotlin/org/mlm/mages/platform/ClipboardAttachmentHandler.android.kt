package org.mlm.mages.platform

import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mlm.mages.ui.components.AttachmentData
import org.mlm.mages.ui.util.guessMimeType
import java.io.File

@Composable
actual fun rememberClipboardAttachmentHandler(): ClipboardAttachmentHandler {
    val context = LocalContext.current
    return remember { AndroidClipboardAttachmentHandler(context) }
}

private class AndroidClipboardAttachmentHandler(
    private val context: Context
) : ClipboardAttachmentHandler {

    private val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager

    override fun hasAttachment(): Boolean {
        val desc = cm?.primaryClipDescription ?: return false
        return (0 until desc.mimeTypeCount).any { i ->
            val mime = desc.getMimeType(i)
            mime.startsWith("image/") ||
            mime.startsWith("video/") ||
            mime.startsWith("audio/") ||
            mime.startsWith("application/") ||
            mime == ClipDescription.MIMETYPE_TEXT_URILIST
        }
    }

    override suspend fun getAttachments(): List<AttachmentData> =
        withContext(Dispatchers.IO) {
            try {
                val clip = cm?.primaryClip ?: return@withContext emptyList()
                (0 until clip.itemCount).mapNotNull { i ->
                    clip.getItemAt(i).uri?.let { processUri(it) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

    private fun processUri(uri: Uri): AttachmentData? {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(resolver, uri)
            ?: "pasted_${System.currentTimeMillis()}"
        val mimeType = resolver.getType(uri)
            ?: guessMimeType(displayName)

        if (mimeType == "text/plain") return null

        val cacheDir = File(context.cacheDir, "clipboard").apply { mkdirs() }
        val safe = displayName.replace(Regex("[^\\w.\\-]"), "_")
        val outFile = File(cacheDir, "${System.currentTimeMillis()}_$safe")

        resolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { input.copyTo(it) }
        } ?: return null

        return AttachmentData(
            path = outFile.absolutePath,
            mimeType = mimeType,
            fileName = displayName,
            sizeBytes = outFile.length()
        )
    }

    private fun queryDisplayName(
        resolver: ContentResolver, uri: Uri
    ): String? = try {
        when (uri.scheme) {
            "file" -> File(uri.path!!).name
            "content" -> resolver.query(uri, null, null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val col = cursor.getColumnIndex(
                            OpenableColumns.DISPLAY_NAME
                        )
                        if (col >= 0) cursor.getString(col) else null
                    } else null
                }
            else -> null
        }
    } catch (_: Exception) { null }
}
