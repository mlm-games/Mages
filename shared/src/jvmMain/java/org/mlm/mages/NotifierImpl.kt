package org.mlm.mages

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

object NotifierImpl {
    private val lock = Any()

    private var conn: DBusConnection? = null

    private var capabilities: Set<String> = emptySet()
    private var actionsSupported: Boolean = false
    private var inlineReplySupported: Boolean = false

    private var handlersInstalledFor: DBusConnection? = null


    private val notifCtx = ConcurrentHashMap<UInt32, Pair<String, String>>()

    private fun ensure(): DBusConnection? = synchronized(lock) {
        if (conn?.isConnected == true) return conn

        return try {
            DBusConnectionBuilder.forSessionBus().build().also { c ->
                conn = c

                if (handlersInstalledFor !== c) {
                    // Reset per-connection state
                    capabilities = emptySet()
                    actionsSupported = false
                    inlineReplySupported = false

                    installHandlers(c)
                    handlersInstalledFor = c
                }
            }
        } catch (_: IOException) {
            conn = null
            null
        }
    }

    private fun installHandlers(c: DBusConnection) {
        val notifications = c.getRemoteObject(
            "org.freedesktop.Notifications",
            "/org/freedesktop/Notifications",
            Notifications::class.java
        )

        capabilities = runCatching { notifications.GetCapabilities().toSet() }
            .getOrElse { emptySet() }

        actionsSupported = capabilities.contains("actions")
        inlineReplySupported = capabilities.contains("inline-reply")

        runCatching {
            c.addSigHandler(Notifications.ActionInvoked::class.java) { sig ->
                val ctx = notifCtx[sig.id] ?: return@addSigHandler
                val (roomId, eventId) = ctx

                when (sig.actionKey) {
                    "", "default" -> DesktopNotifActions.openRoom(roomId)
                    "mark_read" -> DesktopNotifActions.markRead(roomId, eventId)
                    "reply" -> DesktopNotifActions.reply(roomId, eventId)
                    "inline-reply" -> DesktopNotifActions.reply(roomId, eventId)
                }
            }
        }

        runCatching {
            c.addSigHandler(Notifications.NotificationReplied::class.java) { sig ->
                val ctx = notifCtx[sig.id] ?: return@addSigHandler
                val (roomId, eventId) = ctx
                DesktopNotifActions.replyText(roomId, eventId, sig.replyText)
            }
        }

        // Notif close -> cleanup mapping
        runCatching {
            c.addSigHandler(Notifications.NotificationClosed::class.java) { sig ->
                notifCtx.remove(sig.id)
            }
        }
    }

    fun notify(app: String, title: String, body: String, desktopEntry: String? = "org.mlm.mages") {
        val c = ensure() ?: return
        try {
            val notifications = c.getRemoteObject(
                "org.freedesktop.Notifications",
                "/org/freedesktop/Notifications",
                Notifications::class.java
            )

            val hints = HashMap<String, Variant<*>>()
            if (desktopEntry != null) hints["desktop-entry"] = Variant(desktopEntry)

            notifications.Notify(
                app,
                UInt32(0),
                "",
                title,
                body,
                emptyArray(),
                hints,
                -1
            )
        } catch (_: Exception) {
        }
    }

    fun notifyMatrixEvent(
        title: String,
        body: String,
        roomId: String,
        eventId: String,
        hasMention: Boolean = false,
        desktopEntry: String? = "mages",
        iconPath: String? = null
    ) {
        val c = ensure() ?: return

        val notifications = c.getRemoteObject(
            "org.freedesktop.Notifications",
            "/org/freedesktop/Notifications",
            Notifications::class.java
        )

        val hints = HashMap<String, Variant<*>>()

        desktopEntry?.let { hints["desktop-entry"] = Variant(it) }
        iconPath?.let { hints["image-path"] = Variant(it) }

        hints["urgency"] = Variant((if (hasMention) 2 else 1).toByte())

        val expireTimeout = if (hasMention && capabilities.contains("persistence")) 0 else -1
        if (hasMention && capabilities.contains("persistence")) {
            hints["resident"] = Variant(true)
        }

        if (hasMention && capabilities.contains("sound")) {
            hints["sound-name"] = Variant("message-new-instant")
        }

        val formattedBody = formatBodyForServer(body)

        val actions: Array<String> =
            if (actionsSupported && roomId.isNotBlank() && eventId.isNotBlank()) {
                buildList {
                    add("default"); add("Open")

                    if (inlineReplySupported) {
                        add("inline-reply"); add("Reply…")
                    } else {
                        add("reply"); add("Reply…")
                    }

                    add("mark_read"); add("Mark read")
                }.toTypedArray()
            } else emptyArray()

        val appIcon = desktopEntry ?: "mages"

        val id = try {
            notifications.Notify(
                "Mages",
                UInt32(0),
                appIcon,
                title,
                formattedBody,
                actions,
                hints,
                expireTimeout
            )
        } catch (_: Exception) {
            return
        }

        notifCtx[id] = roomId to eventId
    }

    fun warmUp() {
        ensure()
    }

    private fun formatBodyForServer(body: String): String {
        val b = body.trim()

        if (capabilities.contains("body-markup")) {
            val escaped = escapeMarkup(b)
            return if (capabilities.contains("body-hyperlinks")) {
                linkifyMarkup(escaped)
            } else {
                escaped
            }
        }

        return b
    }

    private fun escapeMarkup(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun linkifyMarkup(s: String): String {
        val r = Regex("""(https?://[^\s<>&]*(?:&amp;[^\s<>&]*)*)""")
        return r.replace(s) { m ->
            val trimmed = m.value.trimEnd('.', ',', ';', ':', '!', '?')
            """<a href="$trimmed">$trimmed</a>"""
        }
    }

    @DBusInterfaceName("org.freedesktop.Notifications")
    interface Notifications : DBusInterface {
        fun Notify(
            appName: String,
            replacesId: UInt32,
            appIcon: String,
            summary: String,
            body: String,
            actions: Array<String>,
            hints: Map<String, Variant<*>>,
            expireTimeout: Int
        ): UInt32

        fun GetCapabilities(): List<String>

        class ActionInvoked(
            path: String,
            val id: UInt32,
            val actionKey: String
        ) : DBusSignal(path, id, actionKey)

        class NotificationClosed(
            path: String,
            val id: UInt32,
            reason: UInt32
        ) : DBusSignal(path, id, reason)

        class NotificationReplied(
            path: String,
            val id: UInt32,
            val replyText: String
        ) : DBusSignal(path, id, replyText)
    }
}

object DesktopNotifActions {
    @Volatile var openRoom: (String) -> Unit = {}
    @Volatile var markRead: (String, String) -> Unit = { _, _ -> }
    @Volatile var reply: (String, String) -> Unit = { _, _ -> }
    @Volatile var replyText: (String, String, String) -> Unit = { _, _, _ -> }
}
