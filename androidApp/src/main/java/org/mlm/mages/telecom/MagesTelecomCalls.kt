package org.mlm.mages.telecom

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Build
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import org.mlm.mages.push.CallAudioRouteBridge
import org.mlm.mages.push.CallAudioRouteHandler

object MagesTelecomCalls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val setupMutex = Mutex()
    private val sessions = mutableMapOf<String, CallControlScope?>()
    private val jobs = mutableMapOf<String, Job>()
    private val disconnectHandlers = mutableMapOf<String, () -> Unit>()
    private val bridges = mutableMapOf<String, RouteBridge>()
    @Volatile private var registered = false

    private fun ringingKey(roomId: String): String =
        if (roomId.startsWith("in:")) roomId else "in:$roomId"

    @SuppressLint("MissingPermission")
    fun register(appContext: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || registered) return
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM)) {
            Logger.w { "Telecom: FEATURE_TELECOM missing, skipping register" }
            return
        }
        try {
            val mgr = CallsManager(appContext)
            mgr.registerAppWithTelecom(
                CallsManager.CAPABILITY_BASELINE or
                    CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING
            )
            registered = true
            Logger.i { "Telecom: registered" }
        } catch (e: Exception) {
            Logger.w(e) { "Telecom: registerAppWithTelecom failed, fallback to FGS" }
        }
    }

    suspend fun addIncomingCall(
        appContext: Context,
        roomId: String,
        roomName: String,
        isVideo: Boolean,
        onAnswered: () -> Unit,
        onDeclined: () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val key = ringingKey(roomId)
        setupMutex.withLock {
            if (jobs.containsKey(key)) return
            if (sessions.containsKey(roomId) || jobs.containsKey(roomId)) return
            disconnectHandlers[roomId] = onDeclined
            val bridge = RouteBridge()
            bridges[roomId] = bridge
            jobs[key] = scope.launch {
                runCall(
                    appContext = appContext.applicationContext,
                    keys = listOf(key, roomId),
                    sessionKey = key,
                    roomId = roomId,
                    roomName = roomName,
                    isVideo = isVideo,
                    isIncoming = true,
                    answerOnStart = false,
                    installRoute = false,
                    onAnswer = onAnswered,
                    bridge = bridge,
                )
            }
        }
    }

    suspend fun addOngoingCallSync(
        appContext: Context,
        roomId: String,
        roomName: String,
        isVideo: Boolean = true,
        isIncoming: Boolean = false,
        onRemoteDisconnect: (() -> Unit)? = null,
    ) {
        setupMutex.withLock {
            val callType = if (isVideo) CallAttributesCompat.CALL_TYPE_VIDEO_CALL
            else CallAttributesCompat.CALL_TYPE_AUDIO_CALL
            sweep(roomId)
            val promoted = promoteRinging(roomId = roomId, callType = callType)
            if (promoted) {
                if (onRemoteDisconnect != null) {
                    disconnectHandlers[roomId] = onRemoteDisconnect
                } else {
                    disconnectHandlers.remove(roomId)
                }
                return
            }
            if (jobs.containsKey(roomId)) return
            if (onRemoteDisconnect != null) {
                disconnectHandlers[roomId] = onRemoteDisconnect
            }
            val bridge = RouteBridge()
            bridges[roomId] = bridge
            jobs[roomId] = scope.launch {
                runCall(
                    appContext = appContext.applicationContext,
                    keys = listOf(roomId, ringingKey(roomId)),
                    sessionKey = roomId,
                    roomId = roomId,
                    roomName = roomName,
                    isVideo = isVideo,
                    isIncoming = isIncoming,
                    answerOnStart = true,
                    installRoute = true,
                    onAnswer = null,
                    bridge = bridge,
                )
            }
        }
    }

    suspend fun removeCallSync(sessionKey: String) {
        val self = coroutineContext[Job]
        val child: Job? = setupMutex.withLock {
            val key = when {
                sessions.containsKey(sessionKey) || jobs.containsKey(sessionKey) -> sessionKey
                else -> ringingKey(sessionKey)
            }
            disconnectHandlers.remove(key.removePrefix("in:"))
            val told = runCatching {
                sessions.remove(key)?.disconnect(
                    android.telecom.DisconnectCause(
                        android.telecom.DisconnectCause.LOCAL
                    )
                )
            }
            Logger.i { "Telecom: remove-disconnect $key -> $told" }
            jobs.remove(key)?.also { it.cancel() }
        }
        if (child != null && child !== self) {
            runCatching { withTimeoutOrNull(8_000) { child.join() } }
        }
    }

    private suspend fun sweep(keepRoomId: String) {
        for (key in (sessions.keys + jobs.keys).toSet()) {
            if (key.startsWith("in:")) continue
            if (key == keepRoomId) continue
            disconnectHandlers.remove(key)
            val told = runCatching {
                sessions.remove(key)?.disconnect(
                    android.telecom.DisconnectCause(
                        android.telecom.DisconnectCause.LOCAL
                    )
                )
            }
            Logger.i { "Telecom: sweep-disconnect $key -> $told" }
            jobs.remove(key)?.cancel()
        }
    }

    private fun promoteRinging(roomId: String, callType: Int): Boolean {
        val key = ringingKey(roomId)
        val control = sessions.remove(key) ?: return false
        sessions[roomId] = control
        jobs.remove(key)?.let { jobs[roomId] = it }
        bridges[roomId]?.install(control)
        scope.launch {
            val result = runCatching { control.answer(callType) }
            Logger.i { "Telecom: answer(ringing) $roomId -> $result" }
        }
        return true
    }

    private suspend fun runCall(
        appContext: Context,
        keys: List<String>,
        sessionKey: String,
        roomId: String,
        roomName: String,
        isVideo: Boolean,
        isIncoming: Boolean,
        answerOnStart: Boolean,
        installRoute: Boolean,
        onAnswer: (() -> Unit)?,
        bridge: RouteBridge,
    ) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val mgr = try {
                CallsManager(appContext.applicationContext)
            } catch (e: Exception) {
                Logger.w(e) { "Telecom: CallsManager() failed" }
                return
            }
            val attrs = CallAttributesCompat(
                displayName = roomName,
                address = Uri.parse("mages:$roomId"),
                direction = if (isIncoming) CallAttributesCompat.DIRECTION_INCOMING
                else CallAttributesCompat.DIRECTION_OUTGOING,
                callType = if (isVideo) CallAttributesCompat.CALL_TYPE_VIDEO_CALL
                else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
            )
            mgr.addCall(
                attrs,
                {
                    Logger.i { "Telecom: system answer $roomId" }
                    val handler = onAnswer
                    if (handler != null) {
                        runCatching { handler() }
                            .onFailure { Logger.w(it) { "Telecom: answer handler failed $roomId" } }
                    }
                },
                {
                    Logger.i { "Telecom: remote disconnect $roomId" }
                    scope.launch {
                        val handler = setupMutex.withLock {
                            for (key in keys) sessions.remove(key)
                            disconnectHandlers.remove(roomId)
                        }
                        runCatching { handler?.invoke() }
                    }
                },
                { /* onActive */ },
                { /* onInactive (hold) */ },
            ) {
                sessions[sessionKey] = this
                if (installRoute) bridge.install(this)
                if (answerOnStart) {
                    scope.launch {
                        val result = runCatching {
                            if (isIncoming) answer(attrs.callType) else setActive()
                        }
                        Logger.i { "Telecom: ${if (isIncoming) "answer" else "setActive"} $roomId -> $result" }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e) { "Telecom: addCall failed, FGS fallback covers bg" }
        } finally {
            bridge.clear()
            if (bridges[roomId] === bridge) bridges.remove(roomId)
            setupMutex.withLock {
                for (key in keys) {
                    sessions.remove(key)
                    jobs.remove(key)
                }
                disconnectHandlers.remove(roomId)
            }
        }
    }

    private class RouteBridge {
        var routeHandler: CallAudioRouteHandler? = null
        var endpointsJob: Job? = null

        fun install(control: CallControlScope) {
            if (routeHandler != null) return
            val endpoints = AtomicReference<List<CallEndpointCompat>>(emptyList())
            val pendingRouteType = AtomicReference<Int?>(null)
            endpointsJob = MagesTelecomCalls.scope.launch {
                control.availableEndpoints.collect { available ->
                    endpoints.set(available)
                    val pending = pendingRouteType.getAndSet(null) ?: return@collect
                    available.firstOrNull { it.type == pending }?.let { endpoint ->
                        control.requestEndpointChange(endpoint)
                    }
                }
            }
            val handler = CallAudioRouteHandler { device ->
                val type = device.type.toCallEndpointType()
                if (type == CallEndpointCompat.TYPE_UNKNOWN) return@CallAudioRouteHandler false
                val available = endpoints.get()
                if (available.isEmpty()) {
                    pendingRouteType.set(type)
                    return@CallAudioRouteHandler false
                }
                val endpoint = available.firstOrNull { it.type == type }
                    ?: return@CallAudioRouteHandler false
                MagesTelecomCalls.scope.launch {
                    val result = control.requestEndpointChange(endpoint)
                    Logger.i { "Telecom: route ${device.type} -> $result" }
                }
                true
            }
            routeHandler = handler
            CallAudioRouteBridge.handler = handler
        }

        fun clear() {
            endpointsJob?.cancel()
            if (routeHandler != null && CallAudioRouteBridge.handler === routeHandler) {
                CallAudioRouteBridge.handler = null
            }
        }
    }
}

@Suppress("InlinedApi")
private fun Int.toCallEndpointType(): Int = when (this) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> CallEndpointCompat.TYPE_EARPIECE
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> CallEndpointCompat.TYPE_SPEAKER
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_USB_HEADSET -> CallEndpointCompat.TYPE_WIRED_HEADSET
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST -> CallEndpointCompat.TYPE_BLUETOOTH
    else -> CallEndpointCompat.TYPE_UNKNOWN
}
