package org.mlm.mages.platform

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.dialogs.compose.PhotoResultLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import org.koin.mp.KoinPlatform
import org.mlm.mages.ui.components.AttachmentData
import java.io.File

actual fun getDeviceDisplayName(): String {
    val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val model = Build.MODEL
    return if (model.startsWith(manufacturer, ignoreCase = true)) {
        "Mages (Android - $model)"
    } else {
        "Mages (Android - $manufacturer $model)"
    }
}

actual fun deleteDirectory(path: String): Boolean {
    return File(path).deleteRecursively()
}

@Composable
actual fun getDynamicColorScheme(darkTheme: Boolean, useDynamicColors: Boolean): ColorScheme? {
    return if (useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        }
    } else {
        null
    }
}

fun Activity.enterPip() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        enterPictureInPictureMode(
            PictureInPictureParams.Builder().build()
        )
    }
}

actual fun platformEmbeddedElementCallUrlOrNull(): String? {
    return null
}

actual fun platformEmbeddedElementCallParentUrlOrNull(): String? = null

actual class CameraPickerLauncher(
    private val fileKitLauncher: PhotoResultLauncher
) {
    actual fun launch() {
        fileKitLauncher.launch()
    }
}

@Composable
actual fun rememberCameraPickerLauncher(
    onResult: (PlatformFile?) -> Unit
): CameraPickerLauncher? {
    val launcher = rememberCameraPickerLauncher(onResult = onResult)
    return remember(launcher) { CameraPickerLauncher(launcher) }
}

actual suspend fun PlatformFile.toAttachmentData(): AttachmentData =
    withContext(Dispatchers.IO) {
        val ctx = KoinPlatform.getKoin().get<Context>()
        val resolver = ctx.contentResolver

        val name = this@toAttachmentData.name

        val cacheDir = File(FileKit.cacheDir.toString(), "mages_uploads").apply { mkdirs() }
        val outFile = File(cacheDir, "${System.currentTimeMillis()}_$name")

        val bytes = this@toAttachmentData.readBytes()
        outFile.sink().buffer().use { it.write(bytes) }


        AttachmentData(
            path = outFile.absolutePath,
            mimeType = this@toAttachmentData.mimeType().toString(),
            fileName = name,
            sizeBytes = outFile.length()
        )
    }
