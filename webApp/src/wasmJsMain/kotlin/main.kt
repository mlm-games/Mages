package org.mlm.mages

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.github.mlmgames.settings.core.actions.ActionRegistry
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.mlm.mages.di.KoinApp
import org.mlm.mages.nav.MatrixLink
import org.mlm.mages.nav.PendingDeepLinks
import org.mlm.mages.nav.parseMatrixLink
import org.mlm.mages.platform.Notifier
import org.mlm.mages.platform.SettingsProvider
import org.mlm.mages.platform.installWebImageLoader
import org.mlm.mages.platform.requestNotificationPermissionFromUserGesture
import org.mlm.mages.settings.RequestNotificationPermissionAction
import org.mlm.mages.settings.TestNotificationAction
import org.mlm.mages.ui.util.nowMs

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    installWebImageLoader()

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

    watchBrowserLinks()

    ComposeViewport {
        KoinApp(settingsRepo) {
            App(settingsRepo)
        }
    }
}

// The web build is reached at its own origin, so room links arrive as
// fragments on the page URL (https://host/#/!room:server). They are
// re-parsed as matrix.to links and held by App until an account exists.
private fun watchBrowserLinks() {
    fun parseAndOffer(url: String) {
        if (parseMatrixLink(url) !is MatrixLink.Unsupported) {
            PendingDeepLinks.offer(url)
            return
        }

        val fragment = url.substringAfter('#', "").trim()
        if (fragment.isEmpty()) return

        val asMatrixToLink = "https://matrix.to/#/$fragment"
        if (parseMatrixLink(asMatrixToLink) !is MatrixLink.Unsupported) {
            PendingDeepLinks.offer(asMatrixToLink)
        }
    }

    parseAndOffer(window.location.href)
    window.addEventListener("hashchange") { parseAndOffer(window.location.href) }
    window.addEventListener("popstate") { parseAndOffer(window.location.href) }
}
