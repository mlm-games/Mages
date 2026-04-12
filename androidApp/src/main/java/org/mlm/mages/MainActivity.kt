package org.mlm.mages

import android.Manifest
import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.mlm.mages.activities.DistributorPickerActivity
import org.mlm.mages.di.KoinApp
import org.mlm.mages.nav.DeepLinkAction
import org.mlm.mages.nav.MatrixLink
import org.mlm.mages.nav.handleMatrixLink
import org.mlm.mages.nav.parseMatrixLink
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.platform.SettingsProvider
import org.mlm.mages.platform.AndroidBrowserAuthCoordinator
import org.mlm.mages.push.AndroidNotificationHelper
import org.mlm.mages.push.PREF_INSTANCE
import org.mlm.mages.push.PusherReconciler
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.unifiedpush.android.connector.LinkActivityHelper
import org.unifiedpush.android.connector.UnifiedPush
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    private val deepLinkActions = Channel<DeepLinkAction>(capacity = Channel.BUFFERED)
    private val deepLinks = deepLinkActions.receiveAsFlow()
    private val service: MatrixService by inject()

    private val linkHelper = LinkActivityHelper(this)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> initUnifiedPush() }

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            pendingCallAction?.invoke()
        } else {
            val snackbarManager: SnackbarManager by inject()
            snackbarManager.showError("Microphone permission is required for calls")
        }
        pendingCallAction = null
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            pendingLocationAction?.invoke()
        } else {
            val snackbarManager: SnackbarManager by inject()
            snackbarManager.showError("Location permission is required for live location sharing")
        }
        pendingLocationAction = null
    }

    private var pendingCallAction: (() -> Unit)? = null
    private var pendingLocationAction: (() -> Unit)? = null

    fun requestVoiceCallPermissions(onGranted: () -> Unit) =
        requestCallPermissions(needsCamera = false, onGranted)

    fun requestVideoCallPermissions(onGranted: () -> Unit) =
        requestCallPermissions(needsCamera = true, onGranted)

    fun requestLocationPermissions(onGranted: () -> Unit) {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (needed.isEmpty()) onGranted()
        else {
            pendingLocationAction = onGranted
            locationPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestCallPermissions(needsCamera: Boolean, onGranted: () -> Unit) {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needsCamera && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }

        if (needed.isEmpty()) onGranted()
        else {
            pendingCallAction = onGranted
            callPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val settingsRepository: SettingsRepository<AppSettings> =
            SettingsProvider.get(applicationContext)

        lifecycleScope.launch { handleIntent(intent) }

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                initUnifiedPush()
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            initUnifiedPush()
        }

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            KoinApp(settingsRepository) {
                App(
                    settingsRepository,
                    deepLinks,
                    onRequestLocationPermissions = { action -> requestLocationPermissions(action) },
                    onRequestVideoCallPermissions = { action -> requestVideoCallPermissions(action) },
                    onRequestVoiceCallPermissions = { action -> requestVoiceCallPermissions(action) }
                )
            }
        }
    }

    private fun initUnifiedPush() {
        val saved = UnifiedPush.getSavedDistributor(this)
        if (saved != null) {
            Log.i("UP-Mages", "Saved distributor: $saved → registering")
            UnifiedPush.register(this, PREF_INSTANCE)
            lifecycleScope.launch {
                runCatching { PusherReconciler.ensureServerPusherRegistered(this@MainActivity) }
            }
            return
        }

        val distributors = UnifiedPush.getDistributors(this)
        Log.i("UP-Mages", "No saved distributor. Available: $distributors")

        when {
            distributors.isEmpty() -> Log.w("UP-Mages", "No UnifiedPush distributor available")
            distributors.size == 1 -> {
                Log.i("UP-Mages", "Auto-selecting single distributor: ${distributors[0]}")
                UnifiedPush.saveDistributor(this, distributors[0])
                UnifiedPush.register(this, PREF_INSTANCE)
                lifecycleScope.launch {
                    runCatching { PusherReconciler.ensureServerPusherRegistered(this@MainActivity) }
                }
            }
            else -> {
                if (!linkHelper.startLinkActivityForResult()) {
                    showDistributorPickerOnce()
                }
            }
        }
    }

    private fun showDistributorPickerOnce() {
        val prefs = getSharedPreferences("up_mages", MODE_PRIVATE)
        if (!prefs.getBoolean("picker_shown", false)) {
            prefs.edit { putBoolean("picker_shown", true) }
            startActivity(Intent(this, DistributorPickerActivity::class.java))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (linkHelper.onLinkActivityResult(requestCode, resultCode, data)) {
            Log.i("UP-Mages", "Distributor selected")
            UnifiedPush.register(this, PREF_INSTANCE)
            lifecycleScope.launch {
                runCatching { PusherReconciler.ensureServerPusherRegistered(this@MainActivity) }
            }
        } else {
            showDistributorPickerOnce()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lifecycleScope.launch { handleIntent(intent) }
    }

    private fun handleIntent(intent: Intent) {
        val snackbarManager: SnackbarManager by inject()
        intent.data?.let { uri ->
            if (AndroidBrowserAuthCoordinator.isCallback(uri)) {
                AndroidBrowserAuthCoordinator.handleCallback(uri)
                return
            }

            if (uri.scheme == "mages" && uri.host == "room") {
                val roomId = uri.getQueryParameter("id")
                val eventId = uri.getQueryParameter("event")
                val joinCall = uri.getQueryParameter("join_call") == "1"

                if (!roomId.isNullOrBlank()) {
                    if (joinCall) {
                        AndroidNotificationHelper.cancelCallNotification(this, roomId)
                        if (!eventId.isNullOrBlank()) {
                            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            nm.cancel((roomId + eventId).hashCode())
                        }
                    }
                    deepLinkActions.trySend(DeepLinkAction(roomId, eventId, joinCall))
                }
                return
            }

            // Handles matrix.to and matrix: links
            val url = uri.toString()
            val link = parseMatrixLink(url)
            if (link !is MatrixLink.Unsupported) {
                lifecycleScope.launch {
                    if (!service.isLoggedIn() || service.portOrNull == null) {
                        snackbarManager.showError("Logged out currently.")
                        return@launch
                    }
                    handleMatrixLink(service, link) { roomId, eventId ->
                        deepLinkActions.trySend(DeepLinkAction(roomId, eventId, false))
                    }
                }
            }
        }
    }
}
