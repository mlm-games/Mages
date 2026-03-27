package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLAnchorElement

@Composable
actual fun rememberFileOpener(): (String, String?) -> Boolean {
    return { url, _ -> openUrl(url) }
}

fun openUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.startsWith("javascript:", ignoreCase = true)) return false

    return try {
        val a = document.createElement("a") as HTMLAnchorElement
        a.href = trimmed
        a.rel = "noopener noreferrer"
        a.target = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            trimmed.startsWith("blob:") || trimmed.startsWith("data:") -> "_blank"
            else -> "_self"
        }
        document.body?.appendChild(a)
        a.click()
        a.remove()
        true
    } catch (_: Throwable) {
        false
    }
}

fun downloadUrl(url: String, filename: String): Boolean {
    return try {
        val anchor = document.createElement("a") as HTMLAnchorElement
        anchor.href = url
        anchor.download = filename
        anchor.setAttribute("style", "display:none")
        document.body?.appendChild(anchor)
        anchor.click()
        document.body?.removeChild(anchor)
        true
    } catch (_: Throwable) {
        false
    }
}
