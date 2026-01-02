package org.mlm.mages.platform

import android.os.Build

actual fun getDeviceDisplayName(): String {
    val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val model = Build.MODEL
    return if (model.startsWith(manufacturer, ignoreCase = true)) {
        "Mages (Android - $model)"
    } else {
        "Mages (Android - $manufacturer $model)"
    }
}