package org.mlm.mages.telecom

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
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
import kotlin.coroutines.coroutineContext
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager

object MagesTelecomCalls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val setupMutex = Mutex()
    private val sessions = mutableMapOf<String, CallControlScope?>()
    private val jobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    @Volatile private var registered = false

    @SuppressLint("MissingPermission")
    fun register(appContext: Context) {
        if (registered) return
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

    suspend fun addOngoingCallSync(
        appContext: Context,
        roomId: String,
        roomName: String,
        isVideo: Boolean = true,
        onRemoteDisconnect: (() -> Unit)? = null,
    ) {
        setupMutex.withLock {
            for (key in (sessions.keys + jobs.keys).toSet()) {
                if (!key.startsWith("in:") && key != roomId) {
                    val swept = runCatching {
                        sessions.remove(key)?.disconnect(
                            android.telecom.DisconnectCause(
                                android.telecom.DisconnectCause.LOCAL
                            )
                        )
                    }
                    Logger.i { "Telecom: sweep-disconnect $key -> $swept" }
                    jobs.remove(key)?.cancel()
                }
            }
            if (jobs.containsKey(roomId)) return
            jobs[roomId] = scope.launch {
                runOngoingCall(appContext, roomId, roomName, isVideo, onRemoteDisconnect)
            }
        }
    }

    suspend fun removeCallSync(sessionKey: String) {
        val self = coroutineContext[Job]
        val child: Job? = setupMutex.withLock {
            val told = runCatching {
                sessions.remove(sessionKey)?.disconnect(
                    android.telecom.DisconnectCause(
                        android.telecom.DisconnectCause.LOCAL
                    )
                )
            }
            Logger.i { "Telecom: remove-disconnect $sessionKey -> $told" }
            jobs.remove(sessionKey)?.also { it.cancel() }
        }
        if (child != null && child !== self) {
            runCatching { withTimeoutOrNull(8_000) { child.join() } }
        }
    }

    private suspend fun runOngoingCall(
        appContext: Context,
        roomId: String,
        roomName: String,
        isVideo: Boolean,
        onRemoteDisconnect: (() -> Unit)?,
    ) {
        val mgr = try {
            CallsManager(appContext.applicationContext)
        } catch (e: Exception) {
            Logger.w(e) { "Telecom: CallsManager() failed" }
            return
        }
        val attrs = CallAttributesCompat(
            displayName = roomName,
            address = Uri.parse("mages:$roomId"),
            direction = CallAttributesCompat.DIRECTION_OUTGOING,
            callType = if (isVideo) CallAttributesCompat.CALL_TYPE_VIDEO_CALL
            else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        try {
            mgr.addCall(
                attrs,
                { _: Int -> /* onAnswer (already active) */ },
                {
                    Logger.i { "Telecom: remote disconnect $roomId" }
                    onRemoteDisconnect?.invoke()
                    sessions.remove(roomId)
                },
                { /* onActive */ },
                { /* onInactive (hold) */ },
            ) {
                val control = this
                sessions[roomId] = control
                scope.launch {
                    val setActiveResult = runCatching { control.setActive() }
                    Logger.i { "Telecom: setActive $roomId -> $setActiveResult" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e) { "Telecom: addCall(ongoing) failed, FGS fallback covers bg" }
        } finally {
            setupMutex.withLock {
                sessions.remove(roomId)
                jobs.remove(roomId)
            }
        }
    }
}
