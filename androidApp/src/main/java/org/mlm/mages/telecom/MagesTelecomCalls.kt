package org.mlm.mages.telecom

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import org.mlm.mages.activities.CallActivity
import org.mlm.mages.push.AppNotificationChannels
import org.mlm.mages.push.CallForegroundService
import org.mlm.mages.shared.R

object MagesTelecomCalls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val setupMutex = Mutex()
    private val sessions = mutableMapOf<String, CallControlScope?>()
    private val jobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    @Volatile private var registered = false
    @Volatile private var appCtx: Context? = null

    @SuppressLint("MissingPermission")
    fun register(appContext: Context) {
        if (registered) return
        appCtx = appContext.applicationContext
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
                    runCatching {
                        sessions.remove(key)?.disconnect(
                            android.telecom.DisconnectCause(
                                android.telecom.DisconnectCause.LOCAL
                            )
                        )
                    }
                    jobs.remove(key)?.cancel()
                    appCtx?.let { cancelSessionNotif(it, key) }
                }
            }
            if (jobs.containsKey(roomId)) return
            jobs[roomId] = scope.launch {
                runOngoingCall(appContext, roomId, roomName, isVideo, onRemoteDisconnect)
            }
        }
    }

    suspend fun removeCallSync(sessionKey: String) {
        val child: Job? = setupMutex.withLock {
            runCatching {
                sessions.remove(sessionKey)?.disconnect(
                    android.telecom.DisconnectCause(
                        android.telecom.DisconnectCause.LOCAL
                    )
                )
            }
            appCtx?.let { cancelSessionNotif(it, sessionKey) }
            jobs.remove(sessionKey)
        }
        runCatching { withTimeoutOrNull(8_000) { child?.join() } }
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
                sessions[roomId] = this
                postOngoingCallStyle(appContext.applicationContext, roomId, roomName, isVideo)
            }
        } catch (e: Exception) {
            Logger.w(e) { "Telecom: addCall(ongoing) failed, FGS fallback covers bg" }
        } finally {
            setupMutex.withLock {
                sessions.remove(roomId)
                jobs.remove(roomId)
            }
            appCtx?.let { cancelSessionNotif(it, roomId) }
        }
    }

    private fun postOngoingCallStyle(ctx: Context, roomId: String, roomName: String, isVideo: Boolean) {        AppNotificationChannels.ensureCreated(ctx)
        val openIntent = PendingIntent.getActivity(
            ctx, ("telecom_open_$roomId").hashCode(),
            Intent(ctx, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val endIntent = PendingIntent.getService(
            ctx, ("telecom_end_$roomId").hashCode(),
            Intent(ctx, CallForegroundService::class.java).apply {
                action = CallForegroundService.ACTION_END_CALL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val caller = Person.Builder().setName(roomName).setKey(roomId).build()
        val style = NotificationCompat.CallStyle.forOngoingCall(caller, endIntent)
        if (isVideo) style.setIsVideo(true)
        val notif = NotificationCompat.Builder(ctx, AppNotificationChannels.CHANNEL_CALL_ONGOING)
            .setSmallIcon(R.drawable.ic_notif_status_bar)
            .setContentTitle("Ongoing call")
            .setContentText(roomName)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setStyle(style)
            .addPerson(caller)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(("telecom_ongoing_$roomId").hashCode(), notif)
    }

    private fun cancelSessionNotif(ctx: Context, roomId: String) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(("telecom_ongoing_$roomId").hashCode())
        }
    }
}
