package org.mlm.mages

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.github.mlmgames.settings.core.actions.ActionRegistry
import kotlinx.browser.window
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.mlm.mages.di.KoinApp
import org.mlm.mages.nav.DeepLinkAction
import org.mlm.mages.nav.MatrixLink
import org.mlm.mages.nav.parseMatrixLink
import org.mlm.mages.platform.Notifier
import org.mlm.mages.platform.SettingsProvider
import org.mlm.mages.platform.requestNotificationPermissionFromUserGesture
import org.mlm.mages.settings.RequestNotificationPermissionAction
import org.mlm.mages.settings.TestNotificationAction
import org.mlm.mages.ui.util.nowMs

@OptIn(ExperimentalComposeUiApi::class, FlowPreview::class)
fun main() {
    val settingsRepo = SettingsProvider.get()

    val appScope = MainScope()

    ActionRegistry.register(RequestNotificationPermissionAction::class) {
        requestNotificationPermissionFromUserGesture { granted ->
            if (granted) {
                appScope.launch {
                    settingsRepo.update { current ->
                        current.copy(
                            notificationsEnabled = true,
                            desktopNotifBaselineMs = nowMs()
                        )
                    }
                }
            }
        }
    }

    ActionRegistry.register(TestNotificationAction::class) {
        Notifier.notifyRoom(
            title = "Mages test",
            body = "If you see this, browser notifications work"
        )
    }

    ComposeViewport {
        KoinApp(settingsRepo) {
            App(settingsRepo, deepLinks = browserDeepLinks())
        }
    }
}

@OptIn(FlowPreview::class)
private fun browserDeepLinks(): Flow<DeepLinkAction> {
    val deepLinkEmitter = MutableSharedFlow<DeepLinkAction>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val deepLinks = deepLinkEmitter.asSharedFlow()

    fun parseAndEmit(url: String) {
        val link = parseMatrixLink(url)
        if (link is MatrixLink.Room) {
            deepLinkEmitter.tryEmit(DeepLinkAction(
                roomId = link.target.roomIdOrAlias,
                eventId = link.target.eventId
            ))
        }
    }

    parseAndEmit(window.location.href)

    window.addEventListener("hashchange") { parseAndEmit(window.location.href) }
    window.addEventListener("popstate") { parseAndEmit(window.location.href) }

    return deepLinks
}
