package org.mlm.mages.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * SOT for Android notification channels.
 *
 * Call ensureCreated(context) from:
 *  - Application.onCreate (best effort)
 *  - any background entrypoint (PushService / Worker / Receiver) before posting notifications
 *
 * Safe to call repeatedly.
 */
object AppNotificationChannels {
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_MESSAGES_SILENT = "messages_silent"
    const val CHANNEL_CALLS = "calls"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Messages (normal)
        if (mgr.getNotificationChannel(CHANNEL_MESSAGES) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    "Messages",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Message notifications"
                    enableVibration(true)
                }
            )
        }

        // Messages (silent)
        if (mgr.getNotificationChannel(CHANNEL_MESSAGES_SILENT) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MESSAGES_SILENT,
                    "Messages (Silent)",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Message notifications (no sound)"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }

        // Calls
        if (mgr.getNotificationChannel(CHANNEL_CALLS) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CALLS,
                    "Calls",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Incoming calls"
                    setSound(null, null) // keep if you want silent ring by default
                    enableVibration(true)
                }
            )
        }
    }
}