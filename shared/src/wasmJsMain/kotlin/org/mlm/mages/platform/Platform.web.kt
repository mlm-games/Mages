package org.mlm.mages.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.WebFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlin.js.unsafeCast
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.mlm.mages.content.TransferItem
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL

private class WebBlob(val bytes: ByteArray, val mimeType: String)

private const val WEB_BLOB_PREFIX = "web_blob_"
private const val WEB_BLOB_URI_PREFIX = "webblob:"

private val webBlobCache = mutableMapOf<String, WebBlob>()
private var blobCounter = 0

private fun generateBlobId(): String = "$WEB_BLOB_PREFIX${blobCounter++}"

actual val audioPlayerDispatcher: CoroutineDispatcher = Dispatchers.Default

@JsFun("""(v) => {
  if (typeof v !== 'string') return false;
  return /^data:audio\/ogg(?:;[^,]*)?;base64,/i.test(v);
}""")
private external fun isOggBase64DataUrl(value: String): Boolean

@JsFun("""(v) => {
  if (typeof v !== 'string') return false;
  return /^data:audio\/[^;]+(?:;[^,]*)?;base64,/i.test(v);
}""")
private external fun isAudioBase64DataUrl(value: String): Boolean

@JsFun("""() => {
  const a = document.createElement('audio');
  const canPlay = (t) => {
    try {
      const r = a.canPlayType(t);
      return r === 'probably' || r === 'maybe';
    } catch (_) {
      return false;
    }
  };
  return canPlay('audio/ogg; codecs="opus"') || canPlay('audio/ogg;codecs=opus') || canPlay('audio/ogg');
}""")
private external fun browserSupportsOggPlayback(): Boolean

@JsFun("""(base64, mime) => {
  try {
    const bin = atob(base64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const blob = new Blob([bytes], { type: mime || 'application/octet-stream' });
    return URL.createObjectURL(blob);
  } catch (_) {
    return null;
  }
}""")
private external fun createAudioBlobUrl(base64: String, mime: String): String?

@JsFun("""(url) => {
  try { URL.revokeObjectURL(url); } catch (_) {}
}""")
private external fun revokeBlobUrl(url: String)

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
            onResult?.invoke(browserFile?.let { PlatformFile(WebFile.FileWrapper(it.unsafeCast())) })
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
    val mime = mimeType()?.toString() ?: "application/octet-stream"
    val blobId = generateBlobId()
    webBlobCache[blobId] = WebBlob(bytes, mime)

    return TransferItem(
        fileName = name,
        path = blobId,
        mimeType = mime,
        sizeBytes = bytes.size.toLong(),
        webObjectUrl = "$WEB_BLOB_URI_PREFIX$blobId",
    )
}

actual suspend fun decodeQrImage(bytes: ByteArray): ByteArray? = null

internal fun stageWebBlob(bytes: ByteArray, mime: String, fileName: String): String {
    val path = "${generateBlobId()}/${safeBlobFileName(fileName)}"
    webBlobCache[path] = WebBlob(bytes, mime)
    return path
}

private fun safeBlobFileName(name: String): String =
    name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', '_')
        .take(96)
        .ifBlank { "image" }

fun retrieveWebBlob(path: String): ByteArray? = webBlobCache[path]?.bytes

fun retrieveWebBlobMimeType(path: String): String? = webBlobCache[path]?.mimeType

fun webBlobKey(data: String): String? = data
    .removePrefix(WEB_BLOB_URI_PREFIX)
    .takeIf { it.startsWith(WEB_BLOB_PREFIX) }

fun clearWebBlob(path: String) {
    webBlobCache.remove(path)
}

internal actual fun platformPreparePlaybackUrl(input: String): String {
    if (!isAudioBase64DataUrl(input)) {
        return input
    }

    if (isOggBase64DataUrl(input) && !browserSupportsOggPlayback()) {
        throw IllegalStateException("This browser cannot play OGG/Opus audio")
    }

    val base64MarkerIndex = input.indexOf(";base64,")
    if (base64MarkerIndex <= 5) {
        return input
    }

    val mime = input
        .substring(5, base64MarkerIndex)
        .trim()
        .ifBlank { "application/octet-stream" }
    val payload = input
        .substring(base64MarkerIndex + 8)
        .filterNot { it.isWhitespace() }
    if (payload.isEmpty()) return input

    return createAudioBlobUrl(payload, mime) ?: input
}

internal actual fun platformReleasePlaybackUrl(url: String) {
    if (url.startsWith("blob:")) {
        revokeBlobUrl(url)
    }
}
