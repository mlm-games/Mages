package org.mlm.mages.push

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.content.res.Resources
import android.media.AudioManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
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

    private fun callNotificationId(roomId: String): Int =
        ("call_$roomId").hashCode()

    private fun callScreenRequestCode(roomId: String, eventId: String?): Int =
        ("call_${roomId}_${eventId.orEmpty()}").hashCode()

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
        val notifId = callNotificationId(roomId)
        val screenRequestCode = callScreenRequestCode(roomId, eventId)

        val incomingScreenIntent = createFullScreenCallIntent(
            ctx = ctx,
            roomId = roomId,
            roomName = roomName,
            callerName = callerName,
            eventId = eventId,
            callerAvatarPath = callerAvatarPath,
            requestCode = screenRequestCode,
            isVoiceOnly = isVoiceOnly,
            isDm = isDm
        )

        val joinIntent = createCallJoinIntent(ctx, roomId, eventId, notifId)
        val declineIntent = createCallDeclineIntent(ctx, roomId, eventId, notifId)
        val deleteIntent = createCallDeleteIntent(ctx, roomId, notifId, callerName, roomName, isVoiceOnly)

        val callerIcon = callerAvatarPath?.let { path ->
            runCatching {
                val file = java.io.File(path)
                if (file.exists()) BitmapFactory.decodeFile(path) else null
            }.getOrNull()
        }

        val caller = Person.Builder()
            .setName(callerName)
            .setKey(roomId)
            .apply { callerIcon?.let { setIcon(IconCompat.createWithBitmap(it)) } }
            .build()

        val style = NotificationCompat.CallStyle.forIncomingCall(
            caller,
            declineIntent,
            joinIntent
        ).setIsVideo(!isVoiceOnly)

        val builder = NotificationCompat.Builder(ctx, AppNotificationChannels.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notif_status_bar)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(style)
            .setContentIntent(incomingScreenIntent ?: joinIntent)
            .setDeleteIntent(deleteIntent)
            .setTimeoutAfter(60_000)
            .setSound(Settings.System.DEFAULT_RINGTONE_URI, AudioManager.STREAM_RING)
            .addPerson(caller)
            .setShowWhen(false)
            .setGroup(Notifier.groupKey(ctx))
            .apply { callerIcon?.let { setLargeIcon(it) } }
            .setFullScreenIntent(incomingScreenIntent, true) // TODO: precall it when the setting is enabled

        mgr.notify(notifId, builder.build().apply {
            flags = flags or Notification.FLAG_INSISTENT
        })
        Notifier.updateSummaryNotification(ctx)
    }

    fun cancelCallNotification(ctx: Context, roomId: String) {
        val mgr = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel(callNotificationId(roomId))
        Notifier.updateSummaryNotification(ctx)
    }

    fun cancelRoomNotification(ctx: Context, roomId: String) {
        val mgr = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.cancel(roomId.hashCode()) // TODO: shouldnt cancel if it is a bubble?
        Notifier.updateSummaryNotification(ctx)
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
        requestCode: Int,
        isVoiceOnly: Boolean = false,
        isDm: Boolean = false
    ): PendingIntent? {
        val settingsRepo: SettingsRepository<AppSettings> by inject()

        val showCallScreen = runBlocking { settingsRepo.flow.first().showIncomingCallScreen }
        if (!showCallScreen) return null

        // Do not kick the user into system settings during an incoming call.
        // If FSI is unavailable, we still return the PendingIntent so notification taps
        // open the native incoming-call screen consistently.

        // reflection to avoid direct dependency
        val intent: Intent = try {
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
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
            }
        } catch (e: ClassNotFoundException) {
            e.printStackTrace()
            createCallJoinIntentRaw(ctx, roomId, eventId)
        }

        return PendingIntent.getActivity(
            ctx,
            requestCode,
            intent,
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
        eventId: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_DECLINE_CALL
            putExtra(NotificationActionReceiver.EXTRA_ROOM_ID, roomId)
            putExtra(NotificationActionReceiver.EXTRA_EVENT_ID, eventId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, requestCode)
        }
        return PendingIntent.getBroadcast(
            ctx, requestCode + 3, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createCallDeleteIntent(
        ctx: Context,
        roomId: String,
        requestCode: Int,
        callerName: String,
        roomName: String,
        isVoiceOnly: Boolean = false
    ): PendingIntent {
        val intent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_DISMISS_CALL
            putExtra(NotificationActionReceiver.EXTRA_ROOM_ID, roomId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, requestCode)
            putExtra(NotificationActionReceiver.EXTRA_CALLER_NAME, callerName)
            putExtra(NotificationActionReceiver.EXTRA_ROOM_NAME, roomName)
            putExtra(NotificationActionReceiver.EXTRA_IS_VOICE_ONLY, isVoiceOnly)
        }
        return PendingIntent.getBroadcast(
            ctx, requestCode + 4, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun showMissedCallNotification(
        ctx: Context,
        roomId: String,
        callerName: String,
        roomName: String,
        isVoiceOnly: Boolean = false
    ) {
        AppNotificationChannels.ensureCreated(ctx)
        val mgr = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notifId = ("missed_call_$roomId").hashCode()

        val callType = if (isVoiceOnly) "voice call" else "call"
        val title = "Missed $callType"
        val body = if (callerName == roomName) {
            "From $callerName"
        } else {
            "From $callerName in $roomName"
        }

        val notification = NotificationCompat.Builder(ctx, AppNotificationChannels.CHANNEL_CALLS_SILENT)
            .setSmallIcon(R.drawable.ic_notif_status_bar)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setCategory(Notification.CATEGORY_MISSED_CALL)
                } else {
                    setCategory(NotificationCompat.CATEGORY_EVENT)
                }
            }
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setGroup(Notifier.groupKey(ctx))
            .build()

        mgr.notify(notifId, notification)
        Notifier.updateSummaryNotification(ctx)
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

    internal fun groupKey(context: Context) = "${context.packageName}.NOTIFICATIONS"
    private val SUMMARY_NOTIFICATION_ID = "org.mlm.mages.NOTIFICATIONS.summary".hashCode()

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

        val nm = NotificationManagerCompat.from(context)
        val existingStyle = nm.activeNotifications
            .find { it.id == notificationId }
            ?.notification
            ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }

        val style = (existingStyle ?: NotificationCompat.MessagingStyle(sender)
            .setConversationTitle(if (isDm) null else roomName)
            .setGroupConversation(!isDm))
            .addMessage(messageBody, timestamp, sender)

        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply")
            .build()
        val replyIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_REPLY + notificationId,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_REPLY)
                .putExtra(NotificationActionReceiver.EXTRA_ROOM_ID, roomId)
                .putExtra(NotificationActionReceiver.EXTRA_EVENT_ID, eventId)
                .putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notificationId)
                .putExtra(NotificationActionReceiver.EXTRA_ROOM_NAME, roomName)
                .putExtra(NotificationActionReceiver.EXTRA_SENDER_NAME, senderName)
                .putExtra(NotificationActionReceiver.EXTRA_MESSAGE_BODY, messageBody),
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
                .putExtra(NotificationActionReceiver.EXTRA_ROOM_ID, roomId)
                .putExtra(NotificationActionReceiver.EXTRA_EVENT_ID, eventId)
                .putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notificationId)
                .putExtra(NotificationActionReceiver.EXTRA_ROOM_NAME, roomName)
                .putExtra(NotificationActionReceiver.EXTRA_SENDER_NAME, senderName)
                .putExtra(NotificationActionReceiver.EXTRA_MESSAGE_BODY, messageBody),
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
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setGroup(groupKey(context))
            .setGroupSummary(false)

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

        builder.addExtras(
            Bundle().apply {
                putString(NotificationReconcileWorker.EXTRA_MATRIX_ROOM_ID, roomId)
                putString(NotificationReconcileWorker.EXTRA_MATRIX_EVENT_ID, eventId)
            }
        )

        if (nm.areNotificationsEnabled()) {
            nm.notify(notificationId, builder.build())
        }

        updateSummaryNotification(context)
    }

    fun updateSummaryNotification(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        val activeNotifications = nm.activeNotifications

        val roomNotifIds = activeNotifications
            .filter {
                it.id != SUMMARY_NOTIFICATION_ID &&
                (it.notification.channelId == AppNotificationChannels.CHANNEL_MESSAGES ||
                 it.notification.channelId == AppNotificationChannels.CHANNEL_MESSAGES_SILENT ||
                 it.notification.channelId == AppNotificationChannels.CHANNEL_CALLS ||
                 it.notification.channelId == AppNotificationChannels.CHANNEL_CALLS_SILENT)
            }
            .map { it.id }
            .distinct()

        if (roomNotifIds.size < 2) {
            nm.cancel(SUMMARY_NOTIFICATION_ID)
            return
        }

        val summary = NotificationCompat.Builder(context, AppNotificationChannels.CHANNEL_MESSAGES_SILENT)
            .setSmallIcon(R.drawable.ic_notif_status_bar)
            .setContentTitle("${roomNotifIds.size} conversation(s)")
            .setContentText("${roomNotifIds.size} conversation(s)")
            .setGroup(groupKey(context))
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setSilent(true)
            .build()

        nm.notify(SUMMARY_NOTIFICATION_ID, summary)
    }

    fun showQuickReplyNotification(
        context: Context,
        roomId: String,
        roomName: String,
        eventId: String,
        notificationId: Int,
        contactName: String,
        contactAvatar: AvatarResult,
        originalMessage: String,
        replyText: String,
        myUserId: String,
        myUserName: String,
        myAvatar: AvatarResult,
        bubbleActivityClass: Class<*>?,
        fullOpenIntent: PendingIntent,
        isDm: Boolean,
    ) {
        val channelId = AppNotificationChannels.CHANNEL_MESSAGES_SILENT

        val me = Person.Builder()
            .setName(myUserName)
            .setKey(myUserId)
            .setIcon(myAvatar.icon)
            .build()

        val contact = Person.Builder()
            .setName(contactName)
            .setKey(contactName)
            .setIcon(contactAvatar.icon)
            .build()

        val nm = NotificationManagerCompat.from(context)
        val existingStyle = nm.activeNotifications
            .find { it.id == notificationId }
            ?.notification
            ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }

        val style = (existingStyle ?: NotificationCompat.MessagingStyle(me)
            .setConversationTitle(if (isDm) null else roomName)
            .setGroupConversation(!isDm))

        if (originalMessage.isNotEmpty() && existingStyle == null) {
            style.addMessage(originalMessage, System.currentTimeMillis() - 2000, contact)
        }

        style.addMessage(replyText, System.currentTimeMillis(), me)

        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply")
            .build()
        val replyIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_REPLY + notificationId,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_REPLY)
                .putExtra(NotificationActionReceiver.EXTRA_ROOM_ID, roomId)
                .putExtra(NotificationActionReceiver.EXTRA_EVENT_ID, eventId)
                .putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notificationId)
                .putExtra(NotificationActionReceiver.EXTRA_ROOM_NAME, roomName)
                .putExtra(NotificationActionReceiver.EXTRA_SENDER_NAME, contactName)
                .putExtra(NotificationActionReceiver.EXTRA_MESSAGE_BODY, replyText)
                .putExtra(NotificationActionReceiver.EXTRA_LAST_MESSAGE_FROM_ME, true),
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
                .putExtra(NotificationActionReceiver.EXTRA_ROOM_ID, roomId)
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
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setGroup(groupKey(context))
            .setGroupSummary(false)

        if (isDm) {
            builder.setLargeIcon(myAvatar.bitmap)
        } else {
            builder.setLargeIcon(contactAvatar.bitmap)
        }

        if (bubbleActivityClass != null && BubbleEligibilityEvaluator.canBubble(context, roomId)) {
            val bubblePendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_BUBBLE + notificationId,
                Intent(context, bubbleActivityClass)
                    .putExtra(ConversationShortcutPublisher.EXTRA_ROOM_ID, roomId),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val bubbleIcon = if (isDm) myAvatar.icon else contactAvatar.icon
            val bubbleMetadata = NotificationCompat.BubbleMetadata.Builder(
                bubblePendingIntent, bubbleIcon
            )
                .setDesiredHeight((Resources.getSystem().displayMetrics.density * 480).toInt())
                .setSuppressNotification(false)
                .build()
            builder.setBubbleMetadata(bubbleMetadata)
        }

        builder.setSilent(true)

        if (nm.areNotificationsEnabled()) {
            nm.notify(notificationId, builder.build())
        }

        updateSummaryNotification(context)
    }
}
