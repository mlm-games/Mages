package org.mlm.mages.platform

import android.content.Context
import org.koin.core.context.GlobalContext
import java.io.File

actual object MagesPaths {
    private val appContextOrNull: Context?
        get() = runCatching { GlobalContext.get().get<Context>() }.getOrNull()

    actual fun init() {
        storeDir()
        cacheDir()
    }

    actual fun storeDir(): String {
        val ctx = appContextOrNull ?: return ""
        val dir = File(ctx.filesDir, "mages_store")
        dir.mkdirs()
        return dir.absolutePath
    }

    actual fun cacheDir(): String {
        val ctx = appContextOrNull ?: return ""
        return ctx.cacheDir.absolutePath
    }
}

actual val voiceMessageMimeType: String = "audio/ogg"

actual val voiceMessageExtension: String = "ogg"