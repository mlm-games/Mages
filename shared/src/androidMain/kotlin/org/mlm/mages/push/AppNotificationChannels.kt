package org.mlm.mages.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.provider.Settings

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
    const val CHANNEL_CALLS = "calls_v3"
    const val CHANNEL_CALLS_SILENT = "calls_silent"
    const val CHANNEL_INVITES = "invites"
    const val CHANNEL_CALL_ONGOING = "call_ongoing"

    private val legacyCallChannels = listOf("calls", "calls_v2")

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // One time Migration, TODO: Delete later
        for (legacyId in legacyCallChannels) {
            mgr.getNotificationChannel(legacyId)?.let {
                mgr.deleteNotificationChannel(legacyId)
            }
        }

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
                    NotificationManager.IMPORTANCE_MAX
                ).apply {
                    description = "Incoming calls"
                    setSound(
                        Settings.System.DEFAULT_RINGTONE_URI,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setLegacyStreamType(AudioManager.STREAM_RING)
                            .build()
                    )
                    enableVibration(true)
                }
            )
        }

        // Calls (silent)
        if (mgr.getNotificationChannel(CHANNEL_CALLS_SILENT) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CALLS_SILENT,
                    "Calls (Silent)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Incoming calls (no sound)"
                    setSound(null, null)
                    enableVibration(true)
                }
            )
        }

        if (mgr.getNotificationChannel(CHANNEL_INVITES) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_INVITES,
                    "Room Invites",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Room invitation notifications"
                    enableVibration(true)
                }
            )
        }

        if (mgr.getNotificationChannel(CHANNEL_CALL_ONGOING) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CALL_ONGOING,
                    "Ongoing call",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Notification for ongoing calls"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    fun ensureBubblesAllowed(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.getNotificationChannel(CHANNEL_MESSAGES)?.let {
            it.setAllowBubbles(true)
            nm.createNotificationChannel(it)
        }
    }
}
