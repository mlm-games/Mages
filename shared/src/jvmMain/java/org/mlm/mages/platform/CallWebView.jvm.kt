package org.mlm.mages.platform

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
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
import java.awt.BorderLayout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

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

    println("[CallWebViewHost] widgetUrl=$widgetUrl")

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

    LaunchedEffect(widgetUrl) {
        println("[CallWebViewHost] LaunchedEffect loading: $widgetUrl")
        controller.load(widgetUrl)
    }

    DisposableEffect(Unit) {
        onDispose {
            onAttachController(null)
            controller.close()
        }
    }

    SwingPanel(
        modifier = modifier,
        factory = { controller.container }
    )

    return controller
}

private class JcefCallWebViewController(
    private val onMessageFromWidget: (String) -> Unit,
    private val onClosed: () -> Unit,
    private val onMinimizeRequested: () -> Unit,
) : CallWebViewController {

    private val disposed = AtomicBoolean(false)
    private val closedCallbackFired = AtomicBoolean(false)
    private val browserReady = CountDownLatch(1)
    private val pendingUrl = AtomicReference<String?>(null)

    val container: JPanel = JPanel(BorderLayout())

    @Volatile private var client: CefClient? = null
    @Volatile private var browser: CefBrowser? = null
    @Volatile private var router: CefMessageRouter? = null

    private val pendingToWidget = ConcurrentLinkedQueue<String>()

    init {
        container.add(JLabel("Starting call (or downloading webview for first launch, please wait...)"), BorderLayout.CENTER)
    }

    suspend fun load(url: String) {
        println("[JcefController] load() called with: $url")

        if (disposed.get()) {
            println("[JcefController] Already disposed, skipping load")
            return
        }

        val app: CefApp = withContext(Dispatchers.IO) {
            JcefRuntime.getOrInit()
        }

        pendingUrl.set(url)

        withContext(Dispatchers.Main) {
            if (disposed.get()) {
                println("[JcefController] Disposed during init, skipping")
                return@withContext
            }

            if (browser == null) {
                println("[JcefController] Creating browser...")
                createBrowser(app)
            }
        }

        withContext(Dispatchers.IO) {
            println("[JcefController] Waiting for browser ready...")
            val ready = browserReady.await(5, TimeUnit.SECONDS)
            if (!ready) {
                println("[JcefController] WARNING: Browser ready timeout, trying anyway")
            }
        }

        withContext(Dispatchers.Main) {
            val urlToLoad = pendingUrl.get()
            if (urlToLoad != null && !disposed.get()) {
                println("[JcefController] Browser ready, now loading: $urlToLoad")
                browser?.loadURL(urlToLoad)
            }
        }
    }

    override fun close() {
        println("[JcefController] close() called")
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

            browser = null
            router = null
            client = null

            container.removeAll()
            container.revalidate()
            container.repaint()
        }
    }

    private fun fireClosedOnce() {
        if (closedCallbackFired.compareAndSet(false, true)) onClosed()
    }

    private fun handleWidgetMessage(message: String) {
        try {
            val json = JSONObject(message)
            val api = json.optString("api")
            val action = json.optString("action")

            println("[WidgetBridge] Widget → Native: api=$api, action=$action")

            if (action in ELEMENT_SPECIFIC_ACTIONS) {
                println("[WidgetBridge] Handling Element-specific action locally: $action")
                sendElementActionResponse(message)
                onMessageFromWidget(message)

                when (action) {
                    "io.element.close", "im.vector.hangup" -> {
                        fireClosedOnce()
                    }
                    "minimize" -> {
                        onMinimizeRequested()
                    }
                }
                return
            }

            onMessageFromWidget(message)
        } catch (e: Exception) {
            println("[WidgetBridge] Error parsing message, forwarding anyway: ${e.message}")
            onMessageFromWidget(message)
        }
    }

    private fun sendElementActionResponse(originalMessage: String) {
        try {
            val response = JSONObject(originalMessage).apply {
                put("response", JSONObject())
            }

            println("[WidgetBridge] Sending Element response: ${response.toString().take(100)}")
            postMessageToWidget(response.toString())
        } catch (e: Exception) {
            println("[WidgetBridge] Failed to send response: ${e.message}")
        }
    }

    override fun sendToWidget(message: String) {
        if (disposed.get()) return
        println("[WidgetBridge] Native → Widget: ${message.take(200)}")
        postMessageToWidget(message)
    }

    private fun postMessageToWidget(jsonMessage: String) {
        val b = browser ?: run {
            println("[WidgetBridge] Browser null, queuing message")
            pendingToWidget.add(jsonMessage)
            return
        }

        val frame = b.mainFrame ?: run {
            println("[WidgetBridge] MainFrame null, queuing message")
            pendingToWidget.add(jsonMessage)
            return
        }

        val js = "postMessage($jsonMessage, '*')"
        frame.executeJavaScript(js, b.url ?: "", 0)
    }

    private fun createBrowser(app: CefApp) {
        println("[JcefController] createBrowser()")

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
                println("[JcefController] onLoadStart: ${frame.url} (isMain=${frame.isMain})")
                if (!frame.isMain) return

                if (frame.url != "about:blank") {
                    injectBridge(frame)
                }
            }

            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                println("[JcefController] onLoadEnd: ${frame.url} (status=$httpStatusCode, isMain=${frame.isMain})")
                if (!frame.isMain) return

                if (frame.url == "about:blank") {
                    println("[JcefController] Browser ready (about:blank loaded)")
                    browserReady.countDown()
                } else {
                    println("[WidgetBridge] Page finished: ${frame.url}")
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
                println("[JcefController] onLoadError: $failedUrl - $errorCode: $errorText")
                browserReady.countDown()
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

        container.removeAll()
        container.add(b.uiComponent, BorderLayout.CENTER)
        container.revalidate()
        container.repaint()

        println("[JcefController] Browser component added to container")
    }

    private fun injectBridge(frame: CefFrame) {
        println("[JcefController] Injecting bridge into: ${frame.url}")

        val js = """
            window.addEventListener('message', function(event) {
                let message = {data: event.data, origin: event.origin};
                if (message.data.response && message.data.api == "toWidget"
                    || !message.data.response && message.data.api == "fromWidget") {
                    let json = JSON.stringify(event.data);
                    console.log('message sent: ' + json.substring(0, 100));
                    elementX({request: json, persistent: false, onSuccess: function(){}, onFailure: function(){}});
                } else {
                    console.log('message received (ignored): ' + JSON.stringify(event.data).substring(0, 100));
                }
            });
        """.trimIndent()

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
                console.log('[MagesBridge] Back button handler installed');
            }
        """.trimIndent()

        frame.executeJavaScript(js, frame.url, 0)
    }

    private fun flushPendingToWidget() {
        var count = 0
        while (true) {
            val msg = pendingToWidget.poll() ?: break
            postMessageToWidget(msg)
            count++
        }
        if (count > 0) println("[JcefController] Flushed $count pending messages")
    }
}