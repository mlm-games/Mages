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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import org.koin.compose.koinInject
import org.mlm.mages.MatrixService
import org.mlm.mages.calls.IncomingCall
import org.mlm.mages.calls.IncomingCallTracker
import org.mlm.mages.calls.isExpired
import org.mlm.mages.calls.isRingingCall
import org.mlm.mages.calls.ringing
import org.mlm.mages.matrix.NotificationKind
import org.mlm.mages.nav.PendingDeepLinks
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.settings.autoDownloadRules
import org.mlm.mages.push.NotificationMediaPolicy
import org.mlm.mages.push.NotificationPresentation
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.ThreadReadMarks
import org.mlm.mages.ui.util.nowMs
import org.w3c.dom.events.Event
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.getString

actual object Notifier {
    private val visibleRooms = RoomVisibilityTracker()
    private val visibleThreads = ThreadVisibilityTracker()
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

    actual fun onRoomOpened(roomId: String, clearNotifications: Boolean) {
        if (clearNotifications) {
            runCatching { closeBrowserNotificationByTag(roomId) }
        }
    }

    actual fun setRoomVisible(roomId: String, visible: Boolean) =
        visibleRooms.set(roomId, visible)

    actual fun setThreadVisible(roomId: String, threadId: String, visible: Boolean) =
        visibleThreads.set(roomId, threadId, visible)

    actual fun setWindowFocused(focused: Boolean) {
        windowFocused = focused
    }

    internal fun isRoomVisible(roomId: String): Boolean =
        windowFocused && visibleRooms.isVisible(roomId)

    internal fun isThreadVisible(roomId: String, threadId: String): Boolean =
        windowFocused && visibleThreads.isVisible(roomId, threadId)

    actual fun shouldNotify(roomId: String, threadId: String?, senderIsMe: Boolean): Boolean {
        if (senderIsMe) return false
        if (isRoomVisible(roomId)) return false
        if (threadId != null && isThreadVisible(roomId, threadId)) return false
        return true
    }

    actual fun clearThreadNotification(roomId: String, threadId: String) {
        runCatching { closeBrowserNotificationByTag(notificationTag(roomId, threadId)) }
    }
}

private const val THREAD_TAG_SEPARATOR = '\u001f'

private fun notificationTag(roomId: String, threadId: String?): String =
    if (threadId.isNullOrBlank()) roomId else "$roomId$THREAD_TAG_SEPARATOR$threadId"

private fun notificationTagRoomId(tag: String): String =
    tag.substringBefore(THREAD_TAG_SEPARATOR)

private fun notificationTagThreadId(tag: String): String? =
    tag.substringAfter(THREAD_TAG_SEPARATOR, "").takeIf { it.isNotBlank() }

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
    val incomingCalls: IncomingCallTracker = koinInject()

    DisposableEffect(Unit) {
        val handler: (Event) -> Unit = { event ->
            val tag = notificationClickTag(event)
            if (!tag.isNullOrBlank()) {
                val roomId = notificationTagRoomId(tag)
                val threadId = notificationTagThreadId(tag)
                PendingDeepLinks.offer(
                    buildString {
                        append("mages://room?id=").append(roomId)
                        if (threadId != null) append("&thread=").append(threadId)
                    }
                )
            }
        }
        window.addEventListener("mages-notif-click", handler)
        onDispose { window.removeEventListener("mages-notif-click", handler) }
    }

    LaunchedEffect(activeId) {
        if (activeId == null) return@LaunchedEffect
        incomingCalls.dismissed.collect { closeBrowserNotificationByTag(it.roomId) }
    }

    LaunchedEffect(activeId) {
        if (activeId == null) return@LaunchedEffect
        var firstPoll = true
        val recentlyNotified = LinkedHashSet<String>()
        val lastReadByRoom = HashMap<String, Long>()
        val lastNotifiedTsByConversation = HashMap<String, Long>()

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
                lastNotifiedTsByConversation.clear()
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

            // First poll uses exact baseline (no lookback) to avoid dumping clips of notification at once.
            // others look back 60s to catch any missed notifications.
            val since = if (firstPoll) baseline else (baseline - 60_000L).coerceAtLeast(0L)
            firstPoll = false

            val ownUserId = port.whoami()
            val notifications = runCatching {
                port.fetchNotificationsSince(since, 50, 20)
            }.getOrElse { emptyList() }

            var nextBaseline = baseline
            val now = nowMs()
            val notifModeCache = HashMap<String, RoomNotificationMode?>()

            for (notification in notifications.sortedBy { it.tsMs }) {
                if (notification.eventId.isBlank()) continue
                if (notification.tsMs <= baseline) continue
                nextBaseline = maxOf(nextBaseline, notification.tsMs)

                if (recentlyNotified.size > 2000) {
                    recentlyNotified.toList().take(500).forEach { recentlyNotified.remove(it) }
                }
                if (!recentlyNotified.add(notification.eventId)) continue

                if (notification.isExpired(now)) continue

                val senderIsMe = ownUserId != null && notification.senderUserId == ownUserId
                if (!settings.notifyWhileRoomOpen &&
                    !Notifier.shouldNotify(notification.roomId, notification.threadId, senderIsMe)
                ) {
                    continue
                }

                val notifMode = notifModeCache.getOrPut(notification.roomId) {
                    runCatching { port.roomNotificationMode(notification.roomId) }.getOrNull()
                }
                if (notifMode == RoomNotificationMode.Mute) continue
                if (notifMode == RoomNotificationMode.MentionsAndKeywordsOnly && !notification.hasMention) continue

                if (notification.threadId == null) {
                    val lastReadTs = lastReadByRoom[notification.roomId] ?: runCatching {
                        port.ownLastRead(notification.roomId).second ?: 0L
                    }.getOrDefault(0L).also { lastReadByRoom[notification.roomId] = it }
                    if (lastReadTs > 0L && notification.tsMs <= lastReadTs) continue
                } else {
                    val readTs = ThreadReadMarks.latestTs(
                        settings.threadReadMarksJson,
                        notification.roomId,
                        notification.threadId,
                    )
                    if (readTs > 0L && notification.tsMs <= readTs) continue
                }

                if (notification.kind == NotificationKind.Invite && settings.autoJoinInvites) {
                    runCatching { port.acceptInvite(notification.roomId) }
                    continue
                }

                if (notification.kind == NotificationKind.StateEvent) continue

                if (notification.kind.isRingingCall()) {
                    if (!settings.callNotificationsEnabled) continue
                    incomingCalls.report(IncomingCall.ringing(notification))
                    if (!Notifier.shouldNotify(notification.roomId, notification.threadId, senderIsMe)) continue
                    val callBody = if (notification.roomName.isNotBlank() &&
                        notification.roomName != notification.sender
                    ) {
                        getString(Res.string.notif_incoming_call_for, notification.roomName)
                    } else {
                        getString(Res.string.incoming_call)
                    }
                    if (createBrowserNotification(
                            notification.sender,
                            callBody,
                            null,
                            notification.roomId
                        )
                    ) {
                        val tag = notificationTag(notification.roomId, null)
                        lastNotifiedTsByConversation[tag] =
                            maxOf(lastNotifiedTsByConversation[tag] ?: 0L, notification.tsMs)
                    }
                    continue
                }

                val presentation = NotificationPresentation.of(
                    notification = notification,
                    showPreview = settings.notificationShowPreview,
                    redactedBody = getString(Res.string.notif_new_message)
                )

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

                val resolvedImage = presentation.media
                    ?.takeIf { NotificationMediaPolicy.allowed(settings) }
                    ?.let { NotificationMediaPolicy.preview(port, it.attachment) }

                val tag = notificationTag(notification.roomId, notification.threadId)
                if (createBrowserNotification(
                        presentation.title,
                        if (resolvedImage != null) presentation.bodyWithMedia.ifBlank { presentation.body } else presentation.body,
                        resolvedIcon,
                        tag,
                        resolvedImage
                    )
                ) {
                    lastNotifiedTsByConversation[tag] =
                        maxOf(lastNotifiedTsByConversation[tag] ?: 0L, notification.tsMs)
                }
            }

            for (tag in lastNotifiedTsByConversation.keys.toList()) {
                val roomId = notificationTagRoomId(tag)
                val threadId = notificationTagThreadId(tag)
                if (Notifier.isRoomVisible(roomId)) continue
                if (threadId != null) {
                    if (Notifier.isThreadVisible(roomId, threadId)) {
                        runCatching { closeBrowserNotificationByTag(tag) }
                        lastNotifiedTsByConversation.remove(tag)
                    }
                    continue
                }
                val notifiedTs = lastNotifiedTsByConversation[tag] ?: continue
                val readTs = runCatching { port.ownLastRead(roomId).second }.getOrNull()
                if (readTs != null && readTs >= notifiedTs) {
                    runCatching { closeBrowserNotificationByTag(tag) }
                    lastNotifiedTsByConversation.remove(tag)
                    lastReadByRoom[roomId] = readTs
                    continue
                }
                val stats = runCatching { port.roomUnreadStats(roomId) }.getOrNull()
                if (stats != null && stats.notifications == 0L && stats.mentions == 0L) {
                    runCatching { closeBrowserNotificationByTag(tag) }
                    lastNotifiedTsByConversation.remove(tag)
                }
            }

            incomingCalls.prune().forEach {
                runCatching { closeBrowserNotificationByTag(it.roomId) }
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

@Composable
actual fun BindAutoDownload(
    service: MatrixService,
    settingsRepository: SettingsRepository<AppSettings>,
) {
    val settings by settingsRepository.flow.collectAsState(initial = AppSettings())
    val account by service.activeAccount.collectAsState()
    val ready by service.isReady.collectAsState()

    LaunchedEffect(
        settings.autoDownloadPhotos,
        settings.autoDownloadAudio,
        settings.autoDownloadVideo,
        settings.autoDownloadDocuments,
        account?.id,
        ready,
    ) {
        if (!ready) return@LaunchedEffect
        val port = service.portOrNull ?: return@LaunchedEffect
        port.setAutoDownloadRules(settings.autoDownloadRules())
        port.setAutoDownloadNetwork(metered = false, roaming = false)
    }
}
