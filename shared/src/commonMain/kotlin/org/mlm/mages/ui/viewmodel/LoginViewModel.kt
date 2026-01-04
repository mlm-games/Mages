package org.mlm.mages.ui.viewmodel

import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import org.mlm.mages.accounts.MatrixAccount
import org.mlm.mages.accounts.MatrixClients
import org.mlm.mages.matrix.createMatrixPort
import org.mlm.mages.platform.getDeviceDisplayName
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.LoginUiState
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class LoginViewModel(
    private val settingsRepository: SettingsRepository<AppSettings>,
    private val matrixClients: MatrixClients
) : BaseViewModel<LoginUiState>(LoginUiState()) {

    private var ssoJob: Job? = null

    init {
        launch {
            val savedHs = settingsRepository.flow.first().homeserver
            if (savedHs.isNotBlank()) updateState { copy(homeserver = savedHs) }
        }
    }

    sealed class Event { data object LoginSuccess : Event() }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun setHomeserver(value: String) = updateState { copy(homeserver = value) }
    fun setUser(value: String) = updateState { copy(user = value) }
    fun setPass(value: String) = updateState { copy(pass = value) }

    private fun normalizeHomeserver(input: String): String {
        val hs = input.trim()
        if (hs.isBlank()) return ""
        return if (hs.startsWith("https://") || hs.startsWith("http://")) hs else "https://$hs"
    }

    private fun newAccountId(): String {
        val t = Clock.System.now().toEpochMilliseconds()
        val r1 = Random.nextInt().toUInt().toString(16)
        val r2 = Random.nextInt().toUInt().toString(16)
        return "acct_${t}_${r1}_${r2}"
    }

    @OptIn(ExperimentalTime::class)
    fun submit() {
        val s = currentState
        if (s.isBusy || s.user.isBlank() || s.pass.isBlank()) return
        cancelSso()

        val hs = normalizeHomeserver(s.homeserver)
        if (hs.isBlank()) {
            updateState { copy(error = "Please enter a server") }
            return
        }

        launch(onError = { t ->
            updateState { copy(isBusy = false, error = t.message ?: "Login failed") }
        }) {
            updateState { copy(isBusy = true, error = null) }

            val accountId = newAccountId()
            val port = createMatrixPort()

            try {
                port.init(hs, accountId)
                port.login(s.user, s.pass, getDeviceDisplayName())

                if (!port.isLoggedIn()) {
                    port.close()
                    updateState { copy(isBusy = false, error = "Login failed") }
                    return@launch
                }

                val userId = port.whoami()
                if (userId.isNullOrBlank()) {
                    port.close()
                    updateState { copy(isBusy = false, error = "Login failed - couldn't get user ID") }
                    return@launch
                }

                val account = MatrixAccount(
                    id = accountId,
                    userId = userId,
                    homeserver = hs,
                    deviceId = "",
                    accessToken = "",
                    addedAtMs = Clock.System.now().toEpochMilliseconds()
                )

                matrixClients.addLoggedInAccount(account, port)

                settingsRepository.update {
                    it.copy(
                        homeserver = hs,
                        androidNotifBaselineMs = Clock.System.now().toEpochMilliseconds()
                    )
                }

                updateState { copy(isBusy = false, error = null, homeserver = hs) }
                _events.send(Event.LoginSuccess)
            } catch (e: Exception) {
                runCatching { port.close() }
                updateState { copy(isBusy = false, error = e.message ?: "Login failed") }
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    fun startSso(openUrl: (String) -> Boolean) {
        val s = currentState
        if (s.isBusy) return
        cancelSso()

        val hs = normalizeHomeserver(s.homeserver)
        if (hs.isBlank()) {
            updateState { copy(error = "Please enter a server") }
            return
        }

        ssoJob = launch(onError = { t ->
            if (t !is CancellationException) {
                updateState { copy(isBusy = false, ssoInProgress = false, error = t.message ?: "SSO failed") }
            }
        }) {
            updateState { copy(isBusy = true, ssoInProgress = true, error = null) }

            val accountId = newAccountId()
            val port = createMatrixPort()

            try {
                port.init(hs, accountId)

                val ok = withContext(Dispatchers.IO) {
                    port.loginSsoLoopback(openUrl, deviceName = getDeviceDisplayName())
                }

                if (!ok || !port.isLoggedIn()) {
                    port.close()
                    updateState { copy(isBusy = false, ssoInProgress = false, error = "SSO failed or was cancelled") }
                    return@launch
                }

                val userId = port.whoami()
                if (userId.isNullOrBlank()) {
                    port.close()
                    updateState { copy(isBusy = false, ssoInProgress = false, error = "SSO failed - couldn't get user ID") }
                    return@launch
                }

                val account = MatrixAccount(
                    id = accountId,
                    userId = userId,
                    homeserver = hs,
                    deviceId = "",
                    accessToken = "",
                    addedAtMs = Clock.System.now().toEpochMilliseconds()
                )

                matrixClients.addLoggedInAccount(account, port)

                settingsRepository.update {
                    it.copy(
                        homeserver = hs,
                        androidNotifBaselineMs = Clock.System.now().toEpochMilliseconds()
                    )
                }

                updateState { copy(isBusy = false, ssoInProgress = false, error = null, homeserver = hs) }
                _events.send(Event.LoginSuccess)
            } catch (e: CancellationException) {
                runCatching { port.close() }
                throw e
            } catch (e: Exception) {
                runCatching { port.close() }
                updateState { copy(isBusy = false, ssoInProgress = false, error = e.message ?: "SSO failed") }
            }
        }
    }

    fun cancelSso() {
        ssoJob?.cancel()
        ssoJob = null
        updateState { copy(isBusy = false, ssoInProgress = false) }
    }

    fun clearError() = updateState { copy(error = null) }

    override fun onCleared() {
        cancelSso()
        super.onCleared()
    }
}