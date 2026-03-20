package org.mlm.mages.verification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.SasPhase
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.VerifEvent
import org.mlm.mages.matrix.VerificationService
import org.mlm.mages.matrix.asVerificationService

data class VerificationUiState(
    val sasFlowId: String? = null,
    val sasPhase: SasPhase? = null,
    val sasOtherUser: String? = null,
    val sasOtherDevice: String? = null,
    val sasEmojis: List<String> = emptyList(),
    val sasError: String? = null,
    val sasIncoming: Boolean = false,
    val sasContinuePressed: Boolean = false,
)

class VerificationCoordinator(
    private val service: MatrixService
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(VerificationUiState())
    val state: StateFlow<VerificationUiState> = _state.asStateFlow()

    private var inboxToken: ULong? = null

    private var verificationService: VerificationService? = null

    init {
        scope.launch {
            service.isReady.first { it }

            service.activeAccount.collectLatest { account ->
                reset()
                if (account != null) {
                    startInboxIfPossible()
                    initVerificationService()
                }
            }
        }
    }

    private fun initVerificationService() {
        val port = service.portOrNull
        verificationService = port?.asVerificationService()
    }

    private fun reset() {
        inboxToken?.let { token ->
            runCatching { service.portOrNull?.stopVerificationInbox(token) }
        }
        inboxToken = null
        _state.value = VerificationUiState()
    }

    private fun startInboxIfPossible() {
        val port = service.portOrNull ?: return
        if (!service.isLoggedIn()) return

        inboxToken = port.startVerificationInbox(object : MatrixPort.VerificationInboxObserver {
            override fun onRequest(flowId: String, fromUser: String, fromDevice: String) {
                _state.value = _state.value.copy(
                    sasFlowId = flowId,
                    sasPhase = SasPhase.Requested,
                    sasOtherUser = fromUser,
                    sasOtherDevice = fromDevice,
                    sasEmojis = emptyList(),
                    sasError = null,
                    sasIncoming = true,
                    sasContinuePressed = false
                )
            }

            override fun onError(message: String) {
                _state.value = _state.value.copy(sasError = "Verification inbox: $message")
            }
        })
    }

    fun startSelfVerify(deviceId: String) {
        _state.value = _state.value.copy(
            sasFlowId = null,
            sasPhase = SasPhase.Requested,
            sasIncoming = false,
            sasOtherUser = runCatching { service.portOrNull?.whoami() }.getOrNull(),
            sasOtherDevice = deviceId,
            sasError = null
        )

        scope.launch {
            try {
                verificationService?.startDeviceVerification(deviceId)?.collect { event ->
                    handleVerifEvent(event)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    sasPhase = SasPhase.Failed,
                    sasError = e.message ?: "Verification failed to start",
                    sasContinuePressed = false
                )
            } ?: run {
                _state.value = _state.value.copy(
                    sasPhase = SasPhase.Failed,
                    sasError = "Verification service unavailable"
                )
            }
        }
    }

    fun startUserVerify(userId: String) {
        _state.value = _state.value.copy(
            sasFlowId = null,
            sasPhase = SasPhase.Requested,
            sasIncoming = false,
            sasOtherUser = userId,
            sasError = null
        )

        scope.launch {
            try {
                verificationService?.startUserVerification(userId)?.collect { event ->
                    handleVerifEvent(event)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    sasPhase = SasPhase.Failed,
                    sasError = e.message ?: "Verification failed to start",
                    sasContinuePressed = false
                )
            } ?: run {
                _state.value = _state.value.copy(
                    sasPhase = SasPhase.Failed,
                    sasError = "Verification service unavailable"
                )
            }
        }
    }

    private fun handleVerifEvent(event: VerifEvent) {
        when (event) {
            is VerifEvent.Requested -> {
                _state.value = _state.value.copy(
                    sasFlowId = event.flow_id,
                    sasPhase = SasPhase.Requested,
                    sasError = null,
                    sasContinuePressed = false
                )
            }
            is VerifEvent.Ready -> {
                _state.value = _state.value.copy(
                    sasPhase = SasPhase.Ready,
                    sasError = null
                )
            }
            is VerifEvent.SasStarted -> {
                _state.value = _state.value.copy(
                    sasPhase = SasPhase.Started,
                    sasError = null
                )
            }
            is VerifEvent.KeysExchanged -> {
                _state.value = _state.value.copy(
                    sasOtherUser = event.other_user,
                    sasOtherDevice = event.other_device,
                    sasEmojis = event.emojis.map { it.symbol },
                    sasPhase = SasPhase.Emojis,
                    sasError = null
                )
            }
            is VerifEvent.Confirmed -> {
                _state.value = _state.value.copy(
                    sasPhase = SasPhase.Confirmed,
                    sasError = null
                )
            }
            is VerifEvent.Done -> {
                _state.value = _state.value.copy(
                    sasPhase = SasPhase.Done,
                    sasError = null
                )
                _state.value = VerificationUiState()
            }
            is VerifEvent.Cancelled -> {
                _state.value = _state.value.copy(
                    sasPhase = SasPhase.Cancelled,
                    sasError = event.reason
                )
                _state.value = VerificationUiState()
            }
            is VerifEvent.Error -> {
                _state.value = _state.value.copy(
                    sasPhase = SasPhase.Failed,
                    sasError = event.message,
                    sasContinuePressed = false
                )
            }
        }
    }

    fun acceptOrContinue() {
        val flowId = _state.value.sasFlowId
        if (flowId == null) {
            _state.value = _state.value.copy(sasError = "No active verification")
            return
        }

        val phase = _state.value.sasPhase
        val otherUser = _state.value.sasOtherUser

        _state.value = _state.value.copy(sasContinuePressed = true, sasError = null)

        scope.launch {
            when (phase) {
                SasPhase.Requested -> {
                    try {
                        verificationService
                            ?.acceptAndObserveVerification(flowId, otherUser ?: "")
                            ?.collect { event -> handleVerifEvent(event) }
                            ?: run {
                                _state.value = _state.value.copy(
                                    sasContinuePressed = false,
                                    sasError = "Verification service unavailable"
                                )
                            }
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(
                            sasPhase = SasPhase.Failed,
                            sasError = e.message ?: "Accept failed",
                            sasContinuePressed = false
                        )
                    }
                }
                SasPhase.Ready, SasPhase.Started -> {
                    val ok = try {
                        verificationService?.acceptSas(flowId, otherUser ?: "") ?: false
                    } catch (e: Throwable) { false }

                    val cur = _state.value
                    if (cur.sasFlowId == flowId) {
                        _state.value = cur.copy(
                            sasContinuePressed = false,
                            sasError = if (!ok) "Continue failed" else null
                        )
                    }
                }
                else -> {
                    _state.value = _state.value.copy(sasContinuePressed = false)
                }
            }
        }
    }

    fun confirm() {
        val flowId = _state.value.sasFlowId ?: return

        scope.launch {
            val ok = verificationService?.confirmSas(flowId) ?: false
            if (!ok) _state.value = _state.value.copy(sasError = "Confirm failed")
        }
    }

    fun cancel() {
        val flowId = _state.value.sasFlowId ?: return

        scope.launch {
            val ok = verificationService?.cancelVerification(flowId) ?: false
            if (!ok) {
                _state.value = _state.value.copy(sasError = "Cancel failed")
            } else {
                _state.value = VerificationUiState()
            }
        }
    }
}