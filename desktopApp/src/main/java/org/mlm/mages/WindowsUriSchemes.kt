package org.mlm.mages

import java.io.File
import kotlin.concurrent.thread

internal object WindowsUriSchemes {

    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

    fun registerSchemes() {
        if (!isWindows) return
        val exe = selfExePath() ?: return
        thread(start = true, isDaemon = true, name = "mages-uri-scheme-registration") {
            registerScheme("mages", "Mages Link", exe)
            registerScheme("matrix", "Matrix Link", exe)
        }
    }

    private fun selfExePath(): File? {
        val classPath = System.getProperty("java.class.path", "")
        val mainJar = classPath.split(';').firstOrNull { it.isNotBlank() } ?: return null
        val installDir = File(mainJar).parentFile?.parentFile ?: return null
        return File(installDir, "Mages.exe").takeIf { it.isFile }
    }

    private fun registerScheme(scheme: String, displayName: String, exe: File) {
        val key = "HKCU\\Software\\Classes\\$scheme"
        val command = "\"${exe.absolutePath}\" \"%1\""
        runCatching {
            runProcess("reg.exe", "add", key, "/ve", "/d", "URL:$displayName", "/f")
            runProcess(
                "reg.exe", "add", key, "/v", "URL Protocol",
                "/t", "REG_SZ", "/d", "", "/f"
            )
            runProcess(
                "reg.exe", "add", "$key\\shell\\open\\command",
                "/ve", "/d", command, "/f"
            )
        }
    }

    private fun runProcess(vararg command: String) {
        ProcessBuilder(*command).redirectErrorStream(true).start().waitFor()
    }
}
