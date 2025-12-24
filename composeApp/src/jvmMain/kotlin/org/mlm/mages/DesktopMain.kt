@file:Suppress("AssignedValueIsNeverRead")

package org.mlm.mages

import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dorkbox.systemTray.MenuItem
import dorkbox.systemTray.SystemTray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mages.composeapp.generated.resources.Res
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.mlm.mages.matrix.createMatrixPort
import org.mlm.mages.platform.BindNotifications
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.platform.Notifier
import org.mlm.mages.storage.loadBoolean
import org.mlm.mages.storage.loadString
import org.mlm.mages.storage.provideAppDataStore
import org.mlm.mages.storage.saveBoolean
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

private const val PREF_START_IN_TRAY = "pref.startInTray"

fun main() = application {
    MagesPaths.init()

    val dataStore = remember { provideAppDataStore() }

    val initialStartInTray = remember {
        runBlocking { loadBoolean(dataStore, PREF_START_IN_TRAY) ?: false }
    }

    var startInTray by remember { mutableStateOf(initialStartInTray) }
    var showWindow by remember { mutableStateOf(!startInTray) }

    val deepLinkRoomIds = remember { MutableSharedFlow<String>(extraBufferCapacity = 8) }
    val deepLinks = remember { deepLinkRoomIds.asSharedFlow() }

    val scope = rememberCoroutineScope()

    var service by remember { mutableStateOf<MatrixService?>(null) }
    val serviceLock = remember { Any() }

    fun getService(): MatrixService {
        service?.let { return it }
        return synchronized(serviceLock) {
            service?.let { return it }
            MatrixService(createMatrixPort()).also { created -> service = created }
        }
    }

    val svc = getService()

    LaunchedEffect(Unit) {
        // Warm up DBus so actions work immediately
        withContext(Dispatchers.IO) {
            NotifierImpl.warmUp()
        }

        // Init Matrix client early, (if window = false, compose actually doesn't run App())
        val hs = runCatching { loadString(dataStore, "homeserver") }.getOrNull()
        if (!hs.isNullOrBlank()) {
            runCatching { svc.init(hs) }
        }

        if (svc.isLoggedIn()) {
            svc.startSupervisedSync()
            runCatching { svc.port.enterForeground() }
        }
    }


    // Install desktop notif action handlers early
    DesktopNotifActions.openRoom = { roomId ->
        SwingUtilities.invokeLater { showWindow = true }
        deepLinkRoomIds.tryEmit(roomId)
    }

    DesktopNotifActions.markRead = { roomId, eventId ->
        scope.launch {
            runCatching { svc.port.markFullyReadAt(roomId, eventId) }
        }
    }

    DesktopNotifActions.reply = { roomId, _ ->
        DesktopNotifActions.openRoom(roomId)
    }

    DesktopNotifActions.replyText = replyText@{ roomId, eventId, text ->
        val msg = text.trim()
        if (msg.isBlank()) return@replyText

        scope.launch {
            runCatching { svc.port.reply(roomId, eventId, msg) }
            runCatching { svc.port.markFullyReadAt(roomId, eventId) }
        }
    }

    val windowState = rememberWindowState()

    val tray: SystemTray? = remember {
        SystemTray.DEBUG = false

        val osName = System.getProperty("os.name").lowercase()
        // Work around some macOS issues by forcing Swing
        if (osName.contains("mac")) {
            SystemTray.FORCE_TRAY_TYPE = SystemTray.TrayType.Swing
        }

        val t = SystemTray.get()
        if (t == null) {
            println("SystemTray.get() returned null – no tray available on this platform/configuration.")
        }
        t
    }

    DisposableEffect(tray) {
        if (tray == null) {
            return@DisposableEffect onDispose { }
        }

        val iconBytes = runBlocking { Res.readBytes("files/ic_notif.png") }
        tray.setImage(iconBytes.inputStream())
        tray.setStatus("Mages")

        tray.menu.add(MenuItem("Show").apply {
            setCallback {
                SwingUtilities.invokeLater { showWindow = true }
            }
        })

        tray.menu.add(dorkbox.systemTray.Separator())

        val minimizeItem = MenuItem(
            if (startInTray) "✓ Minimize to tray on launch"
            else "Minimize to tray on launch"
        )

        minimizeItem.setCallback {
            SwingUtilities.invokeLater {
                startInTray = !startInTray
                minimizeItem.text =
                    if (startInTray) "✓ Minimize to tray on launch"
                    else "Minimize to tray on launch"
            }

            scope.launch { saveBoolean(dataStore, PREF_START_IN_TRAY, startInTray) }
        }

        tray.menu.add(minimizeItem)
        tray.menu.add(dorkbox.systemTray.Separator())

        tray.menu.add(MenuItem("Quit").apply {
            setCallback {
                SwingUtilities.invokeLater {
                    tray.shutdown()
                    exitApplication()
                }
            }
        })

        onDispose { tray.shutdown() }
    }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            NotifierImpl.warmUp()
        }
    }

    Window(
        onCloseRequest = { showWindow = false },
        state = windowState,
        visible = showWindow,
        title = "Mages"
    ) {
        val window = this.window

        DisposableEffect(window) {
            val listener = object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                    Notifier.setWindowFocused(true)
                }

                override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                    Notifier.setWindowFocused(false)
                }
            }

            window.addWindowFocusListener(listener)
            Notifier.setWindowFocused(window.isFocused)

            onDispose {
                window.removeWindowFocusListener(listener)
                Notifier.setWindowFocused(false)
            }
        }

        App(dataStore = dataStore, service = svc, deepLinks = deepLinks)
    }
    BindNotifications(service = svc, dataStore = dataStore)
}

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
        desktopEntry: String? = "mages"
    ) {
        val c = ensure() ?: return

        val notifications = c.getRemoteObject(
            "org.freedesktop.Notifications",
            "/org/freedesktop/Notifications",
            Notifications::class.java
        )

        val hints = HashMap<String, Variant<*>>()

        desktopEntry?.let { hints["desktop-entry"] = Variant(it) }

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
        val r = Regex("""(https?://\S+)""")
        return r.replace(s) { m ->
            val url = m.value
            """<a href="$url">$url</a>"""
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
            val reason: UInt32
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
