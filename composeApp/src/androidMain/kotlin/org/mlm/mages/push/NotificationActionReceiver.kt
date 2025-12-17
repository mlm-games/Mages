package org.mlm.mages.push

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mlm.mages.matrix.MatrixProvider

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: return@launch
                val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return@launch
                val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)

                val svc = MatrixProvider.getReady(context) ?: return@launch

                when (intent.action) {
                    ACTION_MARK_READ -> {
                        svc.port.markFullyReadAt(roomId, eventId)
                    }

                    ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(KEY_TEXT_REPLY)
                            ?.toString()
                            ?.trim()
                            .orEmpty()

                        if (text.isNotBlank()) {
                            svc.port.reply(roomId, eventId, text)
                            svc.port.markFullyReadAt(roomId, eventId)
                        }
                    }
                }

                // dismiss notification after action
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (notifId != 0) nm.cancel(notifId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK_READ = "org.mlm.mages.ACTION_MARK_READ"
        const val ACTION_REPLY = "org.mlm.mages.ACTION_REPLY"

        const val EXTRA_ROOM_ID = "roomId"
        const val EXTRA_EVENT_ID = "eventId"
        const val EXTRA_NOTIF_ID = "notifId"

        const val KEY_TEXT_REPLY = "key_text_reply"
    }
}