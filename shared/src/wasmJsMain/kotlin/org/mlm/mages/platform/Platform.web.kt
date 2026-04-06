package org.mlm.mages.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.browser.document
import kotlinx.browser.window
import org.mlm.mages.content.TransferItem
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL

private val webBlobCache = mutableMapOf<String, ByteArray>()
private var blobCounter = 0

private fun generateBlobId(): String = "web_blob_${blobCounter++}"

actual fun getDeviceDisplayName(): String = "Mages (Web)"

actual fun platformSystemBarColorScheme(): ColorScheme? = null

@Composable
actual fun getDynamicColorScheme(
    darkTheme: Boolean,
    useDynamicColors: Boolean
): ColorScheme? {
    return null
}

actual fun deleteDirectory(path: String): Boolean = false

actual fun platformEmbeddedElementCallParentUrlOrNull(): String? {
    return runCatching {
        window.location.origin
    }.getOrNull()
}

actual fun platformEmbeddedElementCallUrlOrNull(): String? {
    return runCatching {
        URL("element-call/index.html", document.baseURI).href
    }.getOrNull()
}

actual fun platformNeedsControlledAudioDevices(): Boolean = false

@Composable
actual fun SystemBarsEffect(hide: Boolean) {
    // No-op on web
}

actual class CameraPickerLauncher {
    private var onResult: ((PlatformFile?) -> Unit)? = null

    actual fun launch() {
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = "image/*"
        input.setAttribute("capture", "environment")
        input.setAttribute("style", "display:none")

        document.body?.appendChild(input)

        input.addEventListener("change") {
            val browserFile = inputSelectedFile(input)
            onResult?.invoke(browserFile?.let { PlatformFile(it) })
            document.body?.removeChild(input)
        }

        input.click()
    }

    fun setOnResult(callback: (PlatformFile?) -> Unit) {
        onResult = callback
    }
}

@Composable
actual fun rememberCameraPickerLauncher(
    onResult: (PlatformFile?) -> Unit
): CameraPickerLauncher? {
    val launcher = remember { CameraPickerLauncher() }
    launcher.setOnResult(onResult)
    return launcher
}

actual suspend fun PlatformFile.toTransferItem(): TransferItem {
    val bytes = readBytes()
    val blobId = generateBlobId()
    webBlobCache[blobId] = bytes

    return TransferItem(
        fileName = name,
        path = blobId,
        mimeType = mimeType()?.toString() ?: "application/octet-stream",
        sizeBytes = bytes.size.toLong(),
        webObjectUrl = "webblob:$blobId",
    )
}

fun retrieveWebBlob(path: String): ByteArray? = webBlobCache[path]

fun clearWebBlob(path: String) {
    webBlobCache.remove(path)
}
