package org.mlm.mages.platform

import android.os.Build
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