package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.RenderedNotification
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.util.nowMs
import org.w3c.dom.events.Event

actual object Notifier {
    private var currentRoomId: String? = null
    private var windowFocused: Boolean = true

    actual fun notifyRoom(title: String, body: String) {
        if (!windowIsSecureContext()) return
        if (!notificationSupported()) return
        if (notificationPermission() != "granted") return

        createBrowserNotification(
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
    LaunchedEffect(service, settingsRepository) {
        while (isActive) {
            val settings = settingsRepository.flow.first()

            if (
                !settings.notificationsEnabled ||
                !windowIsSecureContext() ||
                !notificationSupported() ||
                notificationPermission() != "granted" ||
                !service.isLoggedIn()
            ) {
                delay(5_000)
                continue
            }

            val port = service.portOrNull
            if (port == null) {
                delay(2_000)
                continue
            }

            val baseline = if (settings.desktopNotifBaselineMs > 0L) {
                settings.desktopNotifBaselineMs
            } else {
                settingsRepository.update { it.copy(desktopNotifBaselineMs = nowMs()) }
                continue
            }

            val ownUserId = port.whoami()
            val notifications = runCatching {
                port.fetchNotificationsSince(baseline, 50, 20)
            }.getOrElse { emptyList() }

            var nextBaseline = baseline
            val now = nowMs()

            for (notification in notifications.sortedBy { it.tsMs }) {
                if (notification.tsMs <= baseline) continue
                nextBaseline = maxOf(nextBaseline, notification.tsMs)

                if (notification.expiresAtMs != null && notification.expiresAtMs <= now) continue
                if (settings.mentionsOnly && !notification.hasMention) continue

                val senderIsMe = ownUserId != null && notification.senderUserId == ownUserId
                if (!Notifier.shouldNotify(notification.roomId, senderIsMe)) continue

                val title = if (notification.isDm) {
                    notification.sender
                } else {
                    notification.roomName.ifBlank { notification.sender }
                }

                Notifier.notifyRoom(title, notification.body)
            }

            if (nextBaseline != baseline) {
                settingsRepository.update { current ->
                    if (current.desktopNotifBaselineMs >= nextBaseline) current
                    else current.copy(desktopNotifBaselineMs = nextBaseline)
                }
            }

            delay(15_000)
        }
    }
}

@Composable
actual fun rememberQuitApp(): () -> Unit = {
    runCatching { window.close() }
}
