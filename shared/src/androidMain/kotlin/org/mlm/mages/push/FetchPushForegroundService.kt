package org.mlm.mages.push

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mlm.mages.shared.R
import kotlin.time.Duration.Companion.minutes

class FetchPushForegroundService : Service() {

    private val wakeLock: PowerManager.WakeLock by lazy {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
        }
    }

    private var isOnForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppNotificationChannels.ensureCreated(this)

        val notification = NotificationCompat.Builder(this, AppNotificationChannels.CHANNEL_FETCH_PUSH)
            .setSmallIcon(R.drawable.ic_notif_status_bar)
            .setContentTitle("Syncing notifications…")
            .setProgress(0, 0, true)
            .build()

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        }.onSuccess {
            isOnForeground = true
        }.onFailure {
            Log.e(TAG, "Failed to start in foreground", it)
            isOnForeground = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isOnForeground) {
            stopSelf()
            return START_NOT_STICKY
        }

        wakeLock.acquire(WAKELOCK_TIMEOUT_MS)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            MainScope().launch {
                delay(WAKELOCK_TIMEOUT_MS)
                if (isOnForeground) stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (isOnForeground) {
            if (wakeLock.isHeld) wakeLock.release()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        if (isOnForeground) stopSelf()
    }

    companion object {
        private const val TAG = "FetchPush"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "FetchPushService:WakeLock"
        private val WAKELOCK_TIMEOUT_MS = 3.minutes.inWholeMilliseconds
    }
}
