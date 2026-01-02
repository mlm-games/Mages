package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.delay
import org.mlm.mages.MatrixService
import org.mlm.mages.NotifierImpl
import org.mlm.mages.notifications.getRoomNotifMode
import org.mlm.mages.notifications.shouldNotify
import org.mlm.mages.storage.loadLong
import org.mlm.mages.storage.saveLong
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
actual fun BindNotifications(service: MatrixService, dataStore: DataStore<Preferences>) {
    LaunchedEffect(service) {
        var baseline = loadLong(dataStore, "desktop:notif_baseline_ms")
        if (baseline == null) {
            baseline = System.currentTimeMillis()
            saveLong(dataStore, "desktop:notif_baseline_ms", baseline)
        }

        var firstPoll = true
        val recentlyNotified = LinkedHashSet<String>()

        fun rememberNotified(eventId: String) {
            recentlyNotified.add(eventId)
            while (recentlyNotified.size > 500) {
                val it = recentlyNotified.iterator()
                it.next()
                it.remove()
            }
        }

        // cache per room (to avoid calling ownLastRead too much)
        val lastReadByRoom = HashMap<String, Long>()

        while (true) {
            if (!service.isLoggedIn()) {
                firstPoll = true
                delay(15_000L)
                continue
            }

            val me = runCatching { service.port.whoami() }.getOrNull()

            val since = if (firstPoll) {
                baseline ?: 0L
            } else {
                ((baseline ?: 0L) - 60_000L).coerceAtLeast(0L)
            }
            firstPoll = false

            val items = runCatching {
                service.port.fetchNotificationsSince(
                    sinceMs = since,
                    maxRooms = 50,
                    maxEvents = 50
                )
            }.getOrElse { emptyList() }

            var maxSeenTs = baseline ?: 0L

            for (n in items) {
                if (n.eventId.isBlank()) continue

                // Always advance baseline based on what we *saw*, not only what we notified.
                if (n.tsMs > maxSeenTs) maxSeenTs = n.tsMs

                if (recentlyNotified.contains(n.eventId)) continue

                val lastReadTs = lastReadByRoom[n.roomId] ?: runCatching {
                    service.port.ownLastRead(n.roomId).second ?: 0L
                }.getOrDefault(0L).also { lastReadByRoom[n.roomId] = it }

                if (lastReadTs > 0L && n.tsMs <= lastReadTs) continue

                val mode = runCatching { getRoomNotifMode(dataStore, n.roomId) }
                    .getOrDefault(org.mlm.mages.notifications.RoomNotifMode.Default)

                if (!shouldNotify(mode, n.hasMention)) continue

                val senderIsMe = me != null && me == n.senderUserId
                if (!Notifier.shouldNotify(n.roomId, senderIsMe)) continue

                NotifierImpl.notifyMatrixEvent(
                    title = n.roomName,
                    body = "${n.sender}: ${n.body}",
                    roomId = n.roomId,
                    eventId = n.eventId,
                    hasMention = n.hasMention
                )
                rememberNotified(n.eventId)
            }

            if (maxSeenTs > (baseline ?: 0L)) {
                baseline = maxSeenTs
                saveLong(dataStore, "desktop:notif_baseline_ms", baseline)
            }

            delay(15_000L)
        }
    }
}

@Composable
actual fun rememberQuitApp(): () -> Unit = {
    exitProcess(0)
}