package org.mlm.mages.platform

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Outline
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.*
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import androidx.core.net.toUri
import org.koin.compose.koinInject
import org.mlm.mages.ui.components.snackbar.SnackbarManager

private fun allowedOrigins(widgetBaseUrl: String?): Set<String> =
    setOf("*") // HACK: using baseUrl for element call embedded crashes app, DO NOT USE

// Element-specific actions that the SDK doesn't handle
private val ELEMENT_SPECIFIC_ACTIONS = setOf(
    "io.element.device_mute",
    "io.element.join",
    "io.element.close",
    "io.element.tile_layout",
    "io.element.spotlight_layout",
    "minimize",
    "im.vector.hangup"
)

@SuppressLint("SetJavaScriptEnabled", "ComposableNaming")
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
    val context = LocalContext.current
    val activity = LocalActivity.current
    val webViewRef = remember { AtomicReference<WebView?>(null) }
    val density = LocalDensity.current

    Log.d("WidgetBridge", "Loading URL: $widgetUrl")

    // Helper to send response back to widget for Element-specific actions
    fun sendElementActionResponse(webView: WebView, originalMessage: String) {
        try {
            val response = JSONObject(originalMessage).apply {
                put("response", JSONObject())
            }
            val origin = "*" // HACK: Same as above
            val script = "postMessage(${response}, '$origin')"
            Log.d("WidgetBridge", "Sending Element response: ${response.toString().take(100)}")
            webView.post { webView.evaluateJavascript(script, null) }
        } catch (e: Exception) {
            Log.e("WidgetBridge", "Failed to send response", e)
        }
    }

    fun handleWidgetMessage(webView: WebView, message: String) {
        try {
            val json = JSONObject(message)
            val api = json.optString("api")
            val action = json.optString("action")

            Log.d("WidgetBridge", "Widget → Native: api=$api, action=$action")

            if (action in ELEMENT_SPECIFIC_ACTIONS) {
                Log.d("WidgetBridge", "Handling Element-specific action locally: $action")

                sendElementActionResponse(webView, message)
                onMessageFromWidget(message)

                when (action) {
                    "io.element.close", "im.vector.hangup" -> {
                        onClosed()
                    }
                    "minimize" -> {
                        onMinimizeRequested()
                    }
                    else -> {}
                }
                return
            }

            onMessageFromWidget(message)
        } catch (e: Exception) {
            Log.e("WidgetBridge", "Error parsing message, forwarding anyway", e)
            onMessageFromWidget(message)
        }
    }

    val controller = remember {
        object : CallWebViewController {
            override fun sendToWidget(message: String) {
                val webView = webViewRef.get() ?: run {
                    Log.e("WidgetBridge", "WebView is null!")
                    return
                }
                Log.d("WidgetBridge", "Native → Widget: ${message.take(200)}")

                val origin = "*" // HACK: Same as above
                val script = "postMessage($message, '$origin')"
                webView.post {
                    webView.evaluateJavascript(script) { result ->
                        Log.d("WidgetBridge", "postMessage result: $result")
                    }
                }
            }

            override fun close() {
                // Jvm only
            }
        }
    }

    LaunchedEffect(controller) {
        onAttachController(controller)
    }

    DisposableEffect(Unit) {
        onDispose {
            onAttachController(null)
            val wv = webViewRef.getAndSet(null)
            wv?.destroy()
        }
    }

    val assetLoader = remember {
        WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                clipToOutline = true
                clipChildren = true

                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val cornerRadius = with(density) { 16.dp.toPx() }
                        outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                    }
                }

                setBackgroundColor(Color.TRANSPARENT)

                WebView.setWebContentsDebuggingEnabled(true)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.allowFileAccess = false
                settings.allowContentAccess = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.setGeolocationEnabled(false)
                @Suppress("DEPRECATION")
                settings.databaseEnabled = true

                val webViewInstance = this

                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(
                        this,
                        "elementX",
                        setOf("*") //HACK, The origin rules is not yet supported!: allowedOrigins(widgetBaseUrl)
                    ) { _, message, _, _, _ ->
                        val payload = message.data ?: return@addWebMessageListener
                        handleWidgetMessage(webViewInstance, payload)
                    }
                } else {
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun postMessage(json: String?) {
                            json?.let { handleWidgetMessage(webViewInstance, it) }
                        }
                    }, "elementX")
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        request.grant(request.resources)
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.let {
                            Log.d("WebViewConsole", "${it.messageLevel()}: ${it.message()}")
                        }
                        return true
                    }
                }

                webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        return assetLoader.shouldInterceptRequest(request.url)
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        url: String
                    ): WebResourceResponse? {
                        return assetLoader.shouldInterceptRequest(url.toUri())
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)

                        view.evaluateJavascript(
                            """
                            window.addEventListener('message', function(event) {
                                let message = {data: event.data, origin: event.origin}
                                if (message.data.response && message.data.api == "toWidget"
                                    || !message.data.response && message.data.api == "fromWidget") {
                                    let json = JSON.stringify(event.data);
                                    console.log('message sent: ' + json);
                                    elementX.postMessage(json);
                                } else {
                                    console.log('message received (ignored): ' + JSON.stringify(event.data));
                                }
                            });
                            """.trimIndent(),
                            null
                        )
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d("WidgetBridge", "Page finished: $url")

                        view?.evaluateJavascript(
                            "controls.onBackButtonPressed = () => { elementX.postMessage(JSON.stringify({api:'fromWidget',action:'minimize'})) }",
                            null
                        )
                    }
                }

                webViewRef.set(this)
                loadUrl(widgetUrl)
            }
        },
        update = { webView ->
            webViewRef.set(webView)
            // for minimize/restore
            webView.invalidateOutline()
        }
    )

    return controller
}