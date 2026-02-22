package org.mlm.mages.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile
import org.mlm.mages.ui.components.AttachmentData

expect fun getDeviceDisplayName(): String

expect fun deleteDirectory(path: String): Boolean

@Composable
expect fun getDynamicColorScheme(darkTheme: Boolean, useDynamicColors: Boolean): ColorScheme?

expect fun platformEmbeddedElementCallUrlOrNull(): String?

expect fun platformEmbeddedElementCallParentUrlOrNull(): String?

expect class CameraPickerLauncher {
    fun launch()
}

@Composable
expect fun rememberCameraPickerLauncher(
    onResult: (PlatformFile?) -> Unit
): CameraPickerLauncher?


expect suspend fun PlatformFile.toAttachmentData(): AttachmentData
