package org.mlm.mages.push

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mlm.mages.MatrixService

class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {

    private val service: MatrixService by inject()

    override fun onReceive(context: Context, intent: Intent) {
        AppNotificationChannels.ensureCreated(context)

        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
            val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
            val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)

            try {
                when (intent.action) {
                    ACTION_DECLINE_CALL -> {
                        if (roomId != null) AndroidNotificationHelper.cancelCallNotification(context, roomId)
                    }

                    ACTION_ACCEPT_INVITE -> {
                        if (roomId != null) {
                            runCatching { service.initFromDisk() }
                            val port = service.portOrNull
                            if (port != null && service.isLoggedIn()) {
                                runCatching { port.acceptInvite(roomId) }
                            }
                        }
                    }

                    ACTION_DECLINE_INVITE -> {
                        if (roomId != null) {
                            runCatching { service.initFromDisk() }
                            val port = service.portOrNull
                            if (port != null && service.isLoggedIn()) {
                                runCatching { port.leaveRoom(roomId) }
                            }
                        }
                    }

                    ACTION_MARK_READ, ACTION_REPLY -> {
                        if (roomId == null || eventId == null) return@launch

                        runCatching { service.initFromDisk() }
                        val port = service.portOrNull

                        if (port != null && service.isLoggedIn()) {
                            when (intent.action) {
                                ACTION_MARK_READ -> {
                                    port.markFullyReadAt(roomId, eventId)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                        && BubbleEligibilityEvaluator.canBubble(context, roomId)
                                    ) {
                                        val roomName = intent.getStringExtra(EXTRA_ROOM_NAME) ?: "Unknown"
                                        val senderName = intent.getStringExtra(EXTRA_SENDER_NAME) ?: "Unknown"
                                        val messageBody = intent.getStringExtra(EXTRA_MESSAGE_BODY) ?: ""
                                        val bubbleActivityClass = try {
                                            Class.forName("org.mlm.mages.activities.BubbleConversationActivity")
                                        } catch (_: ClassNotFoundException) {
                                            Class.forName("org.mlm.mages.MainActivity")
                                        }
                                        Notifier.suppressBubble(context, roomId, roomName, senderName, messageBody, notifId, bubbleActivityClass)
                                    } else {
                                        if (notifId != 0) nm.cancel(notifId)
                                    }
                                }
                                ACTION_REPLY -> {
                                    val text = RemoteInput.getResultsFromIntent(intent)
                                        ?.getCharSequence(KEY_TEXT_REPLY)
                                        ?.toString()
                                        ?.trim()
                                        .orEmpty()

                                    if (text.isNotBlank()) {
                                        port.reply(roomId, eventId, text)
                                        port.markFullyReadAt(roomId, eventId)
                                    }
                                    if (notifId != 0) nm.cancel(notifId)
                                }
                            }
                        } else {
                            if (notifId != 0) nm.cancel(notifId)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK_READ = "org.mlm.mages.ACTION_MARK_READ"
        const val ACTION_REPLY = "org.mlm.mages.ACTION_REPLY"
        const val ACTION_DECLINE_CALL = "org.mlm.mages.ACTION_DECLINE_CALL"
        const val ACTION_ACCEPT_INVITE = "org.mlm.mages.ACTION_ACCEPT_INVITE"
        const val ACTION_DECLINE_INVITE = "org.mlm.mages.ACTION_DECLINE_INVITE"

        const val EXTRA_ROOM_ID = "roomId"
        const val EXTRA_EVENT_ID = "eventId"
        const val EXTRA_NOTIF_ID = "notifId"
        const val EXTRA_ROOM_NAME = "roomName"
        const val EXTRA_SENDER_NAME = "senderName"
        const val EXTRA_MESSAGE_BODY = "messageBody"

        const val KEY_TEXT_REPLY = "key_text_reply"
    }
}