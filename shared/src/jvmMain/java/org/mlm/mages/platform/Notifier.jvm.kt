package org.mlm.mages.platform
import co.touchlab.kermit.Logger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.mlm.mages.MatrixService
import org.mlm.mages.NotifierImpl
import org.mlm.mages.calls.IncomingCall
import org.mlm.mages.calls.IncomingCallTracker
import org.mlm.mages.calls.isExpired
import org.mlm.mages.calls.isRingingCall
import org.mlm.mages.calls.ringing
import org.mlm.mages.matrix.NotificationKind
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.push.LinuxPushHandler
import org.mlm.mages.settings.AppSettings
import kotlin.system.exitProcess

actual object Notifier {
    private var currentRoomId: String? = null
    private var windowFocused: Boolean = true
    private val roomsNotifiedWithSound = HashSet<String>()

    actual fun notifyRoom(title: String, body: String, icon: String?) {
        // Plain notifs without actions/context (used by other parts of the app)
        //TODO: Use icon parameter later
        NotifierImpl.notify(app = "Mages", title = title, body = body, desktopEntry = "org.mlm.mages")
    }

    actual fun setCurrentRoom(roomId: String?) {
        currentRoomId = roomId
        if (roomId != null) {
            roomsNotifiedWithSound.remove(roomId)
            // Opening the room marks it read here.
            runCatching { NotifierImpl.closeRoomNotification(roomId) }
        }
    }

    actual fun setWindowFocused(focused: Boolean) {
        windowFocused = focused
    }

    actual fun shouldNotify(roomId: String, senderIsMe: Boolean): Boolean {
        if (senderIsMe) return false
        if (windowFocused && currentRoomId == roomId) return false
        return true
    }

    fun shouldPlaySound(roomId: String, soundEnabled: Boolean, oncePerRoomEnabled: Boolean): Boolean {
        if (!soundEnabled) return false
        if (!oncePerRoomEnabled) return true
        return roomsNotifiedWithSound.add(roomId)
    }

    fun clearNotifiedRooms() {
        roomsNotifiedWithSound.clear()
    }
}

@Composable
actual fun BindLifecycle(service: MatrixService, resetSyncState: Boolean) {
    // no-op here
}

@Composable
actual fun BindNotifications(
    service: MatrixService,
    settingsRepository: SettingsRepository<AppSettings>
) {
    val activeAccount by service.activeAccount.collectAsState()
    val activeId = activeAccount?.id
    val incomingCalls: IncomingCallTracker = koinInject()

    LaunchedEffect(activeId) {
        if (activeId == null) return@LaunchedEffect
        incomingCalls.dismissed.collect { NotifierImpl.closeCallNotification(it.roomId) }
    }

    LaunchedEffect(activeId) {
        if (activeId == null) return@LaunchedEffect

        val pushHandler = LinuxPushHandler(service, settingsRepository, activeId)
        val upOk = withContext(Dispatchers.IO) { pushHandler.init() }
        if (upOk) {
            try {
                awaitCancellation()
            } finally {
                withContext(Dispatchers.IO) { pushHandler.shutdown() }
            }
            return@LaunchedEffect
        }
        Logger.w("[UP] falling back to polling")
        var firstPoll = true
        val recentlyNotified = LinkedHashSet<String>()
        val lastReadByRoom = HashMap<String, Long>()
        val lastNotifiedTsByRoom = HashMap<String, Long>()

        while (true) {
            val settings = settingsRepository.flow.first()

            val port = service.portOrNull
            val loggedIn = port != null && service.isLoggedIn()

            if (!settings.notificationsEnabled || !loggedIn) {
                // Account may have been logged out/removed; wait and retry until this effect is cancelled
                firstPoll = true
                recentlyNotified.clear()
                lastReadByRoom.clear()
                lastNotifiedTsByRoom.clear()
                Notifier.clearNotifiedRooms()
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
            val notifModeCache = HashMap<String, RoomNotificationMode?>()

            for (n in items) {
                if (n.eventId.isBlank()) continue

                if (n.tsMs > maxSeenTs) maxSeenTs = n.tsMs

                if (recentlyNotified.size > 2000) {
                    val nit = recentlyNotified.iterator()
                    repeat(500) { if (nit.hasNext()) { nit.next(); nit.remove() } } // desktop notifs do not appear...
                }
                if (!recentlyNotified.add(n.eventId)) continue

                if (n.kind == NotificationKind.Invite) {
                    if (settings.autoJoinInvites) {
                        runCatching { port.acceptInvite(n.roomId) }
                    } else {
                        NotifierImpl.notify(
                            app = "Mages",
                            title = "Room Invite",
                            body = "${n.sender} invited you to ${n.roomName}",
                            desktopEntry = "org.mlm.mages"
                        )
                    }
                    continue
                }

                val senderIsMe = me != null && me == n.senderUserId
                if (!Notifier.shouldNotify(n.roomId, senderIsMe)) continue

                val notifMode = notifModeCache.getOrPut(n.roomId) {
                    runCatching { port.roomNotificationMode(n.roomId) }.getOrNull()
                }
                if (notifMode == RoomNotificationMode.Mute) continue
                if (notifMode == RoomNotificationMode.MentionsAndKeywordsOnly && !n.hasMention) continue

                val lastReadTs = lastReadByRoom[n.roomId] ?: runCatching {
                    port.ownLastRead(n.roomId).second ?: 0L
                }.getOrDefault(0L).also { lastReadByRoom[n.roomId] = it }
                if (lastReadTs > 0L && n.tsMs <= lastReadTs) continue

                if (n.kind == NotificationKind.StateEvent) continue

                if (n.kind.isRingingCall()) {
                    if (!settings.callNotificationsEnabled) continue
                    if (n.isExpired()) continue
                    incomingCalls.report(IncomingCall.ringing(n))
                    if (!Notifier.shouldNotify(n.roomId, senderIsMe)) continue
                    val callAvatarPath = runCatching {
                        val profile = port.roomProfile(n.roomId)
                        service.avatars.resolve(profile?.avatarUrl, px = 96, crop = true)
                    }.getOrNull()
                    NotifierImpl.notifyIncomingCall(
                        callerName = n.sender,
                        roomName = n.roomName,
                        roomId = n.roomId,
                        eventId = n.eventId,
                        iconPath = callAvatarPath
                    )
                    lastNotifiedTsByRoom[n.roomId] = maxOf(lastNotifiedTsByRoom[n.roomId] ?: 0L, n.tsMs)
                    continue
                }

                val avatarPath = runCatching {
                    val profile = port.roomProfile(n.roomId)
                    service.avatars.resolve(profile?.avatarUrl, px = 96, crop = true)
                }.getOrNull()

                val title = if (n.isDm || n.sender == n.roomName) {
                    n.sender
                } else {
                    n.roomName
                }

                val body = when (n.kind) {
                    NotificationKind.Reaction -> n.body
                    else -> "${n.sender}: ${n.body}"
                }

                val playSound = Notifier.shouldPlaySound(
                    roomId = n.roomId,
                    soundEnabled = settings.notificationSound && n.isNoisy,
                    oncePerRoomEnabled = settings.notifySoundOncePerRoom
                )

                NotifierImpl.notifyMatrixEvent(
                    title = title,
                    body = body,
                    roomId = n.roomId,
                    eventId = n.eventId,
                    hasMention = n.hasMention,
                    playSound = playSound,
                    iconPath = avatarPath
                )
                lastNotifiedTsByRoom[n.roomId] = maxOf(lastNotifiedTsByRoom[n.roomId] ?: 0L, n.tsMs)
            }

            for (roomId in NotifierImpl.trackedRoomIds()) {
                val notifiedTs = lastNotifiedTsByRoom[roomId]
                val readTs = runCatching { port.ownLastRead(roomId).second }.getOrNull()
                if (readTs != null && notifiedTs != null && readTs >= notifiedTs) {
                    NotifierImpl.closeRoomNotification(roomId)
                    lastNotifiedTsByRoom.remove(roomId)
                    lastReadByRoom[roomId] = readTs
                    continue
                }
                val stats = runCatching { port.roomUnreadStats(roomId) }.getOrNull()
                if (stats != null && stats.notifications == 0L && stats.mentions == 0L) {
                    NotifierImpl.closeRoomNotification(roomId)
                    lastNotifiedTsByRoom.remove(roomId)
                }
            }

            incomingCalls.prune().forEach { NotifierImpl.closeCallNotification(it.roomId) }

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
