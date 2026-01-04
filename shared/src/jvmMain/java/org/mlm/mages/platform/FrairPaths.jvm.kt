package org.mlm.mages.platform

import java.io.File

actual object MagesPaths {
    @Volatile private var storeDir: String? = null

    actual fun init() {
        if (storeDir == null) {
            val home = System.getProperty("user.home")
            val base = File(home, ".mages/store")
            if (!base.exists()) base.mkdirs()
            storeDir = base.absolutePath
        }
    }

    actual fun storeDir(): String {
        return storeDir ?: run {
            init()
            storeDir!!
        }
    }
}