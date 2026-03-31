package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.NotificationKind
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.util.nowMs
import org.w3c.dom.events.Event

actual object Notifier {
    private var currentRoomId: String? = null
    private var windowFocused: Boolean = true

    actual fun notifyRoom(title: String, body: String, icon: String?) {
        if (!windowIsSecureContext()) return
        if (!notificationSupported()) return
        if (notificationPermission() != "granted") return

        createBrowserNotification(
            title = title,
            body = body,
            icon = icon
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
    val activeAccount by service.activeAccount.collectAsState()
    val activeId = activeAccount?.id

    LaunchedEffect(activeId) {
        if (activeId == null) return@LaunchedEffect
        var firstPoll = true
        val recentlyNotified = LinkedHashSet<String>()
        val lastReadByRoom = HashMap<String, Long>()

        while (isActive) {
            val settings = settingsRepository.flow.first()

            if (
                !settings.notificationsEnabled ||
                !windowIsSecureContext() ||
                !notificationSupported() ||
                notificationPermission() != "granted" ||
                !service.isLoggedIn()
            ) {
                firstPoll = true
                recentlyNotified.clear()
                lastReadByRoom.clear()
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

            val since = if (firstPoll) baseline else (baseline - 60_000L).coerceAtLeast(0L)
            firstPoll = false

            val ownUserId = port.whoami()
            val notifications = runCatching {
                port.fetchNotificationsSince(since, 50, 20)
            }.getOrElse { emptyList() }

            var nextBaseline = baseline
            val now = nowMs()

            for (notification in notifications.sortedBy { it.tsMs }) {
                if (notification.eventId.isBlank()) continue
                if (notification.tsMs <= baseline) continue
                nextBaseline = maxOf(nextBaseline, notification.tsMs)

                if (recentlyNotified.size > 2000) {
                    val it = recentlyNotified.iterator()
                    repeat(500) { if (it.hasNext()) { it.next(); it.remove() } }
                }
                if (!recentlyNotified.add(notification.eventId)) continue

                if (notification.expiresAtMs != null && notification.expiresAtMs <= now) continue

                val senderIsMe = ownUserId != null && notification.senderUserId == ownUserId
                if (!Notifier.shouldNotify(notification.roomId, senderIsMe)) continue

                val lastReadTs = lastReadByRoom[notification.roomId] ?: runCatching {
                    port.ownLastRead(notification.roomId).second ?: 0L
                }.getOrDefault(0L).also { lastReadByRoom[notification.roomId] = it }
                if (lastReadTs > 0L && notification.tsMs <= lastReadTs) continue

                if (notification.kind == NotificationKind.Invite && settings.autoJoinInvites) {
                    runCatching { port.acceptInvite(notification.roomId) }
                    continue
                }

                if (notification.kind == NotificationKind.StateEvent) continue

                val title = if (notification.isDm || notification.sender == notification.roomName) {
                    notification.sender
                } else {
                    "${notification.sender} • ${notification.roomName.ifBlank { notification.sender }}"
                }

                val body = when (notification.kind) {
                    NotificationKind.Reaction -> notification.body
                    else -> if (notification.isDm) notification.body
                            else "${notification.sender}: ${notification.body}"
                }

                val avatarUrl = if (notification.isDm) {
                    notification.senderAvatarUrl
                } else {
                    notification.roomAvatarUrl
                }

                val resolvedIcon = avatarUrl?.let { url ->
                    runCatching {
                        service.avatars.resolve(url, px = 96, crop = true)
                    }.getOrNull()
                }

                Notifier.notifyRoom(title, body, resolvedIcon)
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
