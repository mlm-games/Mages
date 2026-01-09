package org.mlm.mages.push

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
                        // also cancel whatever notifId was passed (call notif id)
                    }

                    ACTION_MARK_READ, ACTION_REPLY -> {
                        if (roomId == null || eventId == null) return@launch

                        runCatching { service.initFromDisk() }
                        val port = service.portOrNull

                        if (port != null && service.isLoggedIn()) {
                            when (intent.action) {
                                ACTION_MARK_READ -> port.markFullyReadAt(roomId, eventId)
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
                                }
                            }
                        }
                    }
                }
            } finally {
                if (notifId != 0) nm.cancel(notifId)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK_READ = "org.mlm.mages.ACTION_MARK_READ"
        const val ACTION_REPLY = "org.mlm.mages.ACTION_REPLY"
        const val ACTION_DECLINE_CALL = "org.mlm.mages.ACTION_DECLINE_CALL"

        const val EXTRA_ROOM_ID = "roomId"
        const val EXTRA_EVENT_ID = "eventId"
        const val EXTRA_NOTIF_ID = "notifId"

        const val KEY_TEXT_REPLY = "key_text_reply"
    }
}