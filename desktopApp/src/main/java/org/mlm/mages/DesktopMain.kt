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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mages.shared.generated.resources.Res
import org.mlm.mages.matrix.createMatrixPort
import org.mlm.mages.platform.BindNotifications
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.platform.Notifier
import org.mlm.mages.platform.SettingsProvider
import org.mlm.mages.settings.AppSettings
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.SwingUtilities

fun main() = application {
    MagesPaths.init()

    val settingsRepo = remember { SettingsProvider.get() }
    val initialStartInTray = remember {
        runBlocking { settingsRepo.flow.first().startInTray }
    }

    val settings = settingsRepo.flow.collectAsState(AppSettings()).value

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
        val hs = settings.homeserver
        if (hs.isNotBlank()) {
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

            scope.launch {
                settingsRepo.update { it.copy(startInTray = startInTray) }
            }
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
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) {
                    Notifier.setWindowFocused(true)
                }

                override fun windowLostFocus(e: WindowEvent?) {
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

        App( svc, settingsRepo, deepLinks)
    }
    BindNotifications(service = svc, settingsRepo)
}
