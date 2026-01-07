package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.viewModelScope
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.actions.ActionRegistry
import io.github.mlmgames.settings.core.annotations.SettingAction
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.Presence
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.SecurityUiState
import org.mlm.mages.verification.VerificationCoordinator
import kotlin.reflect.KClass

class SecurityViewModel(
    private val service: MatrixService,
    private val settingsRepository: SettingsRepository<AppSettings>,
    private val verification: VerificationCoordinator
) : BaseViewModel<SecurityUiState>(SecurityUiState()) {

    sealed class Event {
        data object LogoutSuccess : Event()
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val settings = settingsRepository.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val settingsSchema = settingsRepository.schema

    init {
        refreshDevices()
        refreshIgnored()
        loadPresence()
    }

    fun <T> updateSetting(name: String, value: T) {
        launch {
            @Suppress("UNCHECKED_CAST")
            settingsRepository.set(name, value as Any)
        }
    }

    suspend fun executeSettingAction(actionClass: KClass<out SettingAction>) {
        ActionRegistry.execute(actionClass)
    }

    fun setSelectedTab(index: Int) {
        updateState { copy(selectedTab = index) }
    }

    fun refreshDevices() {
        launch(onError = { t ->
            updateState { copy(isLoadingDevices = false, error = "Failed to load devices: ${t.message}") }
        }) {
            updateState { copy(isLoadingDevices = true, error = null) }
            val devices = service.listMyDevices()
            updateState { copy(devices = devices, isLoadingDevices = false) }
        }
    }

    // Verification actions delegated to the global coordinator
    fun startSelfVerify(deviceId: String) = verification.startSelfVerify(deviceId)
    fun startUserVerify(userId: String) = verification.startUserVerify(userId.trim())

    // Recovery
    fun openRecoveryDialog() = updateState { copy(showRecoveryDialog = true, recoveryKeyInput = "") }
    fun closeRecoveryDialog() = updateState { copy(showRecoveryDialog = false, recoveryKeyInput = "") }
    fun setRecoveryKey(value: String) = updateState { copy(recoveryKeyInput = value) }

    fun submitRecoveryKey() {
        val key = currentState.recoveryKeyInput.trim()
        if (key.isBlank()) {
            updateState { copy(error = "Enter a recovery key") }
            return
        }

        launch {
            val port = service.portOrNull
            if (port == null || !service.isLoggedIn()) {
                _events.send(Event.ShowError("Not logged in"))
                return@launch
            }

            val ok = port.recoverWithKey(key)
            if (ok) {
                updateState { copy(showRecoveryDialog = false, recoveryKeyInput = "", error = null) }
                _events.send(Event.ShowSuccess("Recovery successful"))
            } else {
                _events.send(Event.ShowError("Recovery failed"))
            }
        }
    }

    fun refreshIgnored() {
        launch {
            val port = service.portOrNull ?: return@launch
            val list = runCatching { port.ignoredUsers() }.getOrElse { emptyList() }
            updateState { copy(ignoredUsers = list) }
        }
    }

    fun unignoreUser(userId: String) {
        launch {
            val port = service.portOrNull ?: return@launch
            val ok = port.unignoreUser(userId)
            if (ok) {
                refreshIgnored()
                _events.send(Event.ShowSuccess("User unignored"))
            } else {
                _events.send(Event.ShowError("Failed to unignore user"))
            }
        }
    }

    fun loadPresence() {
        launch {
            val port = service.portOrNull ?: return@launch
            val myId = port.whoami() ?: return@launch
            val result = port.getPresence(myId)
            if (result != null) {
                updateState {
                    copy(
                        presence = presence.copy(
                            currentPresence = result.first,
                            statusMessage = result.second ?: ""
                        )
                    )
                }
            }
        }
    }

    fun setPresence(presence: Presence) {
        updateState { copy(presence = this.presence.copy(currentPresence = presence)) }
    }

    fun setStatusMessage(message: String) {
        updateState { copy(presence = presence.copy(statusMessage = message)) }
    }

    fun savePresence() {
        launch {
            val port = service.portOrNull ?: return@launch
            updateState { copy(presence = presence.copy(isSaving = true)) }

            val ok = port.setPresence(
                currentState.presence.currentPresence,
                currentState.presence.statusMessage.ifBlank { null }
            )

            updateState { copy(presence = presence.copy(isSaving = false)) }

            if (ok) _events.send(Event.ShowSuccess("Status updated"))
            else _events.send(Event.ShowError("Failed to update status"))
        }
    }

    fun logout() {
        launch {
            val ok = service.logout()
            if (ok) _events.send(Event.LogoutSuccess)
            else _events.send(Event.ShowError("Logout failed"))
        }
    }
}