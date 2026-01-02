package org.mlm.mages.ui.viewmodel

import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.mlm.mages.MatrixService
import org.mlm.mages.platform.getDeviceDisplayName
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.LoginUiState
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class LoginViewModel(
    private val service: MatrixService,
    private val settingsRepository: SettingsRepository<AppSettings>
) : BaseViewModel<LoginUiState>(LoginUiState()) {

    init {
        launch {
            val savedHs = settingsRepository.flow.first().homeserver
            if (savedHs.isNotBlank()) {
                updateState { copy(homeserver = savedHs) }
            }
        }
    }

    // One-time events
    sealed class Event {
        data object LoginSuccess : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    //  Public Actions 

    fun setHomeserver(value: String) {
        updateState { copy(homeserver = value) }
    }

    fun setUser(value: String) {
        updateState { copy(user = value) }
    }

    fun setPass(value: String) {
        updateState { copy(pass = value) }
    }

    private fun normalizeHomeserver(input: String): String {
        val hs = input.trim()
        if (hs.isBlank()) return ""
        return if (hs.startsWith("https://") || hs.startsWith("http://")) hs else "https://$hs"
    }

    @OptIn(ExperimentalTime::class)
    fun submit() {
        val s = currentState
        if (s.isBusy || s.user.isBlank() || s.pass.isBlank()) return

        val hs = normalizeHomeserver(s.homeserver)
        if (hs.isBlank()) {
            updateState { copy(error = "Please enter a server") }
            return
        }

        launch(onError = { t ->
            updateState { copy(isBusy = false, error = t.message ?: "Login failed") }
        }) {
            updateState { copy(isBusy = true, error = null) }

            service.init(hs)
            service.login(s.user, s.pass, getDeviceDisplayName())

            if (!service.isLoggedIn()) {
                updateState { copy(isBusy = false, error = "Login failed") }
                return@launch
            }

            settingsRepository.update {
                it.copy(
                    homeserver = hs,
                    androidNotifBaselineMs = Clock.System.now().toEpochMilliseconds()
                )
            }

            updateState { copy(isBusy = false, error = null, homeserver = hs) }
            _events.send(Event.LoginSuccess)
        }
    }

    @OptIn(ExperimentalTime::class)
    fun startSso(openUrl: (String) -> Boolean) {
        if (currentState.isBusy) return

        launch(onError = { t ->
            updateState { copy(isBusy = false, error = t.message ?: "SSO failed") }
        }) {
            updateState { copy(isBusy = true, error = null) }

            val hs = normalizeHomeserver(currentState.homeserver)
            if (hs.isBlank()) {
                updateState { copy(isBusy = false, error = "Please enter a server") }
                return@launch
            }

            service.init(hs)

            val ok = withTimeoutOrNull(120_000) {
                service.port.loginSsoLoopback(openUrl, deviceName = getDeviceDisplayName())
                service.isLoggedIn()
            } ?: false

            if (!ok) {
                updateState { copy(isBusy = false, error = "SSO failed or was cancelled") }
                return@launch
            }

            settingsRepository.update {
                it.copy(
                    homeserver = hs,
                    androidNotifBaselineMs = Clock.System.now().toEpochMilliseconds()
                )
            }

            updateState { copy(isBusy = false, error = null, homeserver = hs) }
            _events.send(Event.LoginSuccess)
        }
    }

    fun clearError() {
        updateState { copy(error = null) }
    }
}