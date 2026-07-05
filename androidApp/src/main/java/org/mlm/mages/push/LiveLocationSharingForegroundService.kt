package org.mlm.mages.push

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.mlm.mages.activities.MainActivity

class LiveLocationSharingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val roomCount = intent?.getIntExtra(EXTRA_ROOM_COUNT, 1) ?: 1
        startForeground(NOTIFICATION_ID, buildNotification(roomCount))
        return START_STICKY
    }

    private fun buildNotification(roomCount: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AppNotificationChannels.CHANNEL_LIVE_LOCATION)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Sharing live location")
            .setContentText(if (roomCount == 1) "1 room" else "$roomCount rooms")
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val NOTIFICATION_ID = "live_location_foreground_service".hashCode()
        private const val ACTION_STOP = "org.mlm.mages.push.LiveLocationSharingForegroundService.STOP"
        private const val EXTRA_ROOM_COUNT = "room_count"

        fun start(context: Context, roomCount: Int) {
            val intent = Intent(context, LiveLocationSharingForegroundService::class.java).apply {
                putExtra(EXTRA_ROOM_COUNT, roomCount)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LiveLocationSharingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }
}
