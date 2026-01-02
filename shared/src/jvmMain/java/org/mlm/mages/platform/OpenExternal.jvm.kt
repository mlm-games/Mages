package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import java.awt.Desktop
import java.io.File

@Composable
actual fun rememberFileOpener(): (String, String?) -> Boolean {
    return { path, _mime ->
        val file = File(path)

        if (!file.exists()) {
            false
        } else {
            val desktopOpened = runCatching {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(file)
                    true
                } else {
                    false
                }
            }.getOrElse { e ->
                e.printStackTrace()
                false
            }

            if (desktopOpened) {
                true
            } else {
                // Fallback
                val os = System.getProperty("os.name").lowercase()
                val cmd = when {
                    os.contains("mac") -> arrayOf("open", file.absolutePath)
                    os.contains("win") -> arrayOf("cmd", "/c", "start", "", file.absolutePath)
                    else -> arrayOf("xdg-open", file.absolutePath)
                }
                runCatching {
                    ProcessBuilder(*cmd).start()
                    true
                }.getOrElse { e ->
                    e.printStackTrace()
                    false
                }
            }
        }
    }
}