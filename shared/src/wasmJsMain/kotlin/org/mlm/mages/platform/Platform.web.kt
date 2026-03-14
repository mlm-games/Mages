package org.mlm.mages.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.browser.window
import org.mlm.mages.ui.components.AttachmentData

private val webBlobCache = mutableMapOf<String, ByteArray>()
private var blobCounter = 0

private fun generateBlobId(): String {
    blobCounter++
    return "webblob_$blobCounter"
}

actual fun getDeviceDisplayName(): String {
    return "Mages (Web)"
}

actual fun deleteDirectory(path: String): Boolean {
    return false
}

@Composable
actual fun getDynamicColorScheme(darkTheme: Boolean, useDynamicColors: Boolean): ColorScheme? {
    return null
}

actual fun platformEmbeddedElementCallUrlOrNull(): String? {
    return "${window.location.origin}/element-call/index.html"
}

actual fun platformEmbeddedElementCallParentUrlOrNull(): String? {
    return window.location.href
}

actual class CameraPickerLauncher {
    actual fun launch() {
    }
}

@Composable
actual fun rememberCameraPickerLauncher(
    onResult: (PlatformFile?) -> Unit
): CameraPickerLauncher? {
    return null
}

actual suspend fun PlatformFile.toAttachmentData(): AttachmentData {
    val bytes = this.readBytes()
    val blobId = generateBlobId()
    webBlobCache[blobId] = bytes

    return AttachmentData(
        path = blobId,
        mimeType = this.mimeType().toString(),
        fileName = this.name,
        sizeBytes = bytes.size.toLong()
    )
}

fun retrieveWebBlob(path: String): ByteArray? = webBlobCache[path]

fun clearWebBlob(path: String) {
    webBlobCache.remove(path)
}
