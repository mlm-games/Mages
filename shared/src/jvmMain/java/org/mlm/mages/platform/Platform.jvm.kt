package org.mlm.mages.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import org.mlm.mages.content.TransferItem
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

actual fun platformSystemBarColorScheme(): ColorScheme? = null

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

actual fun platformNeedsControlledAudioDevices(): Boolean = false

@Composable
actual fun SystemBarsEffect(hide: Boolean) {
    // No-op on desktop
}

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

actual suspend fun PlatformFile.toTransferItem(): TransferItem {
    val file = this.file
    val mime = runCatching {
        Files.probeContentType(file.toPath())
    }.getOrNull() ?: guessMimeType(file.name)

    return TransferItem(
        fileName = file.name,
        path = file.absolutePath,
        mimeType = mime,
        sizeBytes = file.length(),
    )
}

actual val audioPlayerDispatcher: CoroutineDispatcher = Dispatchers.Swing

internal actual fun platformPreparePlaybackUrl(input: String): String = input

internal actual fun platformReleasePlaybackUrl(url: String) = Unit
