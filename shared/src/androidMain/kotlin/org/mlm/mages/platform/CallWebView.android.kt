package org.mlm.mages.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "CallWebView"

private fun baseOrigin(url: String): String? = runCatching {
    val u = Uri.parse(url)
    val scheme = u.scheme ?: return@runCatching null
    val host = u.host ?: return@runCatching null
    val port = u.port
    if (port == -1) "$scheme://$host" else "$scheme://$host:$port"
}.getOrNull()

private fun enableWebViewDebuggingIfDebuggable(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
}

private class AndroidCallWebViewController(
    private val onClosed: () -> Unit
) : CallWebViewController {

    private val closed = AtomicBoolean(false)

    @Volatile private var webView: WebView? = null
    @Volatile private var targetOrigin: String = "*"  // updated when url set
    @Volatile private var pageReady: Boolean = false

    private val pendingToWidget = ConcurrentLinkedQueue<String>()

    fun attach(webView: WebView, origin: String) {
        this.webView = webView
        this.targetOrigin = origin
    }

    fun markPageReady() {
        pageReady = true
        flush()
    }

    fun markPageNotReady() {
        pageReady = false
    }

    override fun sendToWidget(message: String) {
        val wv = webView
        if (wv == null || !pageReady) {
            pendingToWidget.add(message)
            return
        }

        val escapedMsg = JSONObject.quote(message)      // makes it a JS string literal safely
        val escapedOrigin = JSONObject.quote(targetOrigin)

        // Post structured JSON to the widget (Widget API v2 expects objects).
        val js = """
            (function() {
              try {
                var raw = $escapedMsg;
                var origin = $escapedOrigin;
                try { window.postMessage(JSON.parse(raw), origin); }
                catch (e) { window.postMessage(raw, origin); }
              } catch (e) {}
            })();
        """.trimIndent()

        wv.post {
            runCatching { wv.evaluateJavascript(js, null) }
        }
    }

    private fun flush() {
        while (true) {
            val msg = pendingToWidget.poll() ?: break
            sendToWidget(msg)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        onClosed()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun CallWebViewHost(
    widgetUrl: String,
    onMessageFromWidget: (String) -> Unit,
    onClosed: () -> Unit
): CallWebViewController {
    val context = LocalContext.current

    val onMessageFromWidgetLatest by rememberUpdatedState(onMessageFromWidget)
    val onClosedLatest by rememberUpdatedState(onClosed)

    LaunchedEffect(Unit) {
        enableWebViewDebuggingIfDebuggable(context)
    }

    val controller = remember {
        AndroidCallWebViewController(onClosed = { onClosedLatest() })
    }

    // Runtime permissions for camera/mic
    var hasPermissions by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions[Manifest.permission.CAMERA] == true &&
                permissions[Manifest.permission.RECORD_AUDIO] == true
    }

    LaunchedEffect(Unit) {
        val required = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        hasPermissions = required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermissions) launcher.launch(required)
    }

    // Non-blank UI if permissions missing/denied
    if (!hasPermissions) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera & microphone permissions are required to join the call.")
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(onClick = { launcher.launch(arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                    )) }) {
                        Text("Grant permissions")
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { controller.close() }) {
                        Text("Close")
                    }
                }
            }
        }

        // Still return a valid controller (messages will queue until a WebView exists)
        return controller
    }

    val origin = remember(widgetUrl) { baseOrigin(widgetUrl) ?: "*" }

    // Create WebView exactly once; do NOT destroy/recreate on widgetUrl changes.
    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(0xFF000000.toInt())
        }
    }

    DisposableEffect(Unit) {
        controller.attach(webView, origin)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.allowContentAccess = true
        settings.allowFileAccess = true

        // Cookies are often required for auth/redirect flows
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // JS -> Kotlin bridge (Widget -> host)
        // NOTE: This interface is available to any page loaded in this WebView.
        // So we also validate origins + filter messages in the injected JS.
        class Bridge {
            @android.webkit.JavascriptInterface
            fun postMessage(json: String) {
                onMessageFromWidgetLatest(json)
            }
        }
        webView.addJavascriptInterface(Bridge(), "MagesBridge")

        // --- WebChromeClient: permissions + console logs ---
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                webView.post {
                    val allowed = request.resources.filter {
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                    }.toTypedArray()

                    if (allowed.isNotEmpty()) {
                        Log.i(TAG, "Granting web permissions=${allowed.toList()} origin=${request.origin}")
                        request.grant(allowed)
                    } else {
                        Log.w(TAG, "Denying unknown web permission request resources=${request.resources.toList()}")
                        request.deny()
                    }
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(TAG, "console[${consoleMessage.messageLevel()}] ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} ${consoleMessage.message()}")
                return true
            }
        }

        // --- WebViewClient: load events + errors + inject Widget bridge ---
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                Log.i(TAG, "nav: $url")
                // You can decide to block unexpected navigations here.
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                controller.markPageNotReady()
                Log.i(TAG, "pageStarted: $url")
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.i(TAG, "pageFinished: $url")

                // Inject the message bridge:
                // - forwards Widget API v2 messages only
                // - checks origin match to reduce accidental/malicious calls into the JS interface
                val escapedOrigin = JSONObject.quote(origin)
                val js = """
                    (function() {
                      if (window.__MagesBridgeInstalled) return;
                      window.__MagesBridgeInstalled = true;

                      var allowedOrigin = $escapedOrigin;

                      function normalize(data) {
                        if (!data) return null;
                        if (typeof data === 'string') {
                          try { return JSON.parse(data); } catch (e) { return null; }
                        }
                        if (typeof data === 'object') return data;
                        return null;
                      }

                      function shouldForward(obj) {
                        if (!obj || typeof obj !== 'object') return false;
                        var api = obj.api;
                        var hasResponse = Object.prototype.hasOwnProperty.call(obj, 'response');
                        // Widget API v2 directionality (what matrix-sdk WidgetDriver expects)
                        return (api === 'fromWidget' && !hasResponse) ||
                               (api === 'toWidget'   &&  hasResponse);
                      }

                      window.addEventListener('message', function(ev) {
                        try {
                          // Basic origin guard; keep 'null' allowed because some frames/pages emit that
                          if (allowedOrigin !== "*" && ev.origin && ev.origin !== "null" && ev.origin !== allowedOrigin) {
                            return;
                          }

                          var obj = normalize(ev.data);
                          if (!shouldForward(obj)) return;

                          var jsonStr = JSON.stringify(obj);
                          MagesBridge.postMessage(jsonStr);
                        } catch (e) {
                          // swallow
                        }
                      });
                    })();
                """.trimIndent()

                view.evaluateJavascript(js, null)
                controller.markPageReady()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "onReceivedError url=${request.url} code=${error.errorCode} desc=${error.description}")
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                Log.e(TAG, "onReceivedHttpError url=${request.url} status=${errorResponse.statusCode} reason=${errorResponse.reasonPhrase}")
            }
        }

        onDispose {
            // Avoid double-closing loops: let controller.close() call onClosed, but
            // disposal should just tear down the WebView.
            runCatching {
                controller.markPageNotReady()
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.onPause()
                webView.removeAllViews()
                webView.destroy()
            }
        }
    }

    // Actually load/reload when widgetUrl changes
    LaunchedEffect(widgetUrl) {
        Log.i(TAG, "Loading widgetUrl=$widgetUrl origin=$origin")
        runCatching {
            webView.loadUrl(widgetUrl)
        }.onFailure {
            Log.e(TAG, "loadUrl failed: ${it.message}", it)
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { webView }
    )

    return controller
}