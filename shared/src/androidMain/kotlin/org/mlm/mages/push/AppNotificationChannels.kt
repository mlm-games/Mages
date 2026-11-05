package org.mlm.mages.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import org.mlm.mages.shared.R

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
    const val CHANNEL_MESSAGES = "messages_v2"
    const val CHANNEL_MESSAGES_SILENT = "messages_silent"
    const val CHANNEL_CALLS = "calls_v3"
    const val CHANNEL_CALLS_SILENT = "calls_silent"
    const val CHANNEL_INVITES = "invites"
    const val CHANNEL_CALL_ONGOING = "call_ongoing"
    const val CHANNEL_LIVE_LOCATION = "live_location"
    const val CHANNEL_FETCH_PUSH = "fetch_push"

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

        channel(context, mgr, CHANNEL_MESSAGES, R.string.notif_channel_messages,
            R.string.notif_channel_messages_desc, NotificationManager.IMPORTANCE_DEFAULT) {
            enableVibration(true)
        }
        channel(context, mgr, CHANNEL_MESSAGES_SILENT, R.string.notif_channel_messages_silent,
            R.string.notif_channel_messages_silent_desc, NotificationManager.IMPORTANCE_LOW) {
            setSound(null, null)
            enableVibration(false)
        }
        channel(context, mgr, CHANNEL_CALLS, R.string.notif_channel_calls,
            R.string.notif_channel_calls_desc, NotificationManager.IMPORTANCE_MAX) {
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
        channel(context, mgr, CHANNEL_CALLS_SILENT, R.string.notif_channel_calls_silent,
            R.string.notif_channel_calls_silent_desc, NotificationManager.IMPORTANCE_HIGH) {
            setSound(null, null)
            enableVibration(true)
        }
        channel(context, mgr, CHANNEL_INVITES, R.string.notif_channel_invites,
            R.string.notif_channel_invites_desc, NotificationManager.IMPORTANCE_HIGH) {
            enableVibration(true)
        }
        channel(context, mgr, CHANNEL_CALL_ONGOING, R.string.notif_channel_ongoing,
            R.string.notif_channel_ongoing_desc, NotificationManager.IMPORTANCE_LOW) {
            setSound(null, null)
            enableVibration(false)
        }
        channel(context, mgr, CHANNEL_LIVE_LOCATION, R.string.notif_channel_live_location,
            R.string.notif_channel_live_location_desc, NotificationManager.IMPORTANCE_LOW) {
            setSound(null, null)
            enableVibration(false)
        }
        channel(context, mgr, CHANNEL_FETCH_PUSH, R.string.notif_channel_fetch,
            R.string.notif_channel_fetch_desc, NotificationManager.IMPORTANCE_LOW) {
            setSound(null, null)
            enableVibration(false)
        }
    }

    // Name and description are the only fields Android lets you change after creation, so
    // re-registering keeps them in sync with the language the app is currently running in.
    private fun channel(
        context: Context,
        mgr: NotificationManager,
        id: String,
        nameRes: Int,
        descRes: Int,
        importance: Int,
        configure: NotificationChannel.() -> Unit,
    ) {
        val name = context.getString(nameRes)
        val description = context.getString(descRes)
        val existing = mgr.getNotificationChannel(id)
        if (existing == null) {
            mgr.createNotificationChannel(
                NotificationChannel(id, name, importance).apply {
                    this.description = description
                    configure()
                }
            )
        } else if (existing.name != name || existing.description != description) {
            existing.name = name
            existing.description = description
            mgr.createNotificationChannel(existing)
        }
    }

    fun ensureBubblesAllowed(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        for (id in listOf(CHANNEL_MESSAGES, CHANNEL_MESSAGES_SILENT)) {
            nm.getNotificationChannel(id)?.let {
                it.setAllowBubbles(true)
                nm.createNotificationChannel(it)
            }
        }
    }
}
