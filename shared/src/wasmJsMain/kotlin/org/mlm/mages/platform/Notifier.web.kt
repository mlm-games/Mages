package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import io.github.mlmgames.settings.core.SettingsRepository
import org.mlm.mages.MatrixService
import org.mlm.mages.settings.AppSettings
import kotlinx.browser.window
import org.w3c.dom.events.Event

actual object Notifier {
    private var currentRoomId: String? = null
    private var windowFocused: Boolean = true

    actual fun notifyRoom(title: String, body: String) {
        if (!windowIsSecureContext()) return
        if (!notificationSupported()) return
        if (notificationPermission() != "granted") return

        createNotification(
            title = title,
            body = body,
            icon = "favicon.ico"
        )
    }

    actual fun setCurrentRoom(roomId: String?) {
        currentRoomId = roomId
    }

    actual fun setWindowFocused(focused: Boolean) {
        windowFocused = focused
    }

    actual fun shouldNotify(roomId: String, senderIsMe: Boolean): Boolean {
        if (senderIsMe) return false
        if (roomId == currentRoomId && windowFocused) return false
        return true
    }
}

@Composable
actual fun BindLifecycle(service: MatrixService, resetSyncState: Boolean) {
    DisposableEffect(service) {
        val focusHandler: (Event) -> Unit = {
            Notifier.setWindowFocused(true)
            service.portOrNull?.enterForeground()
        }

        val blurHandler: (Event) -> Unit = {
            Notifier.setWindowFocused(false)
            service.portOrNull?.enterBackground()
        }

        window.addEventListener("focus", focusHandler)
        window.addEventListener("blur", blurHandler)

        onDispose {
            window.removeEventListener("focus", focusHandler)
            window.removeEventListener("blur", blurHandler)
        }
    }
}

@Composable
actual fun BindNotifications(
    service: MatrixService,
    settingsRepository: SettingsRepository<AppSettings>
) {
}

@Composable
actual fun rememberQuitApp(): () -> Unit {
    return {
        runCatching { window.close() }
    }
}
