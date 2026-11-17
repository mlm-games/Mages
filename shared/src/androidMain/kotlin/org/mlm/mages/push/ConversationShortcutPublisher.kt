package org.mlm.mages.push

import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

object ConversationShortcutPublisher {

    fun shortcutId(roomId: String, threadId: String? = null): String =
        if (threadId.isNullOrBlank()) "conv_$roomId" else "conv_$roomId:$threadId"

    fun publishOrUpdate(
        context: Context,
        id: String,
        label: String,
        personName: String,
        personKey: String,
        icon: IconCompat,
        intent: Intent,
    ): Boolean = runCatching {
        val person = Person.Builder()
            .setName(personName)
            .setKey(personKey)
            .setImportant(true)
            .build()

        val shortcut = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setLongLived(true)
            .setIsConversation()
            .setIcon(icon)
            .setPerson(person)
            .setIntent(intent)
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }.getOrDefault(false)

    fun remove(context: Context, roomId: String, threadId: String? = null) {
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(shortcutId(roomId, threadId)))
    }

    const val EXTRA_ROOM_ID = "room_id"
}
