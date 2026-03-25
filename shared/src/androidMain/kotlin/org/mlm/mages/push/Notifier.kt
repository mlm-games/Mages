package org.mlm.mages.push

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.shared.R

object AndroidNotificationHelper : KoinComponent {

    data class NotificationText(val title: String, val body: String)

    fun showSingleEvent(
        ctx: Context,
        n: NotificationText,
        roomId: String,
        eventId: String,
        playSound: Boolean = true
    ) {
        AppNotificationChannels.ensureCreated(ctx)
        val mgr = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channelId = if (playSound)
            AppNotificationChannels.CHANNEL_MESSAGES
        else
            AppNotificationChannels.CHANNEL_MESSAGES_SILENT

        val notifId = (roomId).hashCode()

        val activeMessageCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.activeNotifications.count {
                it.notification.channelId == AppNotificationChannels.CHANNEL_MESSAGES ||
                        it.notification.channelId == AppNotificationChannels.CHANNEL_MESSAGES_SILENT
            }
        } else 0
        val badgeCount = activeMessageCount + 1

        val notification = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_notif_status_bar)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setContentIntent(createOpenIntent(ctx, roomId, eventId, notifId))
            .addAction(createReplyAction(ctx, roomId, eventId, notifId))
            .addAction(createMarkReadAction(ctx, roomId, eventId, notifId))
            .setNumber(badgeCount)
            .setAutoCancel(true)
            .build()

        mgr.notify(notifId, notification)
    }

    fun showIncomingCall(
        ctx: Context,
        roomId: String,
        eventId: String,
        callerName: String,
        roomName: String,
        callerAvatarPath: String? = null,
        isVoiceOnly: Boolean = false,
        isDm: Boolean = false
    ) {
        AppNotificationChannels.ensureCreated(ctx)
        val mgr = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notifId = ("call_$roomId").hashCode()

        val fullScreenIntent = createFullScreenCallIntent(
            ctx, roomId, roomName, callerName, eventId, callerAvatarPath,
            isVoiceOnly = isVoiceOnly,
            isDm = isDm
        )
        val joinIntent = createCallJoinIntent(ctx, roomId, eventId, notifId)
        val declineIntent = createCallDeclineIntent(ctx, roomId, notifId)

        val callTypeLabel = if (isVoiceOnly) "voice call" else "call"
        val text = if (callerName == roomName) {
            "$callerName is ${callTypeLabel}ing"
        } else {
            "$callerName is ${callTypeLabel}ing in $roomName"
        }

        val builder = NotificationCompat.Builder(ctx, AppNotificationChannels.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notif_status_bar)
            .setContentTitle(if (isVoiceOnly) "Incoming voice call" else "Incoming call")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(joinIntent)
            .addAction(R.drawable.ic_notif_status_bar, "Decline", declineIntent)
            .addAction(R.drawable.ic_notif_status_bar, "Answer", joinIntent)
            .setTimeoutAfter(60_000)

        callerAvatarPath?.let { path ->
            runCatching {
                val file = java.io.File(path)
                if (file.exists()) {
                    BitmapFactory.decodeFile(path)?.let { bitmap ->
                        builder.setLargeIcon(bitmap)
                    }
                }
            }
        }

        fullScreenIntent?.let { builder.setFullScreenIntent(it, true) }

        mgr.notify(notifId, builder.build())
    }

    fun cancelCallNotification(ctx: Context, roomId: String) {
        val mgr = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel(("call_$roomId").hashCode())
    }

    fun showInviteNotification(
        ctx: Context,
        roomId: String,
        eventId: String,
        inviterName: String,
        roomName: String
    ) {
        AppNotificationChannels.ensureCreated(ctx)
        val mgr = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notifId = ("invite_$roomId").hashCode()

        val notification = NotificationCompat.Builder(ctx, AppNotificationChannels.CHANNEL_INVITES)
            .setSmallIcon(R.drawable.ic_notif_status_bar)
            .setContentTitle("Room Invite")
            .setContentText("$inviterName invited you to $roomName")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$inviterName invited you to $roomName"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(createOpenIntent(ctx, roomId, eventId, notifId))
            .addAction(R.drawable.ic_notif_status_bar, "Decline",
                createDeclineInviteIntent(ctx, roomId, notifId))
            .addAction(R.drawable.ic_notif_status_bar, "Accept",
                createAcceptInviteIntent(ctx, roomId, notifId))
            .build()

        mgr.notify(notifId, notification)
    }

    private fun createFullScreenCallIntent(
        ctx: Context,
        roomId: String,
        roomName: String,
        callerName: String,
        eventId: String?,
        callerAvatarPath: String? = null,
        isVoiceOnly: Boolean = false,
        isDm: Boolean = false
    ): PendingIntent? {
        val settingsRepo: SettingsRepository<AppSettings> by inject()

        val showCallScreen = runBlocking { settingsRepo.flow.first().showIncomingCallScreen }
        if (!showCallScreen) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val mgr = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (!mgr.canUseFullScreenIntent()) {
                requestFullScreenNotificationPermission(ctx)
            }
        }

        // reflection to avoid direct dependency
        val intent = try {
            val activityClass = Class.forName("org.mlm.mages.activities.IncomingCallActivity")
            Intent(ctx, activityClass).apply {
                putExtra("room_id", roomId)
                putExtra("room_name", roomName)
                putExtra("caller_name", callerName)
                putExtra("event_id", eventId)
                putExtra("caller_avatar_path", callerAvatarPath)
                putExtra("is_voice_only", isVoiceOnly)
                putExtra("is_dm", isDm)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
            }
        } catch (e: ClassNotFoundException) {
            createCallJoinIntentRaw(ctx, roomId, eventId)
            e.printStackTrace()
        }

        return PendingIntent.getActivity(
            ctx,
            roomId.hashCode(),
            intent as Intent?,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createOpenIntent(
        ctx: Context,
        roomId: String,
        eventId: String,
        requestCode: Int
    ): PendingIntent {
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
            ctx, requestCode, intent,
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

    private fun createCallJoinIntent(
        ctx: Context,
        roomId: String,
        eventId: String?,
        requestCode: Int
    ): PendingIntent {
        val uri = Uri.Builder()
            .scheme("mages")
            .authority("room")
            .appendQueryParameter("id", roomId)
            .appendQueryParameter("join_call", "1")
            .apply { eventId?.let { appendQueryParameter("event", it) } }
            .build()

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(ctx.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createCallDeclineIntent(
        ctx: Context,
        roomId: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_DECLINE_CALL
            putExtra(NotificationActionReceiver.EXTRA_ROOM_ID, roomId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, requestCode)
        }
        return PendingIntent.getBroadcast(
            ctx, requestCode + 3, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createAcceptInviteIntent(
        ctx: Context,
        roomId: String,
        notifId: Int
    ): PendingIntent {
        val intent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_ACCEPT_INVITE
            putExtra(NotificationActionReceiver.EXTRA_ROOM_ID, roomId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        return PendingIntent.getBroadcast(
            ctx, notifId + 10, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createDeclineInviteIntent(
        ctx: Context,
        roomId: String,
        notifId: Int
    ): PendingIntent {
        val intent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_DECLINE_INVITE
            putExtra(NotificationActionReceiver.EXTRA_ROOM_ID, roomId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        return PendingIntent.getBroadcast(
            ctx, notifId + 11, intent,
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
            R.drawable.ic_notif_status_bar, "Mark read",
            PendingIntent.getBroadcast(
                ctx, notifId + 1, intent,
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

        return NotificationCompat.Action.Builder(
            R.drawable.ic_notif_status_bar, "Reply",
            PendingIntent.getBroadcast(ctx, notifId + 2, intent, flags)
        )
            .addRemoteInput(
                RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT_REPLY)
                    .setLabel("Reply")
                    .build()
            )
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun requestFullScreenNotificationPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = "package:${ctx.packageName}".toUri()
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
            }
        }
    }
}

object Notifier {

    private const val REQUEST_BUBBLE = 2000
    private const val REQUEST_CONTENT = 3000
    private const val REQUEST_REPLY = 4000
    private const val REQUEST_READ = 5000
    private const val KEY_TEXT_REPLY = "key_text_reply"

    fun showConversationNotification(
        context: Context,
        roomId: String,
        roomName: String,
        eventId: String,
        senderName: String,
        senderUserId: String,
        messageBody: String,
        timestamp: Long,
        notificationId: Int,
        bubbleActivityClass: Class<*>?,
        fullOpenIntent: PendingIntent,
        senderAvatar: AvatarResult,
        roomAvatar: AvatarResult,
        isDm: Boolean = false,
        playSound: Boolean = true,
    ) {
        val channelId = if (playSound) {
            AppNotificationChannels.CHANNEL_MESSAGES
        } else {
            AppNotificationChannels.CHANNEL_MESSAGES_SILENT
        }
        if (bubbleActivityClass != null) {
            ConversationShortcutPublisher.publishOrUpdate(
                context, roomId, roomName, senderName, roomAvatar.icon, bubbleActivityClass
            )
        }

        val sender = Person.Builder()
            .setName(senderName)
            .setKey(senderUserId)
            .setIcon(senderAvatar.icon)
            .build()

        val style = NotificationCompat.MessagingStyle(sender)
            .setConversationTitle(if (isDm) null else roomName)
            .setGroupConversation(!isDm)
            .addMessage(messageBody, timestamp, sender)

        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply")
            .build()
        val replyIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_REPLY + notificationId,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_REPLY)
                .putExtra(ConversationShortcutPublisher.EXTRA_ROOM_ID, roomId)
                .putExtra(NotificationActionReceiver.EXTRA_EVENT_ID, eventId)
                .putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notificationId),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notif_status_bar, "Reply", replyIntent
        ).addRemoteInput(remoteInput).build()

        val markReadIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_READ + notificationId,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_MARK_READ)
                .putExtra(ConversationShortcutPublisher.EXTRA_ROOM_ID, roomId)
                .putExtra(NotificationActionReceiver.EXTRA_EVENT_ID, eventId)
                .putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notif_status_bar, "Mark read", markReadIntent
        ).build()

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notif_status_bar)
            .setStyle(style)
            .setShortcutId(ConversationShortcutPublisher.shortcutId(roomId))
            .setContentIntent(fullOpenIntent)
            .addAction(replyAction)
            .addAction(markReadAction)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_MESSAGE)

        val largeIconBitmap = if (isDm) senderAvatar.bitmap else roomAvatar.bitmap
        builder.setLargeIcon(largeIconBitmap)

        if (bubbleActivityClass != null && BubbleEligibilityEvaluator.canBubble(context, roomId)) {
            val bubblePendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_BUBBLE + notificationId,
                Intent(context, bubbleActivityClass)
                    .putExtra(ConversationShortcutPublisher.EXTRA_ROOM_ID, roomId),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val bubbleMetadata = NotificationCompat.BubbleMetadata.Builder(
                bubblePendingIntent, roomAvatar.icon
            )
                .setDesiredHeight((Resources.getSystem().displayMetrics.density * 480).toInt())
                .setSuppressNotification(false)
                .build()
            builder.setBubbleMetadata(bubbleMetadata)
        }

        if (!playSound) builder.setSilent(true)

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun suppressBubble(
        context: Context,
        roomId: String,
        roomName: String,
        senderName: String,
        messageBody: String,
        notificationId: Int,
        bubbleActivityClass: Class<*>
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val icon = IconCompat.createWithResource(context, R.drawable.ic_notif_status_bar)
        val bubblePi = PendingIntent.getActivity(
            context,
            REQUEST_BUBBLE + notificationId,
            Intent(context, bubbleActivityClass)
                .putExtra(ConversationShortcutPublisher.EXTRA_ROOM_ID, roomId),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val suppressed = NotificationCompat.BubbleMetadata.Builder(bubblePi, icon)
            .setDesiredHeight((Resources.getSystem().displayMetrics.density * 640).toInt())
            .setSuppressNotification(true)
            .build()
        val sender = Person.Builder().setName(senderName).setKey(roomId).build()
        val style = NotificationCompat.MessagingStyle(sender)
            .setConversationTitle(roomName)
            .addMessage(messageBody, System.currentTimeMillis(), sender)
        val builder = NotificationCompat.Builder(context, AppNotificationChannels.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif_status_bar)
            .setStyle(style)
            .setShortcutId(ConversationShortcutPublisher.shortcutId(roomId))
            .setBubbleMetadata(suppressed)
            .setOnlyAlertOnce(true)
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }
}