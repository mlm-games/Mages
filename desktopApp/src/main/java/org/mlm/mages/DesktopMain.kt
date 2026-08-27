@file:Suppress("AssignedValueIsNeverRead")

package org.mlm.mages

import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kdroid.composetray.tray.api.Tray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mages.shared.generated.resources.Res
import org.maplibre.compose.desktop.MapLibre
import org.maplibre.compose.desktop.ProvideMapHost
import org.maplibre.compose.desktop.rememberAwtComposeMapHost
import org.mlm.mages.di.KoinApp
import org.mlm.mages.nav.DeepLinkAction
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.platform.Notifier
import org.mlm.mages.platform.SettingsProvider
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.SwingUtilities

fun main() {
    MapLibre.configure(
        applicationId = "org.mlm.mages",
    )

    application {
        MagesPaths.init()

    val settingsRepo = remember { SettingsProvider.get() }

    var startInTray by remember { mutableStateOf(false) }
    var startInTrayLoaded by remember { mutableStateOf(false) }
    var showWindow by remember { mutableStateOf(false) } // Start hidden, will show if needed
    var trayReady by remember { mutableStateOf(false) }

    val deepLinkEmitter = remember { MutableSharedFlow<DeepLinkAction>(extraBufferCapacity = 8) }
    val deepLinks = remember { deepLinkEmitter.asSharedFlow() }

    val scope = rememberCoroutineScope()

    val windowState = rememberWindowState()

    LaunchedEffect(Unit) {
        val initial = withContext(Dispatchers.IO) {
            runCatching { settingsRepo.flow.first().startInTray }.getOrDefault(false)
        }
        startInTray = initial
        startInTrayLoaded = true
        showWindow = !initial
        println("[tray] startInTray loaded: $initial, showWindow=$showWindow")
    }

    Tray(
        iconPath = Res.getUri("files/tray.png"),
        tooltip = "Mages",
        primaryAction = {
            println("[tray] primaryAction -> show window")
            SwingUtilities.invokeLater { showWindow = true }
        },
        onMenuOpened = {
            if (!trayReady) {
                println("[tray] onMenuOpened -> trayReady=true")
                trayReady = true
            }
        }
    ) {
        Item(label = "Show") {
            println("[tray] Show clicked -> showWindow=true (was $showWindow)")
            SwingUtilities.invokeLater { showWindow = true }
        }
        Divider()
        CheckableItem(
            label = "Minimize to tray on launch",
            checked = startInTray,
            onCheckedChange = { checked ->
                println("[tray] minimize to tray toggled: $checked (was $startInTray)")
                startInTray = checked
                scope.launch {
                    settingsRepo.update { it.copy(startInTray = checked) }
                    println("[tray] persisted startInTray=$checked")
                }
            }
        )
        Divider()
        Item(label = "Quit") {
            println("[tray] Quit clicked")
            SwingUtilities.invokeLater { exitApplication() }
        }
    }

    LaunchedEffect(startInTrayLoaded, startInTray) {
        if (!startInTrayLoaded) return@LaunchedEffect
        if (startInTray) {
            println("[tray] startInTray=true -> waiting briefly for tray registration")
            kotlinx.coroutines.delay(500) // time to register with StatusNotifierWatcher
            println("[tray] ensuring window hidden")
            showWindow = false
        } else if (!showWindow) {
            println("[tray] startInTray=false -> showing window")
            showWindow = true
        }
    }

    LaunchedEffect(startInTray) {
        if (!startInTrayLoaded) return@LaunchedEffect
        if (startInTray && showWindow) {
            println("[tray] startInTray changed to true -> hiding window")
            showWindow = false
        } else if (!startInTray && !showWindow) {
            println("[tray] startInTray changed to false -> showing window")
            showWindow = true
        }
    }

    LaunchedEffect(showWindow) {
        if (!showWindow) {
            Notifier.setWindowFocused(false)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val osName = System.getProperty("os.name").lowercase()
            if (osName.contains("linux")) {
                NotifierImpl.warmUp()
            } else {
                println("Skipping NotifierImpl warmup: D-Bus is not supported on $osName")
            }
        }
    }

    KoinApp(settingsRepo) {
        Window(
            onCloseRequest = {
                Notifier.setWindowFocused(false)
                showWindow = false
            },
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

            ProvideMapHost(host = rememberAwtComposeMapHost(window)) {
                DesktopAppContent(
                    deepLinks = deepLinks
                )
            }
        }

        DesktopBackground(
            deepLinkEmitter = deepLinkEmitter,
            scope = scope
        )
    }
}
}
