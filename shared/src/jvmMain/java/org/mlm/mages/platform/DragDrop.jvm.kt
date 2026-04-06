package org.mlm.mages.platform

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import org.mlm.mages.content.TransferItem
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.net.URI

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
actual fun Modifier.fileDrop(
    enabled: Boolean,
    onDragEnter: () -> Unit,
    onDragExit: () -> Unit,
    onDrop: (List<TransferItem>) -> Unit
): Modifier = composed {
    if (!enabled) return@composed this

    fun eventHasFiles(event: DragAndDropEvent): Boolean {
        val t = event.awtTransferable
        return t.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                t.isDataFlavorSupported(DataFlavor.stringFlavor)
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
                val t = event.awtTransferable
                val attachments = mutableListOf<TransferItem>()

                // Best case: OS provides actual File list
                if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    val raw = t.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: return false
                    val files = raw.filterIsInstance<File>()
                    if (files.isNotEmpty()) {
                        files.forEach { file ->
                            attachments.add(file.toTransferItem())
                        }
                        onDrop(attachments)
                        return true
                    }
                }

                // Fallback: sometimes files come as a string (URI list)
                if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    val s = (t.getTransferData(DataFlavor.stringFlavor) as? String).orEmpty()
                    val paths = s
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .mapNotNull { line ->
                            // Common formats: file:///... or plain path
                            runCatching {
                                if (line.startsWith("file:")) URI(line).path else line
                            }.getOrNull()
                        }
                        .toList()

                    if (paths.isNotEmpty()) {
                        paths.forEach { path ->
                            val file = File(path)
                            if (file.exists()) {
                                attachments.add(file.toTransferItem())
                            }
                        }
                        if (attachments.isNotEmpty()) {
                            onDrop(attachments)
                            return true
                        }
                    }
                }

                return false
            }
        }
    }

    this.dragAndDropTarget(
        shouldStartDragAndDrop = { startEvent -> eventHasFiles(startEvent) },
        target = target
    )
}

private fun File.toTransferItem(): TransferItem {
    return TransferItem(
        fileName = name,
        path = absolutePath,
        mimeType = "application/octet-stream",
        sizeBytes = length(),
    )
}