package org.mlm.mages.platform

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.network.CefRequest
import org.json.JSONObject
import java.awt.BorderLayout
import java.awt.Component
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

private val ELEMENT_SPECIFIC_ACTIONS = setOf(
    "io.element.device_mute",
    "io.element.join",
    "io.element.close",
    "io.element.tile_layout",
    "io.element.spotlight_layout",
    "set_always_on_screen",
    "im.vector.hangup"
)

@Composable
actual fun CallWebViewHost(
    widgetUrl: String,
    onMessageFromWidget: (String) -> Unit,
    onClosed: () -> Unit,
    widgetBaseUrl: String?,
    modifier: Modifier,
): CallWebViewController {

    val controller = remember {
        JcefCallWebViewController(
            onMessageFromWidget = onMessageFromWidget,
            onClosed = onClosed
        )
    }

    LaunchedEffect(widgetUrl) {
        controller.load(widgetUrl)
    }

    DisposableEffect(Unit) {
        onDispose { controller.close() }
    }

    SwingPanel(
        modifier = modifier,
        factory = { controller.container }
    )

    return controller
}

/* -------------------------------------------------------------------------- */
/*  JCEF controller                                                            */
/* -------------------------------------------------------------------------- */

private class JcefCallWebViewController(
    private val onMessageFromWidget: (String) -> Unit,
    private val onClosed: () -> Unit
) : CallWebViewController {

    private val closed = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)

    val container: JPanel = JPanel(BorderLayout()).apply {
        add(JLabel("Starting call…"), BorderLayout.CENTER)
    }

    @Volatile private var client: CefClient? = null
    @Volatile private var browser: CefBrowser? = null
    @Volatile private var router: CefMessageRouter? = null

    private val pendingToWidget = ConcurrentLinkedQueue<String>()

    /* ---------------------------------------------------------------------- */
    /*  Lifecycle                                                              */
    /* ---------------------------------------------------------------------- */

    suspend fun load(url: String) {
        if (disposed.get()) return

        val app = withContext(Dispatchers.IO) {
            JcefRuntime.getOrInit()
        }

        withContext(Dispatchers.Main) {
            if (disposed.get()) return@withContext

            if (browser == null) {
                createBrowser(app)
            }

            browser?.loadURL(url)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        disposed.set(true)

        SwingUtilities.invokeLater {
            runCatching { browser?.close(true) }
            runCatching {
                val cl = client
                val r = router
                if (cl != null && r != null) cl.removeMessageRouter(r)
                r?.dispose()
                cl?.dispose()
            }

            browser = null
            router = null
            client = null

            container.removeAll()
            container.add(JLabel("Call closed"), BorderLayout.CENTER)
            container.revalidate()
            container.repaint()

            onClosed()
        }
    }

    /* ---------------------------------------------------------------------- */
    /*  Widget → Rust  (with Element-specific action handling)                 */
    /* ---------------------------------------------------------------------- */

    private fun handleWidgetMessage(message: String) {
        try {
            val json = JSONObject(message)
            val api = json.optString("api")
            val action = json.optString("action")

            println("[WidgetBridge-JVM] Widget → Native: api=$api, action=$action")

            if (api == "fromWidget" && action in ELEMENT_SPECIFIC_ACTIONS) {
                println("[WidgetBridge-JVM] Handling Element-specific action locally: $action")

                // Send success response back to widget
                sendElementActionResponse(message)

                // Handle specific actions that need app-level behavior
                when (action) {
                    "io.element.close", "im.vector.hangup" -> {
                        println("[WidgetBridge-JVM] Call ended by widget")
                        close()
                    }
                }
                return // Don't forward to SDK
            }

            // Forward all other messages to SDK
            onMessageFromWidget(message)
        } catch (e: Exception) {
            println("[WidgetBridge-JVM] Error parsing message, forwarding anyway: ${e.message}")
            onMessageFromWidget(message)
        }
    }

    private fun sendElementActionResponse(originalMessage: String) {
        try {
            val response = JSONObject(originalMessage).apply {
                put("response", JSONObject())
            }
            sendToWidget(response.toString())
        } catch (e: Exception) {
            println("[WidgetBridge-JVM] Failed to send response: ${e.message}")
        }
    }

    /* ---------------------------------------------------------------------- */
    /*  Rust → Widget                                                          */
    /* ---------------------------------------------------------------------- */

    override fun sendToWidget(message: String) {
        if (disposed.get()) return

        val b = browser
        if (b == null) {
            pendingToWidget.add(message)
            return
        }

        println("[WidgetBridge-JVM] Native → Widget: ${message.take(200)}")

        // Use postMessage like Element X does
        val js = "postMessage($message, '*')"
        b.mainFrame.executeJavaScript(js, b.mainFrame.url, 0)
    }

    /* ---------------------------------------------------------------------- */
    /*  Setup                                                                  */
    /* ---------------------------------------------------------------------- */

    private fun createBrowser(app: org.cef.CefApp) {
        val cl = app.createClient()

        val routerCfg =
            CefMessageRouter.CefMessageRouterConfig("elementX", "elementXCancel")
        val r = CefMessageRouter.create(routerCfg)

        r.addHandler(object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(
                browser: CefBrowser,
                frame: CefFrame,
                queryId: Long,
                request: String,
                persistent: Boolean,
                callback: CefQueryCallback
            ): Boolean {
                handleWidgetMessage(request)
                callback.success("")
                return true
            }
        }, true)

        cl.addMessageRouter(r)

        cl.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadStart(
                browser: CefBrowser,
                frame: CefFrame,
                transitionType: CefRequest.TransitionType
            ) {
                if (!frame.isMain) return
                // Inject bridge early (like Android's onPageStarted)
                injectBridge(frame)
            }

            override fun onLoadEnd(
                browser: CefBrowser,
                frame: CefFrame,
                httpStatusCode: Int
            ) {
                if (!frame.isMain) return
                flushPendingToWidget()
            }
        })

        cl.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(
                browser: CefBrowser,
                level: CefSettings.LogSeverity,
                message: String,
                source: String,
                line: Int
            ): Boolean {
                println("[WebViewConsole-JVM] [$level] $source:$line $message")
                return false
            }
        })

        val b = cl.createBrowser("about:blank", false, false)

        client = cl
        browser = b
        router = r

        container.removeAll()
        container.add(b.uiComponent as Component, BorderLayout.CENTER)
        container.revalidate()
        container.repaint()
    }

    /* ---------------------------------------------------------------------- */
    /*  JS bridge (matching Android/Element X implementation)                  */
    /* ---------------------------------------------------------------------- */

    private fun injectBridge(frame: CefFrame) {
        // Match Element X's message filtering logic exactly
        val js = """
            (function() {
                if (window.__MagesBridgeInstalled) return;
                window.__MagesBridgeInstalled = true;

                window.addEventListener('message', function(event) {
                    try {
                        var message = {data: event.data, origin: event.origin};
                        // Forward fromWidget requests (no response) and toWidget responses (has response)
                        if (message.data.response && message.data.api == "toWidget"
                            || !message.data.response && message.data.api == "fromWidget") {
                            var json = JSON.stringify(event.data);
                            console.log('message sent: ' + json);
                            if (typeof window.elementX === "function") {
                                window.elementX({request: json});
                            }
                        } else {
                            console.log('message received (ignored): ' + JSON.stringify(event.data));
                        }
                    } catch (e) {
                        console.error('Bridge error:', e);
                    }
                });
            })();
        """.trimIndent()

        frame.executeJavaScript(js, frame.url, 0)
    }

    private fun flushPendingToWidget() {
        while (true) {
            val msg = pendingToWidget.poll() ?: break
            sendToWidget(msg)
        }
    }
}