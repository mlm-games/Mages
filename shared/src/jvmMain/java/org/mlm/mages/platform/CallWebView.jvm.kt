package org.mlm.mages.platform

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cef.CefApp
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.*
import org.cef.network.CefRequest
import org.json.JSONObject
import java.awt.*
import java.awt.event.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*

private val ELEMENT_SPECIFIC_ACTIONS = setOf(
    "io.element.device_mute",
    "io.element.join",
    "io.element.close",
    "io.element.tile_layout",
    "io.element.spotlight_layout",
    "minimize",
    "im.vector.hangup"
)

@Composable
actual fun CallWebViewHost(
    widgetUrl: String,
    onMessageFromWidget: (String) -> Unit,
    onClosed: () -> Unit,
    onMinimizeRequested: () -> Unit,
    widgetBaseUrl: String?,
    modifier: Modifier,
    onAttachController: (CallWebViewController?) -> Unit
): CallWebViewController {

    val controller = remember {
        JcefCallWebViewController(
            onMessageFromWidget = onMessageFromWidget,
            onClosed = onClosed,
            onMinimizeRequested = onMinimizeRequested
        )
    }

    LaunchedEffect(controller) {
        onAttachController(controller)
    }

    LaunchedEffect(controller, widgetUrl) {
        controller.load(widgetUrl)
    }

    DisposableEffect(Unit) {
        onDispose {
            onAttachController(null)
            controller.close()
        }
    }

    return controller
}

private class JcefCallWebViewController(
    private val onMessageFromWidget: (String) -> Unit,
    private val onClosed: () -> Unit,
    private val onMinimizeRequested: () -> Unit,
) : CallWebViewController {

    private val disposed = AtomicBoolean(false)
    private val closedCallbackFired = AtomicBoolean(false)
    private val browserCreated = AtomicBoolean(false)
    private val browserReady = CountDownLatch(1)
    private val urlLoaded = AtomicReference<String?>(null)

    @Volatile private var frame: JFrame? = null
    @Volatile private var client: CefClient? = null
    @Volatile private var browser: CefBrowser? = null
    @Volatile private var router: CefMessageRouter? = null

    private val pendingToWidget = ConcurrentLinkedQueue<String>()

    suspend fun load(url: String) {
        if (urlLoaded.get() == url || disposed.get()) return

        // if first time
        val needsDownload = !JcefRuntime.isInitialized()
        var infoDialog: JDialog? = null

        if (needsDownload) {
            SwingUtilities.invokeLater {
                val dialog = JDialog(null as Frame?, "Mages", Dialog.ModalityType.MODELESS)
                dialog.defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
                dialog.isResizable = false

                val label = JLabel("<html><div style='text-align: center; padding: 20px;'>" +
                        "Downloading webview for calls (first time only)... <br>" +
                        "<span style='font-size: 10px; color: gray;'>This may take a minute</span>" +
                        "</div></html>")
                dialog.add(label)
                dialog.pack()
                dialog.setLocationRelativeTo(null)
                dialog.isVisible = true

                infoDialog = dialog
            }
        }

        val app: CefApp = withContext(Dispatchers.IO) {
            JcefRuntime.getOrInit()
        }

        SwingUtilities.invokeLater {
            infoDialog?.dispose()
        }

        if (browserCreated.compareAndSet(false, true)) {
            val latch = CountDownLatch(1)
            SwingUtilities.invokeLater {
                try {
                    if (!disposed.get()) {
                        createBrowserInFrame(app)
                    }
                } finally {
                    latch.countDown()
                }
            }

            withContext(Dispatchers.IO) {
                latch.await(10, TimeUnit.SECONDS)
                browserReady.await(5, TimeUnit.SECONDS)
            }
        }

        if (!disposed.get() && urlLoaded.compareAndSet(null, url)) {
            SwingUtilities.invokeLater {
                browser?.loadURL(url)
            }
        }
    }

    override fun close() {
        if (!disposed.compareAndSet(false, true)) return
        browserReady.countDown()

        SwingUtilities.invokeLater {
            runCatching { browser?.close(true) }
            runCatching {
                val cl = client
                val r = router
                if (cl != null && r != null) cl.removeMessageRouter(r)
                r?.dispose()
                cl?.dispose()
            }
            runCatching {
                frame?.isVisible = false
                frame?.dispose()
            }

            browser = null
            router = null
            client = null
            frame = null
        }
    }

    private fun fireClosedOnce() {
        if (closedCallbackFired.compareAndSet(false, true)) onClosed()
    }

    private fun handleWidgetMessage(message: String) {
        try {
            val json = JSONObject(message)
            val action = json.optString("action")

            println("[JcefCallWebView] Widget → Native: action=$action")

            if (action in ELEMENT_SPECIFIC_ACTIONS) {
                println("[JcefCallWebView] Handling Element-specific action locally: $action")
                sendElementActionResponse(message)

                when (action) {
                    "io.element.close", "im.vector.hangup" -> fireClosedOnce()
                    "minimize" -> onMinimizeRequested()
                }
                return
            }

            onMessageFromWidget(message)
        } catch (e: Exception) {
            println("[JcefCallWebView] Error parsing message: ${e.message}")
            onMessageFromWidget(message)
        }
    }

    private fun sendElementActionResponse(originalMessage: String) {
        try {
            val original = JSONObject(originalMessage)

            val response = JSONObject()
            response.put("api", "toWidget")
            response.put("widgetId", original.optString("widgetId"))
            response.put("requestId", original.optString("requestId"))
            response.put("action", original.optString("action"))
            response.put("response", JSONObject())

            val responseStr = response.toString()
            println("[JcefCallWebView] Sending Element response: $responseStr")
            postMessageToWidget(responseStr)
        } catch (e: Exception) {
            println("[JcefCallWebView] Failed to send response: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun sendToWidget(message: String) {
        if (disposed.get()) return
        println("[JcefCallWebView] Native → Widget: ${message.take(300)}")
        postMessageToWidget(message)
    }

    private fun postMessageToWidget(jsonMessage: String) {
        val b = browser ?: run {
            println("[JcefCallWebView] Browser null, queueing message")
            pendingToWidget.add(jsonMessage)
            return
        }

        val f = b.mainFrame ?: run {
            println("[JcefCallWebView] Frame null, queueing message")
            pendingToWidget.add(jsonMessage)
            return
        }

        val js = "postMessage($jsonMessage, '*')"
        f.executeJavaScript(js, b.url ?: "", 0)
    }

    private fun createBrowserInFrame(app: CefApp) {
        val cl = app.createClient()
        val routerCfg = CefMessageRouter.CefMessageRouterConfig("elementX", "elementXCancel")
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
            override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transitionType: CefRequest.TransitionType) {
                if (!frame.isMain) return
                println("[JcefCallWebView] onLoadStart: ${frame.url}")
                if (frame.url != "about:blank") {
                    injectBridge(frame)
                }
            }

            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                println("[JcefCallWebView] onLoadEnd: ${frame.url} (status: $httpStatusCode)")
                if (frame.url == "about:blank") {
                    browserReady.countDown()
                } else {
                    injectBackButtonHandler(frame)
                    flushPendingToWidget()
                }
            }

            override fun onLoadError(
                browser: CefBrowser,
                frame: CefFrame,
                errorCode: CefLoadHandler.ErrorCode,
                errorText: String,
                failedUrl: String
            ) {
                println("[JcefCallWebView] onLoadError: $failedUrl - $errorText")
                if (frame.isMain) browserReady.countDown()
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
                println("[WebViewConsole] [$level] $source:$line $message")
                return false
            }
        })

        val b = cl.createBrowser("about:blank", false, false)

        client = cl
        browser = b
        router = r

        val jframe = JFrame("Mages - Element Call")
        jframe.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        jframe.setSize(900, 700)
        jframe.setLocationRelativeTo(null)
        jframe.layout = BorderLayout()

        jframe.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                fireClosedOnce()
            }

            override fun windowActivated(e: WindowEvent?) {
                browser?.setFocus(true)
            }
        })

        val browserComponent = b.uiComponent as Component
        jframe.contentPane.add(browserComponent, BorderLayout.CENTER)
        jframe.isVisible = true

        SwingUtilities.invokeLater {
            browserComponent.requestFocusInWindow()
            browser?.setFocus(true)
        }

        frame = jframe
    }

    private fun injectBridge(frame: CefFrame) {
        // This is the original working bridge - only intercepts fromWidget requests
        val js = """
            window.addEventListener('message', function(event) {
                let message = {data: event.data, origin: event.origin};
                if (message.data.response && message.data.api == "toWidget"
                    || !message.data.response && message.data.api == "fromWidget") {
                    let json = JSON.stringify(event.data);
                    elementX({request: json, persistent: false, onSuccess: function(){}, onFailure: function(){}});
                }
            });
        """.trimIndent()

        println("[JcefCallWebView] Injecting bridge into frame: ${frame.url}")
        frame.executeJavaScript(js, frame.url, 0)
    }

    private fun injectBackButtonHandler(frame: CefFrame) {
        val js = """
            if (typeof controls !== 'undefined') {
                controls.onBackButtonPressed = function() {
                    elementX({
                        request: JSON.stringify({api:'fromWidget', action:'minimize'}),
                        persistent: false,
                        onSuccess: function(){},
                        onFailure: function(){}
                    });
                };
            }
        """.trimIndent()

        frame.executeJavaScript(js, frame.url, 0)
    }

    private fun flushPendingToWidget() {
        println("[JcefCallWebView] Flushing ${pendingToWidget.size} pending messages")
        while (true) {
            val msg = pendingToWidget.poll() ?: break
            postMessageToWidget(msg)
        }
    }
}