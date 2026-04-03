package org.mlm.mages.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mlm.mages.shared.R

actual object RoomPlatformShortcuts : KoinComponent {

    private val context: Context by inject()

    actual fun support(): RoomPlatformShortcutSupport {
        val homeSupported = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

        return RoomPlatformShortcutSupport(
            homeScreenShortcut = homeSupported,
        )
    }

    actual fun addHomeScreenShortcut(
        roomId: String,
        roomName: String?,
    ): Result<Unit> = runCatching {
        check(ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            "Home screen shortcuts are not supported on this launcher"
        }

        val shortcutId = buildPinnedShortcutId(roomId)

        val mainActivityClass = Class.forName("org.mlm.mages.MainActivity")

        val intent = Intent(
            Intent.ACTION_VIEW,
            "mages://room?id=${Uri.encode(roomId)}".toUri(),
            context,
            mainActivityClass
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val label = (roomName ?: roomId).ifBlank { roomId }

        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(label.take(32))
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()

        val requested = ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        check(requested) { "Launcher rejected pin request" }
    }

    private fun buildPinnedShortcutId(roomId: String): String {
        return "room_home_$roomId"
    }
}
