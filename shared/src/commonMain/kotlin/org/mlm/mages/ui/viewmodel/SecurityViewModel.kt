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
import org.mlm.mages.matrix.MatrixPort
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

    fun loadSecurityData() {
        if (recoveryStateSub == null) subscribeRecoveryState()
        refreshDevices()
        refreshIgnored()
        loadPresence()
        loadAccountManagementUrl()
        refreshKeyStorageState(forceFetch = true)
    }

    private var recoveryStateSub: ULong? = null
    private var backupStateSub: ULong? = null

    private fun subscribeRecoveryState() {
        val port = service.portOrNull ?: return
        recoveryStateSub = port.observeRecoveryState(object : MatrixPort.RecoveryStateObserver {
            override fun onUpdate(state: MatrixPort.RecoveryState) {
                updateState { copy(recoveryState = state) }
            }
        })

        backupStateSub = port.observeBackupState(object : MatrixPort.BackupStateObserver {
            override fun onUpdate(state: MatrixPort.BackupState) {
                updateState { copy(backupState = state) }
                refreshKeyStorageState(forceFetch = state == MatrixPort.BackupState.Unknown)
            }
        })
    }

    override fun onCleared() {
        recoveryStateSub?.let { service.portOrNull?.unobserveRecoveryState(it) }
        backupStateSub?.let { service.portOrNull?.unobserveBackupState(it) }
        super.onCleared()
    }

    private fun refreshKeyStorageState(forceFetch: Boolean = false) {
        val port = service.portOrNull ?: return
        launch {
            val shouldFetch = forceFetch ||
                (currentState.backupState == MatrixPort.BackupState.Unknown && currentState.backupExistsOnServer == null)

            val exists = if (shouldFetch) {
                runCatching { port.backupExistsOnServer(true) }.getOrNull()
            } else {
                currentState.backupExistsOnServer
            }

            val isEnabled = when (currentState.backupState) {
                MatrixPort.BackupState.Unknown -> exists == true
                MatrixPort.BackupState.Creating,
                MatrixPort.BackupState.Enabling,
                MatrixPort.BackupState.Resuming,
                MatrixPort.BackupState.Downloading,
                MatrixPort.BackupState.Enabled -> true
                MatrixPort.BackupState.Disabling -> false
            }
            updateState {
                copy(
                    backupExistsOnServer = exists,
                    isKeyStorageEnabled = isEnabled
                )
            }
        }
    }

    fun toggleKeyStorage() {
        val port = service.portOrNull ?: return
        val currentEnabled = currentState.isKeyStorageEnabled ?: return
        launch {
            updateState { copy(isTogglingKeyStorage = true, error = null) }
            val target = !currentEnabled
            val result = runCatching { port.setKeyBackupEnabled(target) }
            updateState { copy(isTogglingKeyStorage = false) }
            if (result.isFailure || result.getOrNull() != true) {
                _events.send(Event.ShowError(result.exceptionOrNull()?.message ?: "Failed to update key storage"))
            } else {
                refreshKeyStorageState(forceFetch = true)
            }
        }
    }

    private var setupRecoveryToken: ULong? = null

    fun setupRecovery() {
        launch {
            val port = service.portOrNull ?: return@launch
            
            val observer = object : MatrixPort.RecoveryObserver {
                override fun onProgress(step: String) {
                    updateState { copy(recoveryProgress = step) }
                }

                override fun onDone(recoveryKey: String) {
                    setupRecoveryToken = null
                    updateState { 
                        copy(
                            isEnablingRecovery = false,
                            recoveryProgress = null,
                            generatedRecoveryKey = recoveryKey,
                        ) 
                    }
                }

                override fun onError(message: String) {
                    setupRecoveryToken = null
                    updateState { 
                        copy(
                            isEnablingRecovery = false,
                            recoveryProgress = null,
                            error = "Recovery error: $message"
                        ) 
                    }
                }
            }

            updateState { copy(isEnablingRecovery = true, recoveryProgress = "Starting...") }
            setupRecoveryToken = port.setupRecovery(observer)
        }
    }

    fun dismissRecoveryKey() {
        updateState { copy(generatedRecoveryKey = null) }
    }

    private fun loadAccountManagementUrl() {
        launch {
            val port = service.portOrNull ?: return@launch
            val url = runCatching { port.accountManagementUrl() }.getOrNull()
            updateState { copy(accountManagementUrl = url) }
        }
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
    fun setRecoveryKey(value: String) = updateState { copy(recoveryKeyInput = value, error = null) }

    fun clearRecoverySubmitSuccess() = updateState { copy(recoverySubmitSuccess = false, recoveryKeyInput = "") }

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

            updateState { copy(isSubmittingRecoveryKey = true, error = null) }
            val ok = port.recoverWithKey(key)
            if (ok) {
                updateState { copy(isSubmittingRecoveryKey = false, recoverySubmitSuccess = true) }
                _events.send(Event.ShowSuccess("Recovery successful"))
            } else {
                updateState { copy(isSubmittingRecoveryKey = false, error = "Recovery failed. Check your key and try again.") }
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
            val result = port.unignoreUser(userId)
            if (result.isSuccess) {
                refreshIgnored()
                _events.send(Event.ShowSuccess("User unignored"))
            } else {
                _events.send(Event.ShowError(result.toUserMessage("Failed to unignore user")))
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

            val result = port.setPresence(
                currentState.presence.currentPresence,
                currentState.presence.statusMessage.ifBlank { null }
            )

            updateState { copy(presence = presence.copy(isSaving = false)) }

            if (result?.isSuccess == true) _events.send(Event.ShowSuccess("Status updated"))
            else _events.send(Event.ShowError(result.toUserMessage("Failed to update status")))
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
