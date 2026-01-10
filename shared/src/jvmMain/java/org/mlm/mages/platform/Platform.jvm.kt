package org.mlm.mages.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import java.io.File
import java.net.InetAddress

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


actual fun deleteDirectory(path: String): Boolean {
    return File(path).deleteRecursively()
}

@Composable
actual fun getDynamicColorScheme(
    darkTheme: Boolean,
    useDynamicColors: Boolean
): ColorScheme? {return null}

actual fun platformEmbeddedElementCallUrlOrNull(): String? = ElementCallLocalServer.indexUrl()