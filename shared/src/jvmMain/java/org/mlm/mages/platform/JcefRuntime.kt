package org.mlm.mages.platform

import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler
import org.cef.CefApp
import org.cef.CefSettings
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

internal object JcefRuntime {
    private val appRef = AtomicReference<CefApp?>(null)

    fun isInitialized(): Boolean = appRef.get() != null

    fun getOrInit(): CefApp {
        appRef.get()?.let { return it }

        synchronized(this) {
            appRef.get()?.let { return it }

            val baseDir = jcefBaseDir()
            val installDir = File(baseDir, "install")
            val cacheDir = File(baseDir, "cache")
            Files.createDirectories(installDir.toPath())
            Files.createDirectories(cacheDir.toPath())

            val builder = CefAppBuilder()
            builder.setInstallDir(installDir)
            builder.setProgressHandler(ConsoleProgressHandler())
            builder.setAppHandler(object : MavenCefAppHandlerAdapter() {})

            builder.cefSettings.cache_path = cacheDir.absolutePath
            builder.cefSettings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING
            builder.cefSettings.log_file = File(cacheDir, "cef-debug.log").absolutePath
            builder.cefSettings.remote_debugging_port = 9222

            // THIS CAUSES THE WINDOW TO NOT RECEIVE INPUTS / NOT RESIZE PROPERLY
            builder.cefSettings.windowless_rendering_enabled = false

            val os = System.getProperty("os.name").lowercase()
            val isFlatpak = System.getenv("FLATPAK_ID") != null

            if (os.contains("linux")) {
                builder.addJcefArgs("--ozone-platform=x11")
                builder.addJcefArgs("--disable-dev-shm-usage")

                if (isFlatpak) {
                    // disabling jcef sandbox doesn't fix it
                }

                // Test flags
//                builder.addJcefArgs("--disable-gpu")
//                builder.addJcefArgs("--disable-gpu-compositing")
//                builder.addJcefArgs("--disable-software-rasterizer")
//
//                builder.addJcefArgs("--no-sandbox")
//                builder.addJcefArgs("--disable-setuid-sandbox")
//                builder.addJcefArgs("--change-stack-guard-on-fork=disable")
            }

            builder.addJcefArgs("--enable-media-stream")
            builder.addJcefArgs("--autoplay-policy=no-user-gesture-required")

            val app = builder.build()
            appRef.set(app)
            return app
        }
    }

    private fun jcefBaseDir(): File {
        val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
        val base = if (xdg != null) File(xdg) else File(System.getProperty("user.home"), ".cache")
        return File(base, "mages/jcef")
    }
}