package org.mlm.mages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.mlm.mages.platform.BindNotifications
import org.mlm.mages.settings.AppSettings
import javax.swing.SwingUtilities

@Composable
fun DesktopAppContent(
    deepLinks: Flow<String>,
    deepLinkRoomIds: MutableSharedFlow<String>,
    scope: CoroutineScope
) {
    val service: MatrixService = koinInject()
    val settingsRepo: io.github.mlmgames.settings.core.SettingsRepository<AppSettings> = koinInject()
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
            deepLinkRoomIds.tryEmit(roomId)
        }

        DesktopNotifActions.markRead = { roomId, eventId ->
            scope.launch {
                runCatching { service.port.markFullyReadAt(roomId, eventId) }
            }
        }

        DesktopNotifActions.reply = { roomId, _ ->
            DesktopNotifActions.openRoom(roomId)
        }

        DesktopNotifActions.replyText = replyText@{ roomId, eventId, text ->
            val msg = text.trim()
            if (msg.isBlank()) return@replyText

            scope.launch {
                runCatching { service.port.reply(roomId, eventId, msg) }
                runCatching { service.port.markFullyReadAt(roomId, eventId) }
            }
        }
    }
    BindNotifications(service = service, settingsRepo)

    App(settingsRepo, deepLinks)
}