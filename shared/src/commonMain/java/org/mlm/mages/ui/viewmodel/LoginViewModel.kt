package org.mlm.mages.ui.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.mlm.mages.MatrixService
import org.mlm.mages.platform.getDeviceDisplayName
import org.mlm.mages.storage.loadString
import org.mlm.mages.storage.saveLong
import org.mlm.mages.storage.saveString
import org.mlm.mages.ui.LoginUiState
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class LoginViewModel(
    private val service: MatrixService,
    private val dataStore: DataStore<Preferences>
) : BaseViewModel<LoginUiState>(LoginUiState()) {

    // One-time events
    sealed class Event {
        data object LoginSuccess : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        launch {
            val savedHs = loadString(dataStore, "homeserver")
            if (savedHs != null) {
                updateState { copy(homeserver = savedHs) }
            }
        }
    }
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

            withContext(Dispatchers.Default) {
                saveString(dataStore, "homeserver", hs)
                saveLong(dataStore, "notif:baseline_ms", Clock.System.now().toEpochMilliseconds())
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

            withContext(Dispatchers.Default) {
                saveString(dataStore, "homeserver", hs)
                saveLong(dataStore, "notif:baseline_ms", Clock.System.now().toEpochMilliseconds())
            }

            updateState { copy(isBusy = false, error = null, homeserver = hs) }
            _events.send(Event.LoginSuccess)
        }
    }

    fun clearError() {
        updateState { copy(error = null) }
    }
}