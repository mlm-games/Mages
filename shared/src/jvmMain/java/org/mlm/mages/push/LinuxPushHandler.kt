package org.mlm.mages.push

import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.mlm.mages.MatrixService
import org.mlm.mages.NotifierImpl
import org.mlm.mages.matrix.NotificationKind
import org.mlm.mages.matrix.RoomNotificationMode
import org.mlm.mages.platform.Notifier
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.appLanguageTagOrDefault
import java.util.Locale

class LinuxPushHandler(
    private val service: MatrixService,
    private val settingsRepository: SettingsRepository<AppSettings>,
    private val activeAccountId: String
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun init(): Boolean {
        val endpoint = LinuxPushManager.tryRegister() ?: run {
            System.err.println("[UP] push init failed — no endpoint from distributor")
            return false
        }
        val port = service.portOrNull ?: run {
            System.err.println("[UP] push init failed — no matrix port")
            return false
        }

        val gatewayUrl = GatewayResolver.resolveGateway(endpoint)

        val settings = settingsRepository.flow.first()
        val languageTag = appLanguageTagOrDefault(
            language = settings.language,
            defaultTag = Locale.getDefault().toLanguageTag()
        )

        val ok = port.registerUnifiedPush(
            appId = "org.mlm.mages",
            pushKey = endpoint,
            gatewayUrl = gatewayUrl,
            deviceName = "Linux",
            lang = languageTag,
            profileTag = activeAccountId
        )
        if (!ok) {
            LinuxPushManager.shutdown()
            System.err.println("[UP] push init failed — registerUnifiedPush returned false")
            return false
        }

        LinuxPushManager.onMessage { raw ->
            scope.launch {
                handlePushMessage(raw)
            }
        }

        System.err.println("[UP] push init succeeded")
        return true
    }

    private suspend fun handlePushMessage(raw: String) {
        val pushes = extractMatrixPushPayload(raw)
        val port = service.portOrNull ?: return

        for (push in pushes) {
            val eventPush = push as? ParsedMatrixPush.Event ?: continue
            val roomId = eventPush.roomId
            val eventId = eventPush.eventId
            val n = port.fetchNotification(roomId, eventId) ?: continue

            val me = port.whoami()
            val senderIsMe = me != null && me == n.senderUserId
            if (!Notifier.shouldNotify(n.roomId, senderIsMe)) continue

            val notifMode = runCatching { port.roomNotificationMode(n.roomId) }.getOrNull()
            if (notifMode == RoomNotificationMode.Mute) continue
            if (notifMode == RoomNotificationMode.MentionsAndKeywordsOnly && !n.hasMention) continue

            if (n.kind == NotificationKind.Invite) {
                val settings = settingsRepository.flow.first()
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

            if (n.kind == NotificationKind.StateEvent) continue

            val avatarPath = runCatching {
                val profile = port.roomProfile(n.roomId)
                service.avatars.resolve(profile?.avatarUrl, px = 96, crop = true)
            }.getOrNull()

            val title = if (n.isDm || n.sender == n.roomName) {
                n.sender
            } else {
                "${n.sender} \u2022 ${n.roomName}"
            }

            val body = when (n.kind) {
                NotificationKind.Reaction -> n.body
                else -> "${n.sender}: ${n.body}"
            }

            val settings = settingsRepository.flow.first()
            NotifierImpl.notifyMatrixEvent(
                title = title,
                body = body,
                roomId = n.roomId,
                eventId = n.eventId,
                hasMention = n.hasMention,
                playSound = settings.notificationSound && n.isNoisy,
                iconPath = avatarPath
            )
        }
    }

    fun shutdown() {
        scope.cancel()
        LinuxPushManager.shutdown()
    }
}
