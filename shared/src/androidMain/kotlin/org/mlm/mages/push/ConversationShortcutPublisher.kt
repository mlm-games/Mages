package org.mlm.mages.push

import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

object ConversationShortcutPublisher {

    fun shortcutId(roomId: String): String = "conv_$roomId"

    fun publishOrUpdate(
        context: Context,
        roomId: String,
        roomName: String,
        senderName: String,
        icon: IconCompat,
        bubbleActivityClass: Class<*>,
    ) {
        val person = Person.Builder()
            .setName(senderName)
            .setKey(roomId)
            .setImportant(true)
            .build()

        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId(roomId))
            .setShortLabel(roomName)
            .setLongLived(true)
            .setIsConversation()
            .setIcon(icon)
            .setPerson(person)
            .setIntent(
                Intent(context, bubbleActivityClass)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(EXTRA_ROOM_ID, roomId)
            )
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    fun remove(context: Context, roomId: String) {
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(shortcutId(roomId)))
    }

    const val EXTRA_ROOM_ID = "room_id"
}
