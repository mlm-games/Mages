package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.viewModelScope
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.actions.ActionRegistry
import io.github.mlmgames.settings.core.annotations.SettingAction
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.Presence
import org.mlm.mages.settings.OpenNotificationRulesAction
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.SecurityUiState
import org.mlm.mages.verification.VerificationCoordinator
import kotlin.reflect.KClass

class SecurityViewModel(
    private val service: MatrixService,
    private val settingsRepository: SettingsRepository<AppSettings>,
    private val verification: VerificationCoordinator
) : BaseViewModel<SecurityUiState>(SecurityUiState()) {

    val activeAccount = service.activeAccount

    sealed class Event {
        data object LogoutSuccess : Event()
        data class ShowError(val message: String) : Event()
        data class ShowSuccess(val message: String) : Event()
        data object NavigateToNotificationRules : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val settings = settingsRepository.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val settingsSchema = settingsRepository.schema

    private var hasLoadedSecurityData = false
    private var observedAccountId: String? = service.activeAccount.value?.id
    private var accountDataVersion: Long = 0L

    init {
        viewModelScope.launch {
            service.activeAccount.collectLatest { account ->
                val accountId = account?.id
                if (accountId == observedAccountId) return@collectLatest

                observedAccountId = accountId
                onActiveAccountChanged()
            }
        }
    }

    fun loadSecurityData() {
        hasLoadedSecurityData = true
        if (recoveryStateSub == null) subscribeRecoveryState()
        refreshDevices()
        refreshIgnored()
        loadPresence()
        loadAccountManagementUrl()
        refreshKeyStorageState(forceFetch = true)
    }

    private var recoveryStateSub: ULong? = null
    private var backupStateSub: ULong? = null
    private var recoveryObserverPort: MatrixPort? = null

    private suspend fun onActiveAccountChanged() {
        accountDataVersion += 1
        clearAccountScopedState()
        unsubscribeRecoveryState()

        if (!hasLoadedSecurityData || !service.isLoggedInSuspend()) return

        subscribeRecoveryState()
        refreshDevices()
        refreshIgnored()
        loadPresence()
        loadAccountManagementUrl()
        refreshKeyStorageState(forceFetch = true)
    }

    private fun clearAccountScopedState() {
        val selectedTab = currentState.selectedTab
        updateState { SecurityUiState(selectedTab = selectedTab) }
    }

    private fun isCurrentAccountData(version: Long): Boolean =
        version == accountDataVersion

    private fun updateStateIfCurrent(
        version: Long,
        transform: SecurityUiState.() -> SecurityUiState,
    ) {
        if (isCurrentAccountData(version)) {
            updateState(transform)
        }
    }

    private fun subscribeRecoveryState() {
        val port = service.portOrNull ?: return
        val version = accountDataVersion

        unsubscribeRecoveryState()
        recoveryObserverPort = port

        recoveryStateSub = port.observeRecoveryState(object : MatrixPort.RecoveryStateObserver {
            override fun onUpdate(state: MatrixPort.RecoveryState) {
                updateStateIfCurrent(version) { copy(recoveryState = state) }
            }
        })

        backupStateSub = port.observeBackupState(object : MatrixPort.BackupStateObserver {
            override fun onUpdate(state: MatrixPort.BackupState) {
                updateStateIfCurrent(version) { copy(backupState = state) }
                if (isCurrentAccountData(version)) {
                    refreshKeyStorageState(forceFetch = state == MatrixPort.BackupState.Unknown)
                }
            }
        })
    }

    private fun unsubscribeRecoveryState() {
        val observerPort = recoveryObserverPort

        recoveryStateSub?.let { token ->
            runCatching { observerPort?.unobserveRecoveryState(token) }
        }
        backupStateSub?.let { token ->
            runCatching { observerPort?.unobserveBackupState(token) }
        }

        recoveryStateSub = null
        backupStateSub = null
        recoveryObserverPort = null
    }

    override fun onCleared() {
        unsubscribeRecoveryState()
        super.onCleared()
    }

    private fun refreshKeyStorageState(forceFetch: Boolean = false) {
        val port = service.portOrNull ?: return
        val version = accountDataVersion

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
            updateStateIfCurrent(version) {
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

    fun setupRecovery() {
        val version = accountDataVersion

        launch {
            val port = service.portOrNull ?: return@launch
            
            val observer = object : MatrixPort.RecoveryObserver {
                override fun onProgress(step: String) {
                    updateStateIfCurrent(version) { copy(recoveryProgress = step) }
                }

                override fun onDone(recoveryKey: String) {
                    updateStateIfCurrent(version) {
                        copy(
                            isEnablingRecovery = false,
                            recoveryProgress = null,
                            generatedRecoveryKey = recoveryKey,
                        ) 
                    }
                }

                override fun onError(message: String) {
                    updateStateIfCurrent(version) {
                        copy(
                            isEnablingRecovery = false,
                            recoveryProgress = null,
                            error = "Recovery error: $message"
                        ) 
                    }
                }
            }

            updateStateIfCurrent(version) { copy(isEnablingRecovery = true, recoveryProgress = "Starting...") }
            val ok = port.setupRecovery(observer)
        }
    }

    fun dismissRecoveryKey() {
        updateState { copy(generatedRecoveryKey = null) }
    }

    private fun loadAccountManagementUrl() {
        val version = accountDataVersion

        launch {
            val port = service.portOrNull ?: return@launch
            val url = port.accountManagementUrl()
            updateStateIfCurrent(version) { copy(accountManagementUrl = url) }
        }
    }

    fun <T> updateSetting(name: String, value: T) {
        launch {
            @Suppress("UNCHECKED_CAST")
            settingsRepository.set(name, value as Any)
        }
    }

    suspend fun executeSettingAction(actionClass: KClass<out SettingAction>) {
        if (actionClass == OpenNotificationRulesAction::class) {
            _events.send(Event.NavigateToNotificationRules)
            return
        }
        ActionRegistry.execute(actionClass)
    }

    fun setSelectedTab(index: Int) {
        updateState { copy(selectedTab = index) }
    }

    fun refreshDevices() {
        val version = accountDataVersion

        launch(onError = { t ->
            if (isCurrentAccountData(version)) {
                updateState {
                    copy(
                        isLoadingDevices = false,
                        error = "Failed to load devices: ${t.message}"
                    )
                }
            }
        }) {
            updateStateIfCurrent(version) { copy(isLoadingDevices = true, error = null) }
            val devices = service.listMyDevices()
            updateStateIfCurrent(version) {
                copy(devices = devices, isLoadingDevices = false)
            }
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

        val version = accountDataVersion

        launch {
            val port = service.portOrNull
            if (port == null || !service.isLoggedInSuspend()) {
                if (isCurrentAccountData(version)) {
                    _events.send(Event.ShowError("Not logged in"))
                }
                return@launch
            }

            updateStateIfCurrent(version) { copy(isSubmittingRecoveryKey = true, error = null) }
            val result = port.recoverWithKey(key)
            if (result.isSuccess) {
                updateStateIfCurrent(version) {
                    copy(isSubmittingRecoveryKey = false, recoverySubmitSuccess = true)
                }
                if (isCurrentAccountData(version)) {
                    _events.send(Event.ShowSuccess("Recovery successful"))
                }
            } else {
                updateStateIfCurrent(version) {
                    copy(
                        isSubmittingRecoveryKey = false,
                        error = result.toUserMessage("Recovery failed")
                    )
                }
            }
        }
    }

    fun refreshIgnored() {
        val version = accountDataVersion

        launch {
            val port = service.portOrNull ?: return@launch
            val list = runCatching { port.ignoredUsers() }.getOrElse { emptyList() }
            updateStateIfCurrent(version) { copy(ignoredUsers = list) }
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
        val version = accountDataVersion

        launch {
            val port = service.portOrNull ?: return@launch
            val myId = port.whoami() ?: return@launch
            val result = port.getPresence(myId)
            if (result != null) {
                updateStateIfCurrent(version) {
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

            if (result.isSuccess) _events.send(Event.ShowSuccess("Status updated"))
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
