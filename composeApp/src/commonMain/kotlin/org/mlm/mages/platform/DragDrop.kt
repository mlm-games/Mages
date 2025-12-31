package org.mlm.mages.platform

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import java.net.URI

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.fileDrop(
    enabled: Boolean,
    onDragEnter: () -> Unit,
    onDragExit: () -> Unit,
    onDrop: (List<String>) -> Unit
): Modifier {
    if (!enabled) return this

    val target = object : DragAndDropTarget {
        override fun onStarted(event: DragAndDropEvent) {
            if (event.dragData() is DragData.FilesList) {
                onDragEnter()
            }
        }

        override fun onEnded(event: DragAndDropEvent) {
            onDragExit()
        }

        override fun onDrop(event: DragAndDropEvent): Boolean {
            val dragData = event.dragData()

            return if (dragData is DragData.FilesList) {
                val files = dragData.readFiles()
                val paths = files.mapNotNull {
                    try {
                        URI(it).path
                    } catch (_: Exception) { null }
                }

                if (paths.isNotEmpty()) {
                    onDrop(paths)
                    true
                } else false
            } else {
                false
            }
        }
    }

    return this.dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            event.dragData() is DragData.FilesList
        },
        target = target
    )
}