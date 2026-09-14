package org.mlm.mages.push

import android.content.Context
import android.content.Intent

object CallTelecomBridge {
    var onIncomingGone: ((roomId: String) -> Unit)? = null

    const val ACTION_CALL_DISMISSED = "org.mlm.mages.ACTION_CALL_DISMISSED"
    const val EXTRA_ROOM_ID = "room_id"
    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_SILENT = "silent"

    fun sendCallDismissed(ctx: Context, roomId: String, eventId: String?, silent: Boolean) {
        runCatching {
            ctx.sendBroadcast(
                Intent(ACTION_CALL_DISMISSED).apply {
                    setPackage(ctx.packageName)
                    putExtra(EXTRA_ROOM_ID, roomId)
                    eventId?.let { putExtra(EXTRA_EVENT_ID, it) }
                    putExtra(EXTRA_SILENT, silent)
                }
            )
        }
    }
}
