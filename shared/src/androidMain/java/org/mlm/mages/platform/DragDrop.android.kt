package org.mlm.mages.platform

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import java.io.File

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
actual fun Modifier.fileDrop(
    enabled: Boolean,
    onDragEnter: () -> Unit,
    onDragExit: () -> Unit,
    onDrop: (List<String>) -> Unit
): Modifier = composed {
    if (!enabled) return@composed this

    val context = LocalContext.current

    fun eventHasFiles(event: DragAndDropEvent): Boolean {
        if (event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_URILIST)) return true

        val clip = event.toAndroidDragEvent().clipData ?: return false
        for (i in 0 until clip.itemCount) {
            if (clip.getItemAt(i).uri != null) return true
        }
        return false
    }

    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                if (eventHasFiles(event)) onDragEnter()
            }

            override fun onExited(event: DragAndDropEvent) {
                onDragExit()
            }

            override fun onEnded(event: DragAndDropEvent) {
                onDragExit()
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val dragEvent = event.toAndroidDragEvent()
                val clip = dragEvent.clipData ?: return false

                val outPaths = mutableListOf<String>()

                for (i in 0 until clip.itemCount) {
                    val item = clip.getItemAt(i)

                    val uri = item.uri
                        ?: item.text?.toString()?.let { runCatching { Uri.parse(it) }.getOrNull() }
                        ?: continue

                    // If it's already a file:// URI, we can use it directly.
                    if (uri.scheme == "file") {
                        uri.path?.let(outPaths::add)
                        continue
                    }

                    // For content:// URIs, copy into cache so caller can treat it like a File path.
                    val cached = context.copyUriToCache(uri)
                    if (cached != null) outPaths += cached
                }

                return if (outPaths.isNotEmpty()) {
                    onDrop(outPaths)
                    true
                } else {
                    false
                }
            }
        }
    }

    this.dragAndDropTarget(
        shouldStartDragAndDrop = { startEvent -> eventHasFiles(startEvent) },
        target = target
    )
}

private fun Context.copyUriToCache(uri: Uri): String? {
    return runCatching {
        val name = queryDisplayName(uri)
            ?: uri.lastPathSegment
            ?: "drop_${System.currentTimeMillis()}"

        val safeName = name.replace(Regex("""[^\w\.\-]+"""), "_")
        val dir = File(cacheDir, "dnd").apply { mkdirs() }
        val outFile = File(dir, "${System.currentTimeMillis()}_$safeName")

        contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null

        outFile.absolutePath
    }.getOrNull()
}

private fun Context.queryDisplayName(uri: Uri): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    val c = contentResolver.query(uri, projection, null, null, null) ?: return null
    c.use {
        if (!it.moveToFirst()) return null
        val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx < 0) return null
        return it.getString(idx)
    }
}