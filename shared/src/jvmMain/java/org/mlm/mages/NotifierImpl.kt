package org.mlm.mages
import co.touchlab.kermit.Logger

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.ConcurrentHashMap

object NotifierImpl {
    private val lock = Any()

    private var conn: DBusConnection? = null

    private var capabilities: Set<String> = emptySet()
    private var actionsSupported: Boolean = false
    private var inlineReplySupported: Boolean = false

    private var handlersInstalledFor: DBusConnection? = null


    private val notifCtx = ConcurrentHashMap<UInt32, Pair<String, String>>()
    private val notifIdByRoom = ConcurrentHashMap<String, UInt32>()
    private val callNotifIdByRoom = ConcurrentHashMap<String, UInt32>()

    private fun invalidateConnection(failed: DBusConnection) {
        synchronized(lock) {
            if (conn === failed) {
                runCatching { failed.disconnect() }
                conn = null
                handlersInstalledFor = null
                capabilities = emptySet()
                actionsSupported = false
                inlineReplySupported = false
                notifCtx.clear()
                notifIdByRoom.clear()
                callNotifIdByRoom.clear()
                Logger.w("[notification] D-Bus connection invalidated")
            }
        }
    }

    private fun ensure(): DBusConnection? = synchronized(lock) {
        if (conn?.isConnected == true) return conn

        return try {
            DBusConnectionBuilder.forSessionBus().build().also { c ->
                conn = c

                if (handlersInstalledFor !== c) {
                    capabilities = emptySet()
                    actionsSupported = false
                    inlineReplySupported = false

                    installHandlers(c)
                    handlersInstalledFor = c
                }

                Logger.w("[notification] D-Bus session established")
            }
        } catch (e: Exception) {
            Logger.w("[notification] D-Bus connect failed: ${e.stackTraceToString()}")
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
                    "answer" -> DesktopNotifActions.answerCall(roomId, eventId)
                    "decline" -> DesktopNotifActions.declineCall(roomId, eventId)
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
                notifIdByRoom.entries.removeIf { it.value == sig.id }
                callNotifIdByRoom.entries.removeIf { it.value == sig.id }
            }
        }
    }

    private fun getNotificationsProxy(c: DBusConnection): Notifications =
        c.getRemoteObject(
            "org.freedesktop.Notifications",
            "/org/freedesktop/Notifications",
            Notifications::class.java
        )

    fun notify(app: String, title: String, body: String, desktopEntry: String? = "org.mlm.mages") {
        repeat(2) { attempt ->
            val c = ensure() ?: return
            try {
                val notifications = getNotificationsProxy(c)

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
                return
            } catch (e: Exception) {
                Logger.w(
                    "[notification] D-Bus Notify failed (attempt=${attempt + 1}): ${e.stackTraceToString()}"
                )
                invalidateConnection(c)
            }
        }
    }

    fun notifyMatrixEvent(
        title: String,
        body: String,
        roomId: String,
        eventId: String,
        hasMention: Boolean = false,
        playSound: Boolean = true,
        desktopEntry: String? = "mages",
        iconPath: String? = null
    ) {
        val persistent = hasMention && capabilities.contains("persistence")
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

        val id = postNotify(
            summary = title,
            body = body,
            roomId = roomId,
            eventId = eventId,
            desktopEntry = desktopEntry,
            iconPath = iconPath,
            urgency = (if (hasMention) 2 else 1).toByte(),
            soundName = if (playSound) "message-new-instant" else null,
            resident = persistent,
            actions = actions,
            expireTimeout = if (persistent) 0 else -1,
            replacesId = notifIdByRoom[roomId] ?: UInt32(0),
            logTag = "message"
        ) ?: return
        notifIdByRoom[roomId] = id
    }

    fun warmUp() {
        ensure()
    }

    fun closeRoomNotification(roomId: String) {
        val id = notifIdByRoom.remove(roomId) ?: return
        notifCtx.remove(id)
        val c = ensure() ?: return
        try {
            getNotificationsProxy(c).CloseNotification(id)
        } catch (e: Exception) {
            Logger.w("[notification] D-Bus CloseNotification failed: ${e.message}")
        }
    }

    fun notifyIncomingCall(
        callerName: String,
        roomName: String,
        roomId: String,
        eventId: String,
        desktopEntry: String? = "mages",
        iconPath: String? = null
    ) {
        val body = if (roomName.isNotBlank() && roomName != callerName) {
            "$callerName in $roomName"
        } else {
            "From $callerName"
        }

        val actions: Array<String> =
            if (actionsSupported && roomId.isNotBlank() && eventId.isNotBlank()) {
                arrayOf("answer", "Answer", "decline", "Decline")
            } else emptyArray()

        val id = postNotify(
            summary = "Incoming call",
            body = body,
            roomId = roomId,
            eventId = eventId,
            desktopEntry = desktopEntry,
            iconPath = iconPath,
            urgency = 2.toByte(),
            soundName = "phone-incoming-call",
            resident = capabilities.contains("persistence"),
            actions = actions,
            expireTimeout = 0,
            replacesId = callNotifIdByRoom[roomId] ?: UInt32(0),
            logTag = "incoming-call"
        ) ?: return
        callNotifIdByRoom[roomId] = id
    }

    fun closeCallNotification(roomId: String) {
        val id = callNotifIdByRoom.remove(roomId) ?: return
        notifCtx.remove(id)
        val c = ensure() ?: return
        try {
            getNotificationsProxy(c).CloseNotification(id)
        } catch (e: Exception) {
            Logger.w("[notification] D-Bus CloseNotification failed: ${e.message}")
        }
    }

    fun trackedRoomIds(): Set<String> = notifIdByRoom.keys.toSet()

    private fun postNotify(
        summary: String,
        body: String,
        roomId: String,
        eventId: String,
        desktopEntry: String?,
        iconPath: String?,
        urgency: Byte,
        soundName: String?,
        resident: Boolean,
        actions: Array<String>,
        expireTimeout: Int,
        replacesId: UInt32,
        logTag: String,
    ): UInt32? {
        repeat(2) { attempt ->
            val c = ensure() ?: return null
            try {
                val notifications = getNotificationsProxy(c)

                val hints = HashMap<String, Variant<*>>()
                desktopEntry?.let { hints["desktop-entry"] = Variant(it) }
                iconPath?.let { hints["image-path"] = Variant(it) }
                hints["urgency"] = Variant(urgency)
                if (resident) hints["resident"] = Variant(true)
                if (soundName != null && capabilities.contains("sound")) {
                    hints["sound-name"] = Variant(soundName)
                }

                val id = notifications.Notify(
                    "Mages",
                    replacesId,
                    desktopEntry ?: "mages",
                    summary,
                    formatBodyForServer(body),
                    actions,
                    hints,
                    expireTimeout
                )

                notifCtx[id] = roomId to eventId

                Logger.w(
                    "[notification] D-Bus $logTag Notify succeeded: id=$id room=$roomId event=$eventId"
                )
                return id
            } catch (e: Exception) {
                Logger.w(
                    "[notification] D-Bus $logTag Notify failed (attempt=${attempt + 1}): ${e.stackTraceToString()}"
                )
                invalidateConnection(c)
            }
        }
        return null
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

        fun CloseNotification(id: UInt32)

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
    @Volatile var answerCall: (String, String) -> Unit = { roomId, _ -> openRoom(roomId) }
    @Volatile var declineCall: (String, String) -> Unit = { _, _ -> }
}
