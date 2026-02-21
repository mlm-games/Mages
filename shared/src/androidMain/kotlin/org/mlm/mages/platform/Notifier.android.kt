package org.mlm.mages.platform

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext
import org.mlm.mages.MatrixService
import org.mlm.mages.push.PREF_INSTANCE
import org.mlm.mages.push.PusherReconciler
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.shared.R

private fun parseNotifiedRooms(json: String): Set<String> {
    if (json.isBlank()) return emptySet()
    return runCatching { Json.decodeFromString<Set<String>>(json) }.getOrElse { emptySet() }
}

actual object Notifier {
    private const val CHANNEL_ID = "messages"
    private var currentRoomId: String? = null

    private fun appContextOrNull(): Context? =
        runCatching { GlobalContext.get().get<Context>() }.getOrNull()

    actual fun notifyRoom(title: String, body: String) {
        val ctx = appContextOrNull() ?: return
        val mgr = ctx.getSystemService<NotificationManager>() ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }

        val activeCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mgr.activeNotifications.count { it.notification.channelId == CHANNEL_ID }
        } else 0

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setNumber(activeCount + 1)
            .setAutoCancel(true)
            .build()

        mgr.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }

    actual fun setCurrentRoom(roomId: String?) {
        currentRoomId = roomId
        if (roomId != null) {
            val ctx = appContextOrNull() ?: return
            runBlocking {
                val settingsRepo = SettingsProvider.get(ctx)
                val settings = settingsRepo.flow.first()
                val notifiedRooms = parseNotifiedRooms(settings.notifiedRoomsJson)
                if (notifiedRooms.contains(roomId)) {
                    val updated = notifiedRooms - roomId
                    settingsRepo.update { it.copy(notifiedRoomsJson = Json.encodeToString(updated)) }
                }
            }
        }
    }

    actual fun setWindowFocused(focused: Boolean) {
        // Not needed on Android
    }

    actual fun shouldNotify(roomId: String, senderIsMe: Boolean): Boolean {
        if (senderIsMe) return false
        if (currentRoomId == roomId) return false
        return true
    }
}

@Composable
actual fun BindLifecycle(service: MatrixService) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner, service) {
        val obs = object : DefaultLifecycleObserver {

            override fun onStart(owner: LifecycleOwner) {
                scope.launch {
                    runCatching { service.initFromDisk() }

                    val port = service.portOrNull ?: return@launch
                    if (!service.isLoggedIn()) return@launch

                    runCatching { port.enterForeground() }
                    runCatching { service.resetSyncState() }
                    runCatching { service.startSupervisedSync() }

                    val ctx = runCatching { GlobalContext.get().get<Context>() }.getOrNull()
                    if (ctx != null) {
                        runCatching { PusherReconciler.ensureServerPusherRegistered(ctx, PREF_INSTANCE) }
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                scope.launch {
                    val port = service.portOrNull ?: return@launch
                    runCatching { port.enterBackground() }
                }
            }
        }

        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
}

@Composable
actual fun rememberQuitApp(): () -> Unit {
    val context = LocalContext.current
    return { (context as? Activity)?.finishAffinity() }
}

@Composable
actual fun BindNotifications(
    service: MatrixService,
    settingsRepository: SettingsRepository<AppSettings>
) { // Only for jvm, android uses push
}