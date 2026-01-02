package org.mlm.mages.platform

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