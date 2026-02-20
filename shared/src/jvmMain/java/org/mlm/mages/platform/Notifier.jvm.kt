package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.mlm.mages.MatrixService
import org.mlm.mages.NotifierImpl
import org.mlm.mages.settings.AppSettings
import kotlin.system.exitProcess

actual object Notifier {
    private var currentRoomId: String? = null
    private var windowFocused: Boolean = true

    actual fun notifyRoom(title: String, body: String) {
        // Plain notifs without actions/context (used by other parts of the app)
        NotifierImpl.notify(app = "Mages", title = title, body = body, desktopEntry = "org.mlm.mages")
    }

    actual fun setCurrentRoom(roomId: String?) {
        currentRoomId = roomId
    }

    actual fun setWindowFocused(focused: Boolean) {
        windowFocused = focused
    }

    actual fun shouldNotify(roomId: String, senderIsMe: Boolean): Boolean {
        if (senderIsMe) return false
        if (windowFocused && currentRoomId == roomId) return false
        return true
    }
}

@Composable
actual fun BindLifecycle(service: MatrixService) {
    // no-op here
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

        while (true) {
            val settings = settingsRepository.flow.first()

            val port = service.portOrNull
            val loggedIn = port != null && service.isLoggedIn()

            if (!settings.notificationsEnabled || !loggedIn) {
                // Account may have been logged out/removed; wait and retry until this effect is cancelled
                firstPoll = true
                recentlyNotified.clear()
                lastReadByRoom.clear()
                delay(15_000L)
                continue
            }

            var baseline = settings.desktopNotifBaselineMs
            if (baseline == 0L) {
                baseline = System.currentTimeMillis()
                settingsRepository.update { it.copy(desktopNotifBaselineMs = baseline) }
            }

            val since = if (firstPoll) baseline else (baseline - 60_000L).coerceAtLeast(0L)
            firstPoll = false

            val me = runCatching { port.whoami() }.getOrNull()

            val items = runCatching {
                port.fetchNotificationsSince(
                    sinceMs = since,
                    maxRooms = 50,
                    maxEvents = 50
                )
            }.getOrElse { emptyList() }

            var maxSeenTs = baseline

            for (n in items) {
                if (n.eventId.isBlank()) continue
                if (n.tsMs > maxSeenTs) maxSeenTs = n.tsMs

                if (recentlyNotified.size > 2000) {
                    val nit = recentlyNotified.iterator()
                    repeat(500) { if (nit.hasNext()) { nit.next(); nit.remove() } }
                }

                if (!recentlyNotified.add(n.eventId)) continue

                val senderIsMe = me != null && me == n.senderUserId
                if (!Notifier.shouldNotify(n.roomId, senderIsMe)) continue

                val lastReadTs = lastReadByRoom[n.roomId] ?: runCatching {
                    port.ownLastRead(n.roomId).second ?: 0L
                }.getOrDefault(0L).also { lastReadByRoom[n.roomId] = it }

                if (lastReadTs > 0L && n.tsMs <= lastReadTs) continue

                // Use server push evaluation
                if (!n.isNoisy) continue

                val avatarPath = runCatching {
                    val profile = port.roomProfile(n.roomId)
                    service.avatars.resolve(profile?.avatarUrl, px = 96, crop = true)
                }.getOrNull()

                NotifierImpl.notifyMatrixEvent(
                    title = n.roomName,
                    body = "${n.sender}: ${n.body}",
                    roomId = n.roomId,
                    eventId = n.eventId,
                    hasMention = n.hasMention,
                    iconPath = avatarPath
                )
            }

            if (maxSeenTs > baseline) {
                settingsRepository.update { it.copy(desktopNotifBaselineMs = maxSeenTs) }
            }

            delay(15_000L)
        }
    }
}

@Composable
actual fun rememberQuitApp(): () -> Unit = {
    exitProcess(0)
}
