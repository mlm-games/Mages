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
import mages.shared.generated.resources.*
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.CopyUnifiedPushEndpointAction
import org.mlm.mages.settings.OpenBubbleSettingsAction
import org.mlm.mages.settings.OpenMediaCacheAction
import org.mlm.mages.settings.OpenMessageMenuSettingsAction
import org.mlm.mages.settings.OpenQuickReactionsAction
import org.mlm.mages.settings.OpenWallpaperAction
import org.mlm.mages.settings.OpenNotificationRulesAction
import org.mlm.mages.settings.OpenSystemNotificationSettingsAction
import org.mlm.mages.settings.ReRegisterUnifiedPushAction
import org.mlm.mages.settings.RequestNotificationPermissionAction
import org.mlm.mages.settings.SelectUnifiedPushDistributorAction
import org.mlm.mages.settings.TestNotificationAction
import org.mlm.mages.ui.AvatarEdit
import org.mlm.mages.ui.QrGrantPhase
import org.mlm.mages.ui.SecurityUiState
import org.mlm.mages.verification.VerificationCoordinator
import kotlin.reflect.KClass
import org.jetbrains.compose.resources.getString
import mages.shared.generated.resources.Res

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
        data object NavigateToMediaCache : Event()
        data object NavigateToWallpaper : Event()
        data object NavigateToMessageMenu : Event()
        data object NavigateToQuickReactions : Event()
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
        ActionRegistry.registerAction(OpenSystemNotificationSettingsAction::class, OpenSystemNotificationSettingsAction)
        ActionRegistry.registerAction(OpenBubbleSettingsAction::class, OpenBubbleSettingsAction)
        ActionRegistry.registerAction(OpenNotificationRulesAction::class, OpenNotificationRulesAction)
        ActionRegistry.registerAction(RequestNotificationPermissionAction::class, RequestNotificationPermissionAction)
        ActionRegistry.registerAction(TestNotificationAction::class, TestNotificationAction)
        ActionRegistry.registerAction(SelectUnifiedPushDistributorAction::class, SelectUnifiedPushDistributorAction)
        ActionRegistry.registerAction(ReRegisterUnifiedPushAction::class, ReRegisterUnifiedPushAction)
        ActionRegistry.registerAction(CopyUnifiedPushEndpointAction::class, CopyUnifiedPushEndpointAction)
        ActionRegistry.registerAction(OpenMediaCacheAction::class, OpenMediaCacheAction)
        ActionRegistry.registerAction(OpenWallpaperAction::class, OpenWallpaperAction)
        ActionRegistry.registerAction(OpenMessageMenuSettingsAction::class, OpenMessageMenuSettingsAction)
        ActionRegistry.registerAction(OpenQuickReactionsAction::class, OpenQuickReactionsAction)

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
        loadAccountManagementUrl()
        refreshKeyStorageState(forceFetch = true)
        updateShareHistoryState()
        loadProfile()
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
        loadAccountManagementUrl()
        refreshKeyStorageState(forceFetch = true)
        loadProfile()
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
        if (currentState.qrGrantActive) {
            service.portOrNull?.let { runCatching { it.cancelQrGrant() } }
        }
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
                _events.send(Event.ShowError(result.exceptionOrNull()?.message ?: getString(Res.string.failed_to_update_key_storage)))
            } else {
                refreshKeyStorageState(forceFetch = true)
            }
        }
    }

    fun openQrGrant() {
        updateState {
            copy(
                qrGrantActive = true,
                qrGrantPhase = QrGrantPhase.Scanning,
                qrGrantCheckCode = null,
                qrGrantVerificationUri = null,
                qrGrantError = null,
            )
        }
    }

    fun closeQrGrant() {
        service.portOrNull?.let { runCatching { it.cancelQrGrant() } }
        updateState {
            copy(
                qrGrantActive = false,
                qrGrantPhase = QrGrantPhase.Idle,
                qrGrantCheckCode = null,
                qrGrantVerificationUri = null,
                qrGrantError = null,
            )
        }
    }

    fun startQrGrant(qrBytes: ByteArray) {
        val port = service.portOrNull ?: return
        updateState {
            copy(
                qrGrantActive = true,
                qrGrantPhase = QrGrantPhase.Starting,
                qrGrantCheckCode = null,
                qrGrantVerificationUri = null,
                qrGrantError = null,
            )
        }

        launch(onError = { t ->
            updateState {
                copy(
                    qrGrantPhase = QrGrantPhase.Error,
                    qrGrantError = t.message ?: getString(Res.string.qr_grant_failed),
                )
            }
        }) {
            port.startQrGrant(
                qrBytes,
                object : MatrixPort.QrGrantObserver {
                    override fun onCheckCode(code: Int) {
                        if (!currentState.qrGrantActive) return
                        updateState {
                            copy(qrGrantPhase = QrGrantPhase.CheckCode, qrGrantCheckCode = code)
                        }
                    }

                    override fun onWaitingForAuth(verificationUri: String) {
                        if (!currentState.qrGrantActive) return
                        updateState {
                            copy(
                                qrGrantPhase = QrGrantPhase.WaitingForAuth,
                                qrGrantVerificationUri = verificationUri,
                            )
                        }
                    }

                    override fun onSyncingSecrets() {
                        if (!currentState.qrGrantActive) return
                        updateState { copy(qrGrantPhase = QrGrantPhase.SyncingSecrets) }
                    }

                    override fun onDone() {
                        if (!currentState.qrGrantActive) return
                        updateState { copy(qrGrantPhase = QrGrantPhase.Done) }
                        refreshDevices()
                    }

                    override fun onError(message: String) {
                        if (!currentState.qrGrantActive) return
                        updateState {
                            copy(qrGrantPhase = QrGrantPhase.Error, qrGrantError = message)
                        }
                    }
                },
            )
        }
    }

    fun confirmQrGrant() {
        val port = service.portOrNull ?: return
        updateState { copy(qrGrantPhase = QrGrantPhase.Confirming) }
        launch(onError = { t ->
            updateState {
                copy(
                    qrGrantPhase = QrGrantPhase.Error,
                    qrGrantError = t.message ?: getString(Res.string.qr_grant_failed),
                )
            }
        }) {
            port.confirmQrGrant()
        }
    }

    fun setupRecovery(isChange: Boolean = false) {
        val version = accountDataVersion

        launch {
            val port = service.portOrNull ?: return@launch

            if (isChange) {
                val resetting = getString(Res.string.resetting_recovery_key)
                updateStateIfCurrent(version) { copy(isEnablingRecovery = true, recoveryProgress = resetting) }
                val result = runCatching { port.resetRecoveryKey() }.getOrElse { e ->
                    val resetError = getString(
                        Res.string.could_not_reset_recovery_key,
                        e.message ?: getString(Res.string.unknown_error),
                    )
                    updateStateIfCurrent(version) {
                        copy(
                            isEnablingRecovery = false,
                            recoveryProgress = null,
                            error = resetError
                        )
                    }
                    return@launch
                }
                result.fold(
                    onSuccess = { key ->
                        if (key.isBlank()) {
                            val emptyKey = getString(Res.string.recovery_returned_an_empty_key)
                            updateStateIfCurrent(version) {
                                copy(isEnablingRecovery = false, recoveryProgress = null, error = emptyKey)
                            }
                        } else {
                            updateStateIfCurrent(version) {
                                copy(
                                    isEnablingRecovery = false,
                                    recoveryProgress = null,
                                    generatedRecoveryKey = key,
                                )
                            }
                        }
                    },
                    onFailure = { e ->
                        val resetError = getString(
                            Res.string.could_not_reset_recovery_key,
                            e.message ?: getString(Res.string.unknown_error),
                        )
                        updateStateIfCurrent(version) {
                            copy(
                                isEnablingRecovery = false,
                                recoveryProgress = null,
                                error = resetError
                            )
                        }
                    }
                )
                return@launch
            }

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
                    launch {
                        val text = getString(Res.string.recovery_error, message)
                        updateStateIfCurrent(version) {
                            copy(
                                isEnablingRecovery = false,
                                recoveryProgress = null,
                                error = text
                            )
                        }
                    }
                }
            }

            val starting = getString(Res.string.starting)
            updateStateIfCurrent(version) { copy(isEnablingRecovery = true, recoveryProgress = starting) }
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

    fun loadProfile() {
        val version = accountDataVersion
        launch { fetchProfile(version) }
        launch { fetchProfileFields(version) }
    }

    private suspend fun fetchProfile(version: Long) {
        val port = service.portOrNull ?: return
        updateStateIfCurrent(version) { copy(isLoadingProfile = true) }
        val result = runCatching { port.ownProfile() }
        updateStateIfCurrent(version) {
            copy(ownProfile = result.getOrNull(), isLoadingProfile = false, ownAvatarPath = null)
        }
        val profile = result.getOrNull()
        if (profile == null && isCurrentAccountData(version)) {
            _events.send(
                Event.ShowError(result.exceptionOrNull()?.message ?: getString(Res.string.could_not_load_your_profile))
            )
        }
        val avatarUrl = profile?.avatarUrl ?: return
        val path = runCatching { service.avatars.resolve(avatarUrl, px = 256) }.getOrNull()
        updateStateIfCurrent(version) { copy(ownAvatarPath = path) }
    }

    fun saveProfile(displayName: String, avatar: AvatarEdit) {
        val version = accountDataVersion

        launch {
            val port = service.portOrNull ?: return@launch
            val current = currentState.ownProfile ?: return@launch
            updateStateIfCurrent(version) { copy(isSavingProfile = true) }

            val failures = mutableListOf<String>()
            if (displayName.trim() != (current.displayName ?: "").trim()) {
                port.setDisplayName(displayName)
                    .onFailure { failures += it.message ?: getString(Res.string.display_name_was_rejected) }
            }
            when (avatar) {
                AvatarEdit.None -> Unit
                is AvatarEdit.Replace -> port.setAvatarFromPath(avatar.path, avatar.mime)
                    .onFailure { failures += it.message ?: getString(Res.string.profile_picture_was_rejected) }

                AvatarEdit.Remove -> port.removeAvatar()
                    .onFailure { failures += it.message ?: getString(Res.string.profile_picture_could_not_be_removed) }
            }

            updateStateIfCurrent(version) { copy(isSavingProfile = false) }
            val failure = failures.firstOrNull()
            if (failure != null) _events.send(Event.ShowError(failure))
            else _events.send(Event.ShowSuccess(getString(Res.string.profile_updated)))
            fetchProfile(version)
            fetchProfileFields(version)
        }
    }

    private suspend fun fetchProfileFields(version: Long) {
        val port = service.portOrNull ?: return
        if (!port.canSetProfileFields()) {
            updateStateIfCurrent(version) {
                copy(canSetProfileFields = false, profileFields = emptyList())
            }
            return
        }
        val fields = runCatching { port.ownProfileFields() }.getOrDefault(emptyList())
        updateStateIfCurrent(version) { copy(canSetProfileFields = true, profileFields = fields) }
    }

    fun saveProfileField(name: String, value: String) {
        val version = accountDataVersion
        launch {
            val port = service.portOrNull ?: return@launch
            updateStateIfCurrent(version) { copy(isSavingProfileField = true) }
            val saved = port.setProfileField(name.trim(), value)
            updateStateIfCurrent(version) { copy(isSavingProfileField = false) }
            if (saved.isFailure) {
                _events.send(Event.ShowError(getString(Res.string.profile_field_was_rejected)))
            } else {
                fetchProfileFields(version)
            }
        }
    }

    fun deleteProfileField(name: String) {
        val version = accountDataVersion
        launch {
            val port = service.portOrNull ?: return@launch
            updateStateIfCurrent(version) { copy(isSavingProfileField = true) }
            val removed = port.deleteProfileField(name)
            updateStateIfCurrent(version) { copy(isSavingProfileField = false) }
            if (removed.isFailure) {
                _events.send(Event.ShowError(getString(Res.string.profile_field_could_not_be_removed)))
            } else {
                fetchProfileFields(version)
            }
        }
    }

    fun updateSetting(name: String, value: Any?) {
        launch {
            settingsRepository.set(name, value)
        }
    }

    suspend fun executeSettingAction(actionClass: KClass<out SettingAction>) {
        if (actionClass == OpenNotificationRulesAction::class) {
            _events.send(Event.NavigateToNotificationRules)
            return
        }
        if (actionClass == OpenMediaCacheAction::class) {
            _events.send(Event.NavigateToMediaCache)
            return
        }
        if (actionClass == OpenWallpaperAction::class) {
            _events.send(Event.NavigateToWallpaper)
            return
        }
        if (actionClass == OpenMessageMenuSettingsAction::class) {
            _events.send(Event.NavigateToMessageMenu)
            return
        }
        if (actionClass == OpenQuickReactionsAction::class) {
            _events.send(Event.NavigateToQuickReactions)
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
                val deviceError = getString(Res.string.failed_to_load_devices, t.message ?: "")
                updateState {
                    copy(
                        isLoadingDevices = false,
                        error = deviceError
                    )
                }
            }
        }) {
            updateStateIfCurrent(version) { copy(isLoadingDevices = true, error = null) }
            val devices = service.listMyDevices().getOrThrow()
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
            launch {
                val enterARecoveryKeyFallback = getString(Res.string.enter_a_recovery_key)
                updateState { copy(error = enterARecoveryKeyFallback) }
            }
            return
        }

        val version = accountDataVersion

        launch {
            val port = service.portOrNull
            if (port == null || !service.isLoggedInSuspend()) {
                if (isCurrentAccountData(version)) {
                    _events.send(Event.ShowError(getString(Res.string.not_logged_in)))
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
                    _events.send(Event.ShowSuccess(getString(Res.string.recovery_successful)))
                }
            } else {
                val submitError = result.toUserMessage(getString(Res.string.recovery_failed))
                updateStateIfCurrent(version) {
                    copy(
                        isSubmittingRecoveryKey = false,
                        error = submitError
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
                _events.send(Event.ShowSuccess(getString(Res.string.user_unignored)))
            } else {
                _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.failed_to_unignore_user))))
            }
        }
    }

    private fun updateShareHistoryState() {
        val enabled = service.activeAccount.value?.enableShareHistoryOnInvite ?: true
        updateState { copy(enableShareHistoryOnInvite = enabled) }
    }

    fun setEnableShareHistoryOnInvite(enabled: Boolean) {
        launch {
            val account = service.activeAccount.value ?: return@launch
            val updated = account.copy(enableShareHistoryOnInvite = enabled)
            service.accountStore.updateAccount(updated)
            updateState { copy(enableShareHistoryOnInvite = enabled) }
        }
    }

    fun logout() {
        launch {
            val result = service.logout()
            if (result.isSuccess) _events.send(Event.LogoutSuccess)
            else _events.send(Event.ShowError(result.toUserMessage(getString(Res.string.logout_failed))))
        }
    }
}
