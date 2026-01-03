package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

@Composable
actual fun rememberShareHandler(): (ShareContent) -> Unit {
    return remember {
        { content ->
            try {
                val files = content.allFilePaths.map { File(it) }.filter { it.exists() && it.canRead() }

                when {
                    files.isNotEmpty() -> {
                        // Open containing folder of the first file (simple, predictable)
                        val parent = files.first().parentFile
                        if (parent != null && Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().open(parent)
                        }

                        // Also copy paths to clipboard as a convenience for multi-file cases
                        if (files.size > 1) {
                            val text = files.joinToString("\n") { it.absolutePath }
                            val selection = StringSelection(text)
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                        }
                    }

                    content.text != null -> {
                        val selection = StringSelection(content.text)
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(selection, selection)
                    }
                }
            } catch (_: Throwable) {
                // Ignore
            }
        }
    }
}