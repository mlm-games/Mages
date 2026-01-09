package org.mlm.mages.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.shared.R

object AndroidNotificationHelper : KoinComponent {
    private const val CHANNEL_MESSAGES = "messages"
    private const val CHANNEL_CALLS = "calls"

    data class NotificationText(val title: String, val body: String)

    fun showSingleEvent(ctx: Context, n: NotificationText, roomId: String, eventId: String) {
        val mgr = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val settingsRepo: SettingsRepository<AppSettings> by inject()
        val soundEnabled = runBlocking { settingsRepo.flow.first().notificationSound }
        val channelId = if (soundEnabled) "messages" else "messages_silent"

        val notifId = (roomId + eventId).hashCode()

        val notification = NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif_status_bar)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setChannelId(channelId)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setContentIntent(createOpenIntent(ctx, roomId, eventId, notifId))
            .addAction(createReplyAction(ctx, roomId, eventId, notifId))
            .addAction(createMarkReadAction(ctx, roomId, eventId, notifId))
            .setAutoCancel(true)
            .build()

        mgr.notify(notifId, notification)
    }

    fun showIncomingCall(
        ctx: Context,
        roomId: String,
        eventId: String,
        callerName: String,
        roomName: String
    ) {
        val mgr = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notifId = ("call_$roomId").hashCode()

        val fullScreenIntent = createFullScreenCallIntent(ctx, roomId, roomName, callerName, eventId)
        val joinIntent = createCallJoinIntent(ctx, roomId, eventId, notifId)
        val declineIntent = createCallDeclineIntent(ctx, roomId, notifId)

        val title = "Incoming call"
        val text = if (callerName == roomName) {
            "$callerName is calling"
        } else {
            "$callerName is calling in $roomName"
        }

        val notification = NotificationCompat.Builder(ctx, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notif_status_bar)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(joinIntent)
            .addAction(
                R.drawable.ic_notif_status_bar,
                "Decline",
                declineIntent
            )
            .addAction(
                R.drawable.ic_notif_status_bar,
                "Answer",
                joinIntent
            )
            .setFullScreenIntent(fullScreenIntent, true)
            .setTimeoutAfter(60_000)
            .build()

        mgr.notify(notifId, notification)
    }

    fun cancelCallNotification(ctx: Context, roomId: String) {
        val mgr = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notifId = ("call_$roomId").hashCode()
        mgr.cancel(notifId)
    }

    private fun createFullScreenCallIntent(
        ctx: Context,
        roomId: String,
        roomName: String,
        callerName: String,
        eventId: String?
    ): PendingIntent {
        // reflection to avoid direct dependency
        val intent = try {
            val activityClass = Class.forName("org.mlm.mages.activities.IncomingCallActivity")
            Intent(ctx, activityClass).apply {
                putExtra("room_id", roomId)
                putExtra("room_name", roomName)
                putExtra("caller_name", callerName)
                putExtra("event_id", eventId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
            }
        } catch (_: ClassNotFoundException) {
            createCallJoinIntentRaw(ctx, roomId, eventId)
        }

        return PendingIntent.getActivity(
            ctx,
            roomId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createOpenIntent(ctx: Context, roomId: String, eventId: String, requestCode: Int): PendingIntent {
        val uri = Uri.Builder()
            .scheme("mages")
            .authority("room")
            .appendQueryParameter("id", roomId)
            .appendQueryParameter("event", eventId)
            .build()

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(ctx.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            ctx,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createCallJoinIntent(ctx: Context, roomId: String, eventId: String?, requestCode: Int): PendingIntent {
        return PendingIntent.getActivity(
            ctx,
            requestCode,
            createCallJoinIntentRaw(ctx, roomId, eventId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createCallJoinIntentRaw(ctx: Context, roomId: String, eventId: String?): Intent {
        val uriBuilder = Uri.Builder()
            .scheme("mages")
            .authority("room")
            .appendQueryParameter("id", roomId)
            .appendQueryParameter("join_call", "1")

        eventId?.let { uriBuilder.appendQueryParameter("event", it) }

        return Intent(Intent.ACTION_VIEW, uriBuilder.build()).apply {
            setPackage(ctx.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    private fun createCallDeclineIntent(ctx: Context, roomId: String, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_DECLINE_CALL
            putExtra(NotificationActionReceiver.EXTRA_ROOM_ID, roomId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, requestCode)
        }

        return PendingIntent.getBroadcast(
            ctx,
            requestCode + 3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createMarkReadAction(
        ctx: Context,
        roomId: String,
        eventId: String,
        notifId: Int
    ): NotificationCompat.Action {
        val intent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra(NotificationActionReceiver.EXTRA_ROOM_ID, roomId)
            putExtra(NotificationActionReceiver.EXTRA_EVENT_ID, eventId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
        }

        return NotificationCompat.Action.Builder(
            R.drawable.ic_notif_status_bar,
            "Mark read",
            PendingIntent.getBroadcast(
                ctx,
                notifId + 1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        ).build()
    }

    private fun createReplyAction(
        ctx: Context,
        roomId: String,
        eventId: String,
        notifId: Int
    ): NotificationCompat.Action {
        val intent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY
            putExtra(NotificationActionReceiver.EXTRA_ROOM_ID, roomId)
            putExtra(NotificationActionReceiver.EXTRA_EVENT_ID, eventId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT_REPLY)
            .setLabel("Reply")
            .build()

        return NotificationCompat.Action.Builder(
            R.drawable.ic_notif_status_bar,
            "Reply",
            PendingIntent.getBroadcast(ctx, notifId + 2, intent, flags)
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
    }
}