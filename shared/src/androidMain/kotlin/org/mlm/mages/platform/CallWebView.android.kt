package org.mlm.mages.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.os.Build
import android.os.PowerManager
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import androidx.core.content.getSystemService
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

private val wantedDeviceTypes = listOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
)

private val audioDeviceComparator = Comparator<AudioDeviceInfo> { a, b ->
    val indexOfA = wantedDeviceTypes.indexOf(a.type).let { if (it == -1) Int.MAX_VALUE else it }
    val indexOfB = wantedDeviceTypes.indexOf(b.type).let { if (it == -1) Int.MAX_VALUE else it }
    indexOfA.compareTo(indexOfB)
}

private fun deviceName(type: Int, name: String): String {
    val typePart = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in earpiece"
        else -> "Unknown"
    }
    val isBuiltIn = type in listOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
    )
    return if (isBuiltIn) typePart else "$typePart - $name"
}

private fun listAudioDevices(audioManager: AudioManager): List<AudioDeviceInfo> {
    @Suppress("DEPRECATION")
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.availableCommunicationDevices
    } else {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    }.filter { it.type in wantedDeviceTypes && it.isSink }
      .sortedWith(audioDeviceComparator)
}

private fun buildAudioDevicesJson(audioManager: AudioManager): String {
    val devices = listAudioDevices(audioManager)
    return org.json.JSONArray().apply {
        devices.forEach { device ->
            val isSpeaker = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            val isEarpiece = device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val isExternalHeadset =
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

            put(JSONObject().apply {
                put("id", device.id.toString())
                put("name", deviceName(device.type, device.productName?.toString() ?: ""))
                put("isSpeaker", isSpeaker)
                put("isEarpiece", isEarpiece)
                put("isExternalHeadset", isExternalHeadset)
            })
        }
    }.toString()
}

private fun setupAudioDeviceBridge(webView: WebView, audioManager: AudioManager, audioDeviceBridge: AudioDeviceBridge) {
    runCatching {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }.onFailure {
        Log.w("AudioBridge", "Failed to enter communication mode before enumerating devices", it)
    }

    val devicesJson = buildAudioDevicesJson(audioManager)

    val setupCallback = """
        if (typeof controls !== 'undefined') {
            const outputHandler = function(id) {
                androidNativeBridge.setOutputDevice(String(id));
            };
            controls.onOutputDeviceSelect = outputHandler;
            if ('onAudioDeviceSelect' in controls) {
                controls.onAudioDeviceSelect = outputHandler;
            }
            controls.onAudioPlaybackStarted = function() {
                androidNativeBridge.onTrackReady();
            };
        }
    """.trimIndent()

    val setDevices = """
        if (typeof controls !== 'undefined' && typeof controls.setAvailableOutputDevices === 'function') {
            controls.setAvailableOutputDevices($devicesJson);
        } else if (typeof controls !== 'undefined' && typeof controls.setAvailableAudioDevices === 'function') {
            controls.setAvailableAudioDevices($devicesJson);
        }
    """.trimIndent()

    webView.evaluateJavascript(setupCallback, null)
    webView.evaluateJavascript(setDevices, null)
}

private class AudioDeviceBridge(
    private val context: Context,
) {
    private var webViewRef: WebView? = null
    private var currentDeviceId: Int? = null
    private var expectedNewCommunicationDeviceId: Int? = null
    private var previousSelectedDevice: AudioDeviceInfo? = null
    private var hasRegisteredCallbacks = false
    private val isInCallMode = AtomicBoolean(false)
    private val isWebViewAudioEnabled = AtomicBoolean(true)

    private val disableBluetoothAudioDevices = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    private val proximitySensorWakeLock by lazy {
        context.getSystemService<PowerManager>()
            ?.takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "${context.packageName}:ProximitySensorCallWakeLock")
    }

    private val proximitySensorMutex = Mutex()

    private val audioFocusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
    } else null

    fun setWebView(webView: android.webkit.WebView) {
        webViewRef = webView
    }

    @Suppress("DEPRECATION")
    @JavascriptInterface
    fun setOutputDevice(id: String) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val deviceIdInt = id.toIntOrNull() ?: return

        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.find { it.id == deviceIdInt }
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).find { it.id == deviceIdInt }
        } ?: return

        previousSelectedDevice = listAudioDevices(audioManager).find { it.id.toString() == id }

        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            selectAudioDevice(device)
            Log.d("AudioBridge", "Applied output device id=$id type=${device.type}")
        }.onFailure {
            Log.w("AudioBridge", "Failed to apply output device id=$id", it)
        }
    }

    @Suppress("DEPRECATION")
    @JavascriptInterface
    fun onTrackReady() {
        Log.d("AudioBridge", "Audio track ready")
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (hasRegisteredCallbacks) return@postDelayed

            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    commsDeviceChangedListener?.let {
                        audioManager.addOnCommunicationDeviceChangedListener(
                            Executors.newSingleThreadExecutor(),
                            it
                        )
                    }
                } catch (e: Exception) {
                    Log.w("AudioBridge", "Failed to add communication device listener", e)
                }
            }

            val devices = listAudioDevices(audioManager)
            setAvailableAudioDevicesInWebView(devices)

            val firstDevice = devices.firstOrNull()
            if (firstDevice != null && currentDeviceId == null) {
                selectAudioDevice(firstDevice)
            }

            hasRegisteredCallbacks = true
        }, 2000)
    }

    private val commsDeviceChangedListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            if (device != null && device.id == expectedNewCommunicationDeviceId) {
                expectedNewCommunicationDeviceId = null
                Log.d("AudioBridge", "Audio device changed, type: ${device.type}")
                updateSelectedAudioDeviceInWebView(device.id.toString())
            } else if (device != null && device.id != expectedNewCommunicationDeviceId) {
                val expectedDeviceId = expectedNewCommunicationDeviceId
                if (expectedDeviceId != null) {
                    expectedNewCommunicationDeviceId = null
                    selectAudioDeviceById(expectedDeviceId.toString())
                }
            } else {
                expectedNewCommunicationDeviceId = null
                Log.d("AudioBridge", "Audio device cleared")
            }
        }
    } else null

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            val validNewDevices = addedDevices.orEmpty().filter { it.type in wantedDeviceTypes && it.isSink }
            if (validNewDevices.isEmpty()) return

            Log.d("AudioBridge", "Audio devices added: ${validNewDevices.size}")

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val audioDevices = (listAudioDevices(audioManager) + validNewDevices).distinctBy { it.id }
            setAvailableAudioDevicesInWebView(audioDevices)

            val firstDevice = audioDevices.firstOrNull()
            if (firstDevice != null && (currentDeviceId == null || !audioDevices.any { it.id == currentDeviceId })) {
                selectAudioDeviceById(firstDevice.id.toString())
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            Log.d("AudioBridge", "Audio devices removed")

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            setAvailableAudioDevicesInWebView(listAudioDevices(audioManager))

            val removedCurrentDevice = removedDevices.orEmpty().any { it.id == currentDeviceId }
            if (!removedCurrentDevice) return

            val previousDevice = previousSelectedDevice
            if (previousDevice != null) {
                previousSelectedDevice = null
                selectAudioDeviceById(previousDevice.id.toString())
            } else {
                val devices = listAudioDevices(audioManager)
                val firstDevice = devices.firstOrNull()
                if (firstDevice != null) {
                    selectAudioDeviceById(firstDevice.id.toString())
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun selectAudioDevice(device: AudioDeviceInfo?) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        currentDeviceId = device?.id

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (device != null) {
                runCatching {
                    Log.d("AudioBridge", "Setting communication device: ${device.id} - ${deviceName(device.type, device.productName?.toString() ?: "")}")
                    audioManager.setCommunicationDevice(device)
                }.onFailure {
                    Log.e("AudioBridge", "Could not set communication device.", it)
                }
            } else {
                runCatching {
                    audioManager.clearCommunicationDevice()
                }.onFailure {
                    Log.e("AudioBridge", "Could not clear communication device.", it)
                }
            }
        } else {
            if (device != null) {
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && disableBluetoothAudioDevices) {
                    Log.w("AudioBridge", "Bluetooth audio devices are disabled on this Android version")
                    setAudioEnabled(false)
                    onInvalidAudioDeviceAdded()
                    return
                }
                setAudioEnabled(true)
                audioManager.isSpeakerphoneOn = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                audioManager.isBluetoothScoOn = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } else {
                audioManager.isSpeakerphoneOn = false
                audioManager.isBluetoothScoOn = false
            }
        }

        expectedNewCommunicationDeviceId = null

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                val held = proximitySensorMutex.tryLock()
                try {
                    if (device?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && proximitySensorWakeLock?.isHeld == false) {
                        proximitySensorWakeLock?.acquire(10000)
                    } else if (proximitySensorWakeLock?.isHeld == true) {
                        proximitySensorWakeLock?.release()
                    }
                } finally {
                    if (held) proximitySensorMutex.unlock()
                }
            }
        }
    }

    private fun selectAudioDeviceById(deviceId: String) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val audioDevice = audioManager.availableCommunicationDevices.find { it.id.toString() == deviceId }
            selectAudioDevice(audioDevice)
        } else {
            val rawAudioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val audioDevice = rawAudioDevices.find { it.id.toString() == deviceId }
            selectAudioDevice(audioDevice)
        }
    }

    private fun setAudioEnabled(enabled: Boolean) {
        val webView = webViewRef ?: return
        webView.post {
            if (isWebViewAudioEnabled.getAndSet(enabled) != enabled) {
                webView.evaluateJavascript(
                    "if (typeof controls !== 'undefined' && typeof controls.setAudioEnabled === 'function') { controls.setAudioEnabled($enabled); }",
                    null
                )
                Log.d("AudioBridge", "Setting audio enabled in Element Call: $enabled")
            }
        }
    }

    private fun onInvalidAudioDeviceAdded() {
        Log.w("AudioBridge", "Invalid audio device added")
    }

    private fun setAvailableAudioDevicesInWebView(devices: List<AudioDeviceInfo>) {
        val webView = webViewRef ?: return
        val devicesJson = org.json.JSONArray().apply {
            devices.forEach { device ->
                val isSpeaker = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                val isEarpiece = device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val isExternalHeadset =
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

                put(org.json.JSONObject().apply {
                    put("id", device.id.toString())
                    put("name", deviceName(device.type, device.productName?.toString() ?: ""))
                    put("isSpeaker", isSpeaker)
                    put("isEarpiece", isEarpiece)
                    put("isExternalHeadset", isExternalHeadset)
                })
            }
        }.toString()

        webView.post {
            webView.evaluateJavascript(
                "if (typeof controls !== 'undefined' && typeof controls.setAvailableOutputDevices === 'function') { controls.setAvailableOutputDevices($devicesJson); }",
                null
            )
        }
    }

    private fun updateSelectedAudioDeviceInWebView(deviceId: String) {
        val webView = webViewRef ?: return
        webView.post {
            webView.evaluateJavascript(
                "if (typeof controls !== 'undefined' && typeof controls.setOutputDevice === 'function') { controls.setOutputDevice('$deviceId'); }",
                null
            )
        }
    }

    fun onCallStarted() {
        if (!isInCallMode.compareAndSet(false, true)) {
            Log.w("AudioBridge", "Audio: tried to enable webview in-call audio mode while already in it")
            return
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AudioManager.MODE_IN_COMMUNICATION
        } else {
            AudioManager.MODE_NORMAL
        }

        requestAudioFocus(audioManager)
    }

    private fun requestAudioFocus(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
    }

    private fun abandonAudioFocus(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun onCallStopped() {
        if (!isInCallMode.compareAndSet(true, false)) {
            Log.w("AudioBridge", "Audio: tried to disable webview in-call audio mode while already disabled")
            return
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
        abandonAudioFocus(audioManager)

        runCatching {
            val held = proximitySensorMutex.tryLock()
            try {
                if (proximitySensorWakeLock?.isHeld == true) {
                    proximitySensorWakeLock?.release()
                }
            } finally {
                if (held) proximitySensorMutex.unlock()
            }
        }

        if (!hasRegisteredCallbacks) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            commsDeviceChangedListener?.let {
                runCatching { audioManager.removeOnCommunicationDeviceChangedListener(it) }
            }
            runCatching { audioManager.clearCommunicationDevice() }
        }

        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        hasRegisteredCallbacks = false
    }
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
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val audioDeviceBridge = remember { AudioDeviceBridge(context) }

    LaunchedEffect(minimized) {
        if (minimized) {
            audioDeviceBridge.onCallStarted()
        } else {
            audioDeviceBridge.onCallStopped()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            audioDeviceBridge.onCallStopped()
            onAttachController(null)
            val wv = webViewRef.getAndSet(null)
            wv?.apply {
                stopLoading()
                removeJavascriptInterface("elementX")
                removeJavascriptInterface("androidNativeBridge")
                webChromeClient = null
                destroy()
            }
        }
    }

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

                when (action) {
                    "io.element.close", "im.vector.hangup" -> onClosed()
                    "minimize" -> onMinimizeRequested()
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

            override fun close() {}
        }
    }

    LaunchedEffect(controller) {
        onAttachController(controller)
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

                addJavascriptInterface(audioDeviceBridge, "androidNativeBridge")
                audioDeviceBridge.setWebView(this)

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
                                if (window.__MagesBridgeInstalled) return;
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
                                    if (key && window.__MagesEchoBlock.delete(key)) return;
                                    const hasResponse = Object.prototype.hasOwnProperty.call(data, 'response');
                                    const shouldForward = (!hasResponse && data.api === 'fromWidget') || (hasResponse && data.api === 'toWidget');
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
                            setupAudioDeviceBridge(view, audioManager, audioDeviceBridge)
                        }, 500)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d("WidgetBridge", "Page finished: $url")

                        view?.evaluateJavascript(
                            """
                            if (typeof controls !== 'undefined') {
                                controls.onBackButtonPressed = function() {
                                    const payload = { api: 'fromWidget', action: 'minimize' };
                                    if (window.__MagesPostFromHost) {
                                        window.__MagesPostFromHost(payload);
                                    } else if (typeof elementX !== 'undefined' && elementX.postMessage) {
                                        elementX.postMessage(JSON.stringify(payload));
                                    }
                                };
                            }
                            """.trimIndent(),
                            null
                        )

                        view?.let { wv ->
                            wv.postDelayed({
                                setupAudioDeviceBridge(wv, audioManager, audioDeviceBridge)
                            }, 250)
                        }
                    }
                }

                webViewRef.set(this)
                loadUrl(widgetUrl)
            }
        },
        update = { webView ->
            webViewRef.set(webView)
            webView.invalidateOutline()
        }
    )

    return controller
}
