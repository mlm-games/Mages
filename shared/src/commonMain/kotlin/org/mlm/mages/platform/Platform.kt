package org.mlm.mages.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineDispatcher
import org.mlm.mages.content.TransferItem

expect fun getDeviceDisplayName(): String

expect fun deleteDirectory(path: String): Boolean

expect fun platformSystemBarColorScheme(): ColorScheme?

@Composable
expect fun getDynamicColorScheme(darkTheme: Boolean, useDynamicColors: Boolean): ColorScheme?

expect fun platformEmbeddedElementCallUrlOrNull(): String?

expect fun platformEmbeddedElementCallParentUrlOrNull(): String?

expect fun platformNeedsControlledAudioDevices(): Boolean

@Composable
expect fun SystemBarsEffect(hide: Boolean)

expect class CameraPickerLauncher {
    fun launch()
}

@Composable
expect fun rememberCameraPickerLauncher(
    onResult: (PlatformFile?) -> Unit
): CameraPickerLauncher?


expect suspend fun PlatformFile.toTransferItem(): TransferItem

expect val audioPlayerDispatcher: CoroutineDispatcher

internal expect fun platformPreparePlaybackUrl(input: String): String

internal expect fun platformReleasePlaybackUrl(url: String)
