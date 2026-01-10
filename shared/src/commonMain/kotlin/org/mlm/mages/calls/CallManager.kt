package org.mlm.mages.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.CallIntent
import org.mlm.mages.matrix.CallWidgetObserver
import org.mlm.mages.platform.CallWebViewController

data class GlobalCallState(
    val roomId: String,
    val roomName: String,
    val sessionId: ULong,
    val widgetUrl: String,
    val widgetBaseUrl: String?,
    val minimized: Boolean = false,
    val pipX: Float = 24f,
    val pipY: Float = 120f,
    val pipW: Float = 220f,
    val pipH: Float = 140f,

)

class CallManager(
    private val service: MatrixService
) {
    private val _call = MutableStateFlow<GlobalCallState?>(null)
    val call: StateFlow<GlobalCallState?> = _call.asStateFlow()

    private var controller: CallWebViewController? = null
    private val pendingToWidget = ArrayDeque<String>()

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

    fun attachController(c: CallWebViewController?) {
        controller = c
        if (c != null) {
            val drained = pendingToWidget.toList()
            pendingToWidget.clear()
            drained.forEach { c.sendToWidget(it) }
        }
    }

    fun onMessageFromWidget(message: String) {
        val s = _call.value ?: return
        val port = service.portOrNull ?: return
        port.callWidgetFromWebview(s.sessionId, message)
    }

    fun onToWidgetFromSdk(message: String) {
        val c = controller
        if (c != null) c.sendToWidget(message) else pendingToWidget.addLast(message)
    }

    suspend fun startOrJoinCall(
        roomId: String,
        roomName: String,
        intent: CallIntent,
        elementCallUrl: String?,
        languageTag: String?,
        theme: String?,
        onToWidget: (String) -> Unit,
    ): Boolean {
        val port = service.portOrNull ?: return false

        val session = port.startElementCall(
            roomId = roomId,
            intent = intent,
            elementCallUrl = elementCallUrl,
            languageTag = languageTag,
            theme = theme,
            observer = object : CallWidgetObserver {
                override fun onToWidget(message: String) = onToWidget(message)
            }
        ) ?: return false

        _call.value = GlobalCallState(
            roomId = roomId,
            roomName = roomName,
            sessionId = session.sessionId,
            widgetUrl = session.widgetUrl,
            widgetBaseUrl = session.widgetBaseUrl,
            minimized = false
        )
        return true
    }

    fun endCall() {
        val s = _call.value ?: return
        val port = service.portOrNull
        runCatching { port?.stopElementCall(s.sessionId) }
        pendingToWidget.clear()
        controller = null
        _call.value = null
    }
}