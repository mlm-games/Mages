package org.mlm.mages.platform

import android.content.Context
import java.io.File

actual object MagesPaths {
    @Volatile
    private var storeDir: String? = null

    fun init(context: Context) {
        if (storeDir == null) {
            val base = File(context.filesDir, "mages_store")
            if (!base.exists()) base.mkdirs()
            storeDir = base.absolutePath
        }
    }

    actual fun init() {
        throw UnsupportedOperationException("Use init(context: Context) on Android")
    }

    actual fun storeDir(): String {
        return storeDir ?: throw IllegalStateException(
            "MagesPaths not initialized. Call MagesPaths.init(context) in Application/Activity.onCreate."
        )
    }
}