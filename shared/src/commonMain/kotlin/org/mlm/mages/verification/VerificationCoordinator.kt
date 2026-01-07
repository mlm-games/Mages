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
import org.mlm.mages.matrix.VerificationObserver
import org.mlm.mages.matrix.MatrixPort

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

    init {
        scope.launch {
            service.isReady.first { it }

            service.activeAccount.collectLatest { account ->
                reset()
                if (account != null) {
                    startInboxIfPossible()
                }
            }
        }
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

    private fun commonObserver(): VerificationObserver = object : VerificationObserver {
        override fun onPhase(flowId: String, phase: SasPhase) {
            _state.value = _state.value.copy(
                sasFlowId = flowId,
                sasPhase = phase,
                sasError = null,
                sasContinuePressed = false
            )
            if (phase == SasPhase.Done || phase == SasPhase.Cancelled) {
                _state.value = VerificationUiState()
            }
        }

        override fun onEmojis(flowId: String, otherUser: String, otherDevice: String, emojis: List<String>) {
            _state.value = _state.value.copy(
                sasFlowId = flowId,
                sasOtherUser = otherUser,
                sasOtherDevice = otherDevice,
                sasEmojis = emojis,
                sasError = null
            )
        }

        override fun onError(flowId: String, message: String) {
            _state.value = _state.value.copy(
                sasFlowId = flowId,
                sasError = message,
                sasContinuePressed = false
            )
        }
    }

    fun startSelfVerify(deviceId: String) {
        val port = service.portOrNull ?: return
        val myUserId = runCatching { port.whoami() }.getOrNull() ?: return

        _state.value = _state.value.copy(
            sasIncoming = false,
            sasOtherUser = myUserId,
            sasOtherDevice = deviceId,
            sasError = null
        )

        scope.launch {
            val flowId = runCatching { port.startSelfSas(deviceId, commonObserver()) }
                .getOrNull()
                .orEmpty()

            if (flowId.isBlank()) {
                _state.value = _state.value.copy(sasError = "Failed to start verification")
            } else {
                _state.value = _state.value.copy(sasFlowId = flowId)
            }
        }
    }

    fun startUserVerify(userId: String) {
        val port = service.portOrNull ?: return

        _state.value = _state.value.copy(
            sasIncoming = false,
            sasOtherUser = userId,
            sasError = null
        )

        scope.launch {
            val flowId = runCatching { port.startUserSas(userId, commonObserver()) }
                .getOrNull()
                .orEmpty()

            if (flowId.isBlank()) {
                _state.value = _state.value.copy(sasError = "Failed to start verification")
            } else {
                _state.value = _state.value.copy(sasFlowId = flowId)
            }
        }
    }

    /**
     * Accept/Continue semantics:
     * - Requested => accept VerificationRequest (request-level accept)
     * - Ready/Started => accept SAS (sas-level accept; UI "Continue")
     */
    fun acceptOrContinue() {
        val port = service.portOrNull
        if (port == null) {
            println(">>> acceptOrContinue: port is NULL!")
            _state.value = _state.value.copy(sasError = "Not connected")
            return
        }

        val s = _state.value
        val flowId = s.sasFlowId
        if (flowId == null) {
            println(">>> acceptOrContinue: flowId is NULL!")
            _state.value = _state.value.copy(sasError = "No active verification")
            return
        }

        println(">>> acceptOrContinue: flowId=$flowId, phase=${s.sasPhase}")

        _state.value = s.copy(sasContinuePressed = true, sasError = null)

        val obs = commonObserver()

        scope.launch {
            println(">>> acceptOrContinue: launching coroutine for phase=${_state.value.sasPhase}")
            val ok: Boolean = try {
                when (_state.value.sasPhase) {
                    SasPhase.Requested -> {
                        println(">>> calling acceptVerificationRequest")
                        port.acceptVerificationRequest(flowId, _state.value.sasOtherUser, obs)
                    }
                    SasPhase.Ready, SasPhase.Started -> {
                        println(">>> calling acceptSas")
                        port.acceptSas(flowId, _state.value.sasOtherUser, obs)
                    }
                    else -> {
                        println(">>> unexpected phase: ${_state.value.sasPhase}")
                        false
                    }
                }
            } catch (e: Throwable) {
                println(">>> acceptOrContinue exception: ${e.message}")
                e.printStackTrace()
                false
            } finally {
                val cur = _state.value
                if (cur.sasFlowId == flowId) {
                    _state.value = cur.copy(sasContinuePressed = false)
                }
            }

            println(">>> acceptOrContinue result: $ok")
            if (!ok) {
                val cur = _state.value
                if (cur.sasFlowId == flowId) {
                    _state.value = cur.copy(sasError = "Continue failed")
                }
            }
        }
    }

    fun confirm() {
        val port = service.portOrNull ?: return
        val flowId = _state.value.sasFlowId ?: return

        scope.launch {
            val ok = runCatching { port.confirmVerification(flowId) }.getOrDefault(false)
            if (!ok) _state.value = _state.value.copy(sasError = "Confirm failed")
        }
    }

    fun cancel() {
        val port = service.portOrNull ?: return
        val s = _state.value
        val flowId = s.sasFlowId ?: return

        scope.launch {
            val ok = if (s.sasPhase == SasPhase.Requested) {
                runCatching { port.cancelVerificationRequest(flowId, s.sasOtherUser) }.getOrDefault(false)
            } else {
                runCatching { port.cancelVerification(flowId) }.getOrDefault(false)
            }

            if (!ok) {
                _state.value = _state.value.copy(sasError = "Cancel failed")
            } else {
                _state.value = VerificationUiState()
            }
        }
    }
}