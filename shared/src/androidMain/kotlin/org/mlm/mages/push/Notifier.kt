package org.mlm.mages.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.net.toUri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.mlm.mages.platform.SettingsProvider
import org.mlm.mages.shared.R

object AndroidNotificationHelper {
    private const val CHANNEL_ID = "messages"

    data class NotificationText(val title: String, val body: String)

    fun showSingleEvent(ctx: Context, n: NotificationText, roomId: String, eventId: String) {
        val mgr = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val settingsRepo = SettingsProvider.get(ctx)
        val soundEnabled = runBlocking { settingsRepo.flow.first().notificationSound }
        val channelId = if (soundEnabled) "messages" else "messages_silent"

        val notifId = (roomId + eventId).hashCode()

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_status_bar)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setContentIntent(createOpenIntent(ctx, roomId, notifId))
            .addAction(createReplyAction(ctx, roomId, eventId, notifId))
            .addAction(createMarkReadAction(ctx, roomId, eventId, notifId))
            .setAutoCancel(true)
            .build()

        mgr.notify(notifId, notification)
    }

    fun showBasic(ctx: Context, roomId: String, eventId: String) {
        showSingleEvent(
            ctx,
            NotificationText("New message", "You have a new message"),
            roomId,
            eventId
        )
    }

    fun showEnriched(ctx: Context, rendered: org.mlm.mages.matrix.RenderedNotification) {
        showSingleEvent(
            ctx,
            NotificationText(
                title = "${rendered.sender} • ${rendered.roomName}",
                body = rendered.body
            ),
            rendered.roomId,
            rendered.eventId
        )
    }

    private fun createOpenIntent(ctx: Context, roomId: String, notifId: Int): PendingIntent {
        val intent = Intent().apply {
            action = Intent.ACTION_VIEW
            data = "mages://room?id=$roomId".toUri()
            setPackage(ctx.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            ctx,
            notifId,
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