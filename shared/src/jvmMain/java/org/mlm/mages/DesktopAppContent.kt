package org.mlm.mages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.mlm.mages.calls.IncomingCallTracker
import org.mlm.mages.calls.answerIncomingCall
import org.mlm.mages.calls.declineIncomingCall
import org.mlm.mages.nav.DeepLinkAction
import org.mlm.mages.platform.BindNotifications
import org.mlm.mages.settings.AppSettings
import javax.swing.SwingUtilities

@Composable
fun DesktopBackground(
    deepLinkEmitter: MutableSharedFlow<DeepLinkAction>,
    scope: CoroutineScope
) {
    val service: MatrixService = koinInject()
    val settingsRepo: SettingsRepository<AppSettings> = koinInject()
    val incomingCalls: IncomingCallTracker = koinInject()
    val settings by settingsRepo.flow.collectAsState(AppSettings())

    LaunchedEffect(Unit) {
        val hs = settings.homeserver
        if (hs.isNotBlank()) {
            runCatching { service.initFromDisk() }
        }

        if (service.isLoggedIn()) {
            service.startSupervisedSync()
            runCatching { service.port.enterForeground() }
        }
    }

    LaunchedEffect(service) {
        DesktopNotifActions.openRoom = { roomId ->
            SwingUtilities.invokeLater { /* showWindow = true handled elsewhere */ }
            deepLinkEmitter.tryEmit(DeepLinkAction(roomId = roomId))
        }

        DesktopNotifActions.markRead = { roomId, eventId ->
            scope.launch {
                val port = service.portOrNull ?: return@launch
                val sendReceipt = settingsRepo.flow.first().sendReadReceipts
                port.markFullyReadAt(roomId, eventId, sendReceipt)
            }
        }

        DesktopNotifActions.reply = { roomId, _ ->
            DesktopNotifActions.openRoom(roomId)
        }

        DesktopNotifActions.replyText = replyText@{ roomId, eventId, text ->
            val msg = text.trim()
            if (msg.isBlank()) return@replyText

            scope.launch {
                val port = service.portOrNull ?: return@launch
                port.reply(roomId, eventId, msg)
                val sendReceipt = settingsRepo.flow.first().sendReadReceipts
                port.markFullyReadAt(roomId, eventId, sendReceipt)
            }
        }

        DesktopNotifActions.answerCall = { roomId, eventId ->
            NotifierImpl.closeCallNotification(roomId)
            answerIncomingCall(incomingCalls, roomId, eventId) { action ->
                deepLinkEmitter.tryEmit(action)
            }
        }

        DesktopNotifActions.declineCall = { roomId, eventId ->
            scope.launch {
                declineIncomingCall(service.portOrNull, incomingCalls, roomId, eventId)
                NotifierImpl.closeCallNotification(roomId)
            }
        }
    }

    BindNotifications(service = service, settingsRepo)
}

@Composable
fun DesktopAppContent(
    deepLinks: Flow<DeepLinkAction>
) {
    val settingsRepo: SettingsRepository<AppSettings> = koinInject()
    App(settingsRepo, deepLinks)
}
