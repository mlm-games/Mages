package org.mlm.mages.push

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.mlm.mages.R
import org.mlm.mages.activities.MainActivity

class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val roomName = intent?.getStringExtra(EXTRA_ROOM_NAME) ?: "Ongoing call"
        startForeground(NOTIFICATION_ID, buildNotification(roomName))

        return START_NOT_STICKY
    }

    private fun buildNotification(roomName: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AppNotificationChannels.CHANNEL_CALL_ONGOING)
            .setSmallIcon(R.drawable.ic_notif_status_bar)
            .setContentTitle("Ongoing call")
            .setContentText(roomName)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                R.drawable.ic_notif_status_bar, "End",
                stopPendingIntent
            )
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val NOTIFICATION_ID = "call_foreground_service".hashCode()

        private const val ACTION_STOP = "org.mlm.mages.push.CallForegroundService.STOP"
        private const val EXTRA_ROOM_NAME = "room_name"

        fun start(context: Context, roomName: String) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                putExtra(EXTRA_ROOM_NAME, roomName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }
}
