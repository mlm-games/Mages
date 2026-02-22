package org.mlm.mages.platform

import io.github.vinceglb.filekit.FileKit
import java.io.File

actual object MagesPaths {
    @Volatile private var storeDir: String? = null

    actual fun init() {
        if (storeDir == null) {
            val base = File(getAppDataDir("mages"), "store")
            if (!base.exists()) base.mkdirs()
            storeDir = base.absolutePath
        }

        FileKit.init("org.mlm.mages")
    }

    actual fun storeDir(): String {
        return storeDir ?: run {
            init()
            storeDir!!
        }
    }

    private fun getAppDataDir(appName: String): File {
        val os = System.getProperty("os.name").lowercase()

        return when {
            os.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA")
                    ?: System.getenv("APPDATA")
                    ?: "${System.getProperty("user.home")}\\AppData\\Local"
                File(localAppData, appName)
            }
            os.contains("mac") || os.contains("darwin") -> {
                val home = System.getProperty("user.home")
                File(home, "Library/Application Support/$appName")
            }
            else -> {
                val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                    ?: "${System.getProperty("user.home")}/.local/share"
                File(dataHome, appName)
            }
        }
    }
}