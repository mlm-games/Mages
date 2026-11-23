package org.mlm.mages.platform

import android.content.Context
import org.koin.mp.KoinPlatform

@Suppress("DEPRECATION")
actual fun appVersion(): String {
    val context = KoinPlatform.getKoin().get<Context>()
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return "${info.versionName ?: "unknown"} (${info.versionCode})"
}
