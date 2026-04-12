package org.mlm.mages.platform

import io.github.vinceglb.filekit.FileKit
import java.io.File

actual object MagesPaths {
    @Volatile private var storeDirValue: String? = null
    @Volatile private var cacheDirValue: String? = null

    actual fun init() {
        FileKit.init("org.mlm.mages")
        storeDir()
        cacheDir()
    }

    actual fun storeDir(): String {
        return storeDirValue ?: run {
            val base = File(getAppDataDir("mages"), "store")
            base.mkdirs()
            storeDirValue = base.absolutePath
            base.absolutePath
        }
    }

    actual fun cacheDir(): String {
        return cacheDirValue ?: run {
            val base = File(getAppDataDir("mages"), "cache")
            base.mkdirs()
            cacheDirValue = base.absolutePath
            base.absolutePath
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

actual val voiceMessageMimeType: String = "audio/wav"

actual val voiceMessageExtension: String = "wav"
