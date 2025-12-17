package org.mlm.mages.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.net.toUri
import org.mlm.mages.MainActivity
import org.mlm.mages.R

object AndroidNotificationHelper {
    data class NotificationText(val title: String, val body: String) // mirror FFI return

    fun showSingleEvent(ctx: Context, n: NotificationText, roomId: String, eventId: String) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (mgr.getNotificationChannel("messages") == null) {
                mgr.createNotificationChannel(
                    NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }

        val notifId = (roomId + eventId).hashCode()
        val baseFlags = PendingIntent.FLAG_UPDATE_CURRENT

        val open = Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = "mages://room?id=$roomId".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val openPi = PendingIntent.getActivity(
            ctx,
            notifId,
            open,
            baseFlags or PendingIntent.FLAG_IMMUTABLE
        )

        val markReadIntent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra(NotificationActionReceiver.EXTRA_ROOM_ID, roomId)
            putExtra(NotificationActionReceiver.EXTRA_EVENT_ID, eventId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
        }

        val markReadPi = PendingIntent.getBroadcast(
            ctx,
            notifId + 1,
            markReadIntent,
            baseFlags or PendingIntent.FLAG_IMMUTABLE
        )

        val replyIntent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY
            putExtra(NotificationActionReceiver.EXTRA_ROOM_ID, roomId)
            putExtra(NotificationActionReceiver.EXTRA_EVENT_ID, eventId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
        }

        val replyFlags = baseFlags or if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }

        val replyPi = PendingIntent.getBroadcast(
            ctx,
            notifId + 2,
            replyIntent,
            replyFlags
        )

        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT_REPLY)
            .setLabel("Reply")
            .build()

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.icon_tray, "Reply", replyPi
        ).addRemoteInput(remoteInput).setAllowGeneratedReplies(true).build()

        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.icon_tray, "Mark read", markReadPi
        ).build()

        val nobj = NotificationCompat.Builder(ctx, "messages")
            .setSmallIcon(R.drawable.icon_tray)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setContentIntent(openPi)
            .addAction(replyAction)
            .addAction(markReadAction)
            .setAutoCancel(true)
            .build()

        mgr.notify(notifId, nobj)
    }
}