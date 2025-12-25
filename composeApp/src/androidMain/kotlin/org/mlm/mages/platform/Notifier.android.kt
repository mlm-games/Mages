package org.mlm.mages.platform

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.mlm.mages.MatrixService
import org.mlm.mages.R
import org.mlm.mages.matrix.MatrixProvider
import org.mlm.mages.push.PREF_INSTANCE
import org.mlm.mages.push.PusherReconciler

actual object Notifier {
    private const val CHANNEL_ID = "messages"
    private var currentRoomId: String? = null

    actual fun notifyRoom(title: String, body: String) {
        val ctx = AppCtx.get() ?: return
        val mgr = ctx.getSystemService<NotificationManager>() ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        mgr.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }

    actual fun setCurrentRoom(roomId: String?) {
        currentRoomId = roomId
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
    val ctx = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner, service) {
        val obs = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch {
                    val ready = MatrixProvider.getReady(ctx)
                    if (ready != null) {
                        runCatching { service.port.enterForeground() }

                        MatrixProvider.ensureSyncStarted()

                        runCatching { PusherReconciler.ensureServerPusherRegistered(ctx, PREF_INSTANCE) }
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                scope.launch {
                    runCatching { service.port.enterBackground() }
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
    return {
        (context as? Activity)?.finishAffinity()
    }
}

@Composable
actual fun BindNotifications(
    service: MatrixService,
    dataStore: DataStore<Preferences>
) { // Only for jvm, android uses push
}