package org.mlm.mages

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.datastore.createSettingsDataStore
import kotlinx.browser.window
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.mlm.mages.di.KoinApp
import org.mlm.mages.nav.DeepLinkAction
import org.mlm.mages.nav.MatrixLink
import org.mlm.mages.nav.parseMatrixLink
import org.mlm.mages.settings.AppSettingsSchema
import org.w3c.dom.events.Event

@OptIn(ExperimentalComposeUiApi::class, FlowPreview::class)
fun main() {
    val settingsRepo = SettingsRepository(
        createSettingsDataStore("mages_settings"),
        AppSettingsSchema
    )

    ComposeViewport {
        KoinApp(settingsRepo) {
            App(settingsRepo, deepLinks = browserDeepLinks())
        }
    }
}

private fun browserDeepLinks() = MutableSharedFlow<DeepLinkAction>(
    replay = 1,
    extraBufferCapacity = 8
).onStart {
    fun parseAndEmit(url: String) {
        val link = parseMatrixLink(url)
        if (link is MatrixLink.Room) {
            GlobalScope.launch { emit(DeepLinkAction(
                    roomId = link.target.roomIdOrAlias,
                    eventId = link.target.eventId
                ))
            }
        }
    }

    parseAndEmit(window.location.href)

    val hashHandler: (Event) -> Unit = { parseAndEmit(window.location.href) }
    window.addEventListener("hashchange", hashHandler)
    window.addEventListener("popstate", hashHandler)
}.debounce(100)
