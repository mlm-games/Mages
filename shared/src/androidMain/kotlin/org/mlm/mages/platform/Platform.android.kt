package org.mlm.mages.platform

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
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