package org.mlm.mages.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile
import org.mlm.mages.ui.components.AttachmentData
import org.mlm.mages.ui.util.guessMimeType
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

actual fun getDeviceDisplayName(): String {
    val os = System.getProperty("os.name") ?: "Desktop"
    val hostname = runCatching {
        InetAddress.getLocalHost().hostName
    }.getOrNull()

    return if (hostname != null) {
        "Mages ($os - $hostname)"
    } else {
        "Mages ($os)"
    }
}


actual fun deleteDirectory(path: String): Boolean {
    return File(path).deleteRecursively()
}

@Composable
actual fun getDynamicColorScheme(
    darkTheme: Boolean,
    useDynamicColors: Boolean
): ColorScheme? {return null}

actual fun platformEmbeddedElementCallUrlOrNull(): String? = ElementCallLocalServer.indexUrl()

actual fun platformEmbeddedElementCallParentUrlOrNull(): String? = ElementCallLocalServer.parentUrl()

actual class CameraPickerLauncher {
    actual fun launch() {
        // No-op
    }
}

@Composable
actual fun rememberCameraPickerLauncher(
    onResult: (PlatformFile?) -> Unit
): CameraPickerLauncher? {
    return null
}

actual suspend fun PlatformFile.toAttachmentData(): AttachmentData {
    val file = this.file
    val mime = runCatching {
        Files.probeContentType(file.toPath())
    }.getOrNull() ?: guessMimeType(file.name)

    return AttachmentData(
        path = file.absolutePath,
        mimeType = mime,
        fileName = file.name,
        sizeBytes = file.length()
    )
}