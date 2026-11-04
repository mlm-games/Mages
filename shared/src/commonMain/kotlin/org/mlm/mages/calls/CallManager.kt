package org.mlm.mages.calls

import co.touchlab.kermit.Logger
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.CallIntent
import org.mlm.mages.matrix.CallWidgetObserver
import org.mlm.mages.platform.CallWebViewController
import org.mlm.mages.settings.AppSettings
import kotlin.random.Random
import kotlin.time.Clock

data class GlobalCallState(
    val roomId: String,
    val roomName: String,
    val sessionId: ULong,
    val widgetUrl: String,
    val widgetBaseUrl: String?,
    val parentUrl: String?,
    val minimized: Boolean = false,
    val startedAtMs: Long = 0L,
    val isAudio: Boolean = false,
    val incoming: Boolean = false,
    val pipX: Float = 24f,
    val pipY: Float = 120f,
    val pipW: Float = 220f,
    val pipH: Float = 140f,

)

private const val HANGUP_GRACE_MS = 1500L
private const val WIDGET_ENDED_GRACE_MS = 250L
private const val WIDGET_HANGUP_ACTION = "im.vector.hangup"

class CallManager(
    private val service: MatrixService,
    private val settings: SettingsRepository<AppSettings>,
) {
    private val _call = MutableStateFlow<GlobalCallState?>(null)
    val call: StateFlow<GlobalCallState?> = _call.asStateFlow()

    private val _externalHost = MutableStateFlow(false)
    val externalHost: StateFlow<Boolean> = _externalHost.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun setExternalHost(hosted: Boolean) {
        _externalHost.value = hosted
    }

    private var controller: CallWebViewController? = null
    private val pendingToWidget = Channel<String>(Channel.UNLIMITED)
    private val callStatusMutex = Mutex()

    init {
        scope.launch {
            settings.flow
                .map { it.shareCallStatus }
                .distinctUntilChanged()
                .drop(1)
                .collect { syncCallStatus(inCall = _call.value != null) }
        }
    }
    private var hangupRequested = false
    private var hangupSignal: CompletableDeferred<Unit>? = null

    var onCallStateChanged: ((GlobalCallState?) -> Unit)? = null

    fun isInCall(): Boolean = _call.value != null
    fun isInCall(roomId: String): Boolean = _call.value?.roomId == roomId

    fun setMinimized(min: Boolean) {
        _call.value = _call.value?.copy(minimized = min)
    }

    fun movePip(x: Float, y: Float) {
        _call.value = _call.value?.copy(pipX = x, pipY = y)
    }

    fun resizePip(wDp: Float, hDp: Float) {
        _call.value = _call.value?.copy(pipW = wDp, pipH = hDp)
    }

    fun attachController(c: CallWebViewController) {
        controller = c
        while (true) {
            val message = pendingToWidget.tryReceive().getOrNull() ?: break
            c.sendToWidget(message)
        }
    }

    fun detachController(c: CallWebViewController) {
        if (controller === c) controller = null
    }

    suspend fun onMessageFromWidget(message: String) {
        val s = _call.value ?: return
        val port = service.portOrNull ?: return
        port.callWidgetFromWebview(s.sessionId, message)
    }

    private fun onToWidgetFromSdk(sessionId: ULong, message: String) {
        if (_call.value?.sessionId != sessionId) return
        val c = controller
        if (c != null) c.sendToWidget(message) else pendingToWidget.trySend(message)
    }

    suspend fun startOrJoinCall(
        roomId: String,
        roomName: String,
        intent: CallIntent,
        elementCallUrl: String?,
        parentUrl: String?,
        languageTag: String?,
        theme: String?,
        incoming: Boolean = false,
    ): Boolean {
        val port = service.portOrNull ?: return false

        val current = _call.value
        if (current != null) {
            if (current.roomId == roomId) return true
            val signal = CompletableDeferred<Unit>()
            hangupSignal = signal
            hangupRequested = true
            val requested = requestWidgetHangup(current)
            if (requested) withTimeoutOrNull(HANGUP_GRACE_MS) { signal.await() }
            teardown(current)
        }

        val observerSessionId = MutableStateFlow<ULong?>(null)
        val session = port.startElementCall(
            roomId = roomId,
            intent = intent,
            elementCallUrl = elementCallUrl,
            parentUrl = parentUrl,
            languageTag = languageTag,
            theme = theme,
            observer = object : CallWidgetObserver {
                override fun onToWidget(message: String) {
                    observerSessionId.value?.let { onToWidgetFromSdk(it, message) }
                }
            }
        ) ?: return false
        observerSessionId.value = session.sessionId

        _call.value = GlobalCallState(
            roomId = roomId,
            roomName = roomName,
            sessionId = session.sessionId,
            widgetUrl = session.widgetUrl,
            widgetBaseUrl = session.widgetBaseUrl,
            parentUrl = session.parentUrl,
            minimized = false,
            startedAtMs = Clock.System.now().toEpochMilliseconds(),
            isAudio = intent.isAudioCall(),
            incoming = incoming
        )
        syncCallStatus(inCall = true)
        onCallStateChanged?.invoke(_call.value)
        return true
    }

    /**
     * Host-initiated end: the Element Call widget owns the call session, so it has to be
     * asked to leave first, otherwise the room keeps the call alive for everyone else.
     */
    fun endCall() {
        val s = _call.value ?: return
        if (hangupRequested) return
        hangupRequested = true
        val signal = CompletableDeferred<Unit>()
        hangupSignal = signal
        if (!requestWidgetHangup(s)) {
            hangupSignal = null
            teardown(s)
            return
        }
        scope.launch {
            withTimeoutOrNull(HANGUP_GRACE_MS) { signal.await() }
            teardown(s)
        }
    }

    /**
     * The call view went away. [widgetEnded] is true when the widget reported the end itself
     * (it already left the call server-side), false when the host closed the view.
     */
    fun onCallEnded(sessionId: ULong, widgetEnded: Boolean) {
        val s = _call.value ?: return
        if (s.sessionId != sessionId) return
        val signal = hangupSignal
        if (signal != null) {
            signal.complete(Unit)
            return
        }
        if (widgetEnded) {
            scope.launch {
                delay(WIDGET_ENDED_GRACE_MS)
                teardown(s)
            }
        } else {
            endCall()
        }
    }

    private fun requestWidgetHangup(s: GlobalCallState): Boolean {
        val c = controller ?: return false
        val requestId = "${Random.nextLong().toULong().toString(16)}-${Clock.System.now().toEpochMilliseconds()}"
        val message = "{\"api\":\"toWidget\",\"widgetId\":\"mages-ecall-${s.sessionId}\"," +
            "\"requestId\":\"$requestId\",\"action\":\"$WIDGET_HANGUP_ACTION\",\"data\":{}}"
        c.sendToWidget(message)
        return true
    }

    private fun teardown(s: GlobalCallState) {
        runCatching { service.portOrNull?.stopElementCall(s.sessionId) }
            .onFailure { Logger.w("Failed to stop element call session ${s.sessionId}: ${it.message}") }
        if (_call.value?.sessionId != s.sessionId) return
        while (pendingToWidget.tryReceive().isSuccess) {
        }
        controller = null
        _call.value = null
        _externalHost.value = false
        hangupRequested = false
        hangupSignal = null
        syncCallStatus(inCall = false)
        onCallStateChanged?.invoke(null)
    }

    private fun syncCallStatus(inCall: Boolean) {
        scope.launch {
            callStatusMutex.withLock {
                val port = service.portOrNull ?: return@withLock
                if (!inCall) {
                    runCatching { port.clearUserCall() }
                        .onFailure { Logger.w("Failed to clear m.call: ${it.message}") }
                    return@withLock
                }
                val enabled = runCatching {
                    settings.flow.map { it.shareCallStatus }.first()
                }.getOrDefault(false)
                if (!enabled) return@withLock
                val startedAt = _call.value?.startedAtMs
                    ?: Clock.System.now().toEpochMilliseconds()
                runCatching { port.setUserCall(startedAt) }
                    .onFailure { Logger.w("Failed to set m.call: ${it.message}") }
            }
        }
    }
}

private fun CallIntent.isAudioCall(): Boolean = when (this) {
    CallIntent.StartCallVoiceDm, CallIntent.JoinExistingVoiceDm -> true
    CallIntent.StartCall, CallIntent.JoinExisting -> false
}
