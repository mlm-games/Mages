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
import org.mlm.mages.settings.ThreadReadMarks
import org.mlm.mages.settings.readReceiptsForRoom
import javax.swing.SwingUtilities

@Composable
fun DesktopBackground(
    deepLinkEmitter: MutableSharedFlow<DeepLinkAction>,
    scope: CoroutineScope,
    onShowWindow: () -> Unit
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
        DesktopNotifActions.openRoom = { roomId, threadId ->
            SwingUtilities.invokeLater { onShowWindow() }
            deepLinkEmitter.tryEmit(DeepLinkAction(roomId = roomId, threadId = threadId))
        }

        DesktopNotifActions.acceptInvite = { roomId ->
            NotifierImpl.closeInviteNotification(roomId)
            scope.launch {
                service.portOrNull?.let { runCatching { it.acceptInvite(roomId) } }
            }
        }

        DesktopNotifActions.declineInvite = { roomId ->
            NotifierImpl.closeInviteNotification(roomId)
            scope.launch {
                service.portOrNull?.let { runCatching { it.leaveRoom(roomId) } }
            }
        }

        suspend fun markThreadRead(roomId: String, threadId: String, eventId: String, sendReceipt: Boolean) {
            val port = service.portOrNull ?: return
            port.markThreadReadAt(roomId, threadId, eventId, sendReceipt)
            val readAt = port.fetchNotification(roomId, eventId)?.tsMs ?: return
            settingsRepo.update { settings ->
                settings.copy(
                    threadReadMarksJson = ThreadReadMarks.marked(
                        settings.threadReadMarksJson,
                        roomId,
                        threadId,
                        readAt,
                    )
                )
            }
        }

        DesktopNotifActions.markRead = { roomId, eventId, threadId ->
            scope.launch {
                val port = service.portOrNull ?: return@launch
                val sendReceipt = settingsRepo.flow.first().readReceiptsForRoom(roomId)
                if (threadId == null) {
                    port.markFullyReadAt(roomId, eventId, sendReceipt)
                } else {
                    markThreadRead(roomId, threadId, eventId, sendReceipt)
                    NotifierImpl.closeThreadNotification(roomId, threadId)
                }
            }
        }

        DesktopNotifActions.reply = { roomId, _, threadId ->
            DesktopNotifActions.openRoom(roomId, threadId)
        }

        DesktopNotifActions.replyText = replyText@{ roomId, eventId, threadId, text ->
            val msg = text.trim()
            if (msg.isBlank()) return@replyText

            scope.launch {
                val port = service.portOrNull ?: return@launch
                if (threadId == null) {
                    port.reply(roomId, eventId, msg)
                } else {
                    port.sendThreadText(roomId, threadId, msg)
                }
                val sendReceipt = settingsRepo.flow.first().readReceiptsForRoom(roomId)
                if (threadId == null) {
                    port.markFullyReadAt(roomId, eventId, sendReceipt)
                } else {
                    markThreadRead(roomId, threadId, eventId, sendReceipt)
                }
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
