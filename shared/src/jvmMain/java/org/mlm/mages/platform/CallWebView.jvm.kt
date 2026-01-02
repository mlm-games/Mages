package org.mlm.mages.platform

import androidx.compose.foundation.layout.fillMaxSize
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
import java.awt.BorderLayout
import java.awt.Component
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities


@Composable
actual fun CallWebViewHost(
    widgetUrl: String,
    onMessageFromWidget: (String) -> Unit,
    onClosed: () -> Unit
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
        modifier = Modifier.fillMaxSize(),
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
    /*  Widget → Rust                                                          */
    /* ---------------------------------------------------------------------- */

    override fun sendToWidget(message: String) {
        if (disposed.get()) return

        val b = browser
        if (b == null) {
            pendingToWidget.add(message)
            return
        }

        val js = """
            (function () {
              try {
                var raw = ${jsStringLiteral(message)};
                try {
                  window.postMessage(JSON.parse(raw), "*");
                } catch (_) {
                  window.postMessage(raw, "*");
                }
              } catch (_) {}
            })();
        """.trimIndent()

        b.mainFrame.executeJavaScript(js, b.mainFrame.url, 0)
    }

    /* ---------------------------------------------------------------------- */
    /*  Setup                                                                  */
    /* ---------------------------------------------------------------------- */

    private fun createBrowser(app: org.cef.CefApp) {
        val cl = app.createClient()

        val routerCfg =
            CefMessageRouter.CefMessageRouterConfig("cefQuery", "cefQueryCancel")
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
                onMessageFromWidget(request)
                callback.success("")
                return true
            }
        }, true)

        cl.addMessageRouter(r)

        cl.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(
                browser: CefBrowser,
                frame: CefFrame,
                httpStatusCode: Int
            ) {
                if (!frame.isMain) return
                injectBridge(frame)
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
                println("JCEF [$level] $source:$line $message")
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
    /*  JS bridge (CURRENT widget spec)                                        */
    /* ---------------------------------------------------------------------- */

    private fun injectBridge(frame: CefFrame) {
        val js = """
            (function () {
              if (window.__MagesBridgeInstalled) return;
              window.__MagesBridgeInstalled = true;

              window.addEventListener("message", function (ev) {
                try {
                  if (ev.data == null) return;
                  const payload = typeof ev.data === "string"
                    ? ev.data
                    : JSON.stringify(ev.data);

                  if (typeof window.cefQuery === "function") {
                    window.cefQuery({ request: payload });
                  }
                } catch (_) {}
              }, false);
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

    private fun jsStringLiteral(s: String): String =
        buildString(s.length + 16) {
            append('\'')
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '\'' -> append("\\'")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('\'')
        }
}
