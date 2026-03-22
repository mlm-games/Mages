package org.mlm.mages.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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
    "set_always_on_screen",
    "minimize",
    "im.vector.hangup",
)

private fun setupAudioDeviceBridge(webView: WebView) {
    val audioManager = webView.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    @Suppress("DEPRECATION")
    fun getOutputDevices(): List<AudioDeviceInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        }
    }
    
    val devices = getOutputDevices().map { device ->
        val isSpeaker = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val isEarpiece = device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        val isExternalHeadset = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        val name = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in earpiece"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
            else -> device.productName?.toString() ?: "Unknown"
        }
        """{"id":"${device.id}","name":"$name","isSpeaker":$isSpeaker,"isEarpiece":$isEarpiece,"isExternalHeadset":$isExternalHeadset}"""
    }
    
    val devicesJson = "[${devices.joinToString(",")}]"
    
    webView.evaluateJavascript(
        """
        if (typeof controls !== 'undefined' && typeof controls.setAvailableOutputDevices === 'function') {
            controls.setAvailableOutputDevices($devicesJson);
        } else if (typeof controls !== 'undefined' && typeof controls.setAvailableAudioDevices === 'function') {
            controls.setAvailableAudioDevices($devicesJson);
        }
        """.trimIndent(),
        null
    )
}

@SuppressLint("SetJavaScriptEnabled", "ComposableNaming")
@Composable
actual fun CallWebViewHost(
    widgetUrl: String,
    onMessageFromWidget: (String) -> Unit,
    onClosed: () -> Unit,
    onMinimizeRequested: () -> Unit,
    minimized: Boolean,
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

            if (action in ELEMENT_SPECIFIC_ACTIONS) {
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

                // echo-suppressed post
                val script = "window.__MagesPostFromHost && window.__MagesPostFromHost($message) || postMessage($message, '*')" // HACK: Same as above (*)
                webView.post {
                    Log.d("WidgetBridge", "Sending to widget: $message")
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
            onClosed()
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
                            (function () {
                                if (window.__MagesBridgeInstalled) {
                                    return;
                                }
                                window.__MagesBridgeInstalled = true;
                                
                                window.__MagesEchoBlock = new Set();
                                
                                function keyFor(data) {
                                    if (!data || typeof data !== 'object') return null;
                                    return JSON.stringify({
                                        api: data.api || null,
                                        requestId: data.requestId || null,
                                        action: data.action || null,
                                        hasResponse: Object.prototype.hasOwnProperty.call(data, 'response')
                                    });
                                }
                                
                                window.__MagesPostFromHost = function(payload) {
                                    const key = keyFor(payload);
                                    if (key) window.__MagesEchoBlock.add(key);
                                    window.postMessage(payload, '*');
                                };
                                
                                window.addEventListener('message', function(ev) {
                                    const data = ev.data;
                                    if (!data || typeof data !== 'object') return;
                                    
                                    const key = keyFor(data);
                                    if (key && window.__MagesEchoBlock.delete(key)) {
                                        return;
                                    }
                                    
                                    const hasResponse = Object.prototype.hasOwnProperty.call(data, 'response');
                                    const shouldForward = 
                                        (!hasResponse && data.api === 'fromWidget') ||
                                        (hasResponse && data.api === 'toWidget');
                                    
                                    if (!shouldForward) return;
                                    
                                    if (typeof elementX !== 'undefined' && elementX.postMessage) {
                                        elementX.postMessage(JSON.stringify(data));
                                    }
                                });
                            })();
                            """.trimIndent(),
                            null
                        )
                        
                        view.postDelayed({
                            setupAudioDeviceBridge(view)
                        }, 500)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d("WidgetBridge", "Page finished: $url")

                        view?.evaluateJavascript(
                            """
                            if (typeof controls !== 'undefined') {
                                controls.onBackButtonPressed = function() {
                                    window.__MagesPostFromHost && window.__MagesPostFromHost(JSON.stringify({api:'fromWidget',action:'minimize'})) || 
                                    elementX.postMessage(JSON.stringify({api:'fromWidget',action:'minimize'}));
                                };
                            }
                            """.trimIndent(),
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
