package org.mlm.mages

import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.mlm.mages.di.KoinApp
import org.mlm.mages.nav.DeepLinkAction
import org.mlm.mages.nav.MatrixLink
import org.mlm.mages.nav.handleMatrixLink
import org.mlm.mages.nav.parseMatrixLink
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.platform.SettingsProvider
import org.mlm.mages.push.AndroidNotificationHelper
import org.mlm.mages.push.PREF_INSTANCE
import org.mlm.mages.push.PushManager
import org.mlm.mages.push.PusherReconciler
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.unifiedpush.android.connector.UnifiedPush

class MainActivity : AppCompatActivity() {

    private val deepLinkActions = Channel<DeepLinkAction>(capacity = Channel.BUFFERED)
    private val deepLinks = deepLinkActions.receiveAsFlow()
    private val service: MatrixService by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        MagesPaths.init(this)

        val settingsRepository: SettingsRepository<AppSettings> =
            SettingsProvider.get(applicationContext)

        lifecycleScope.launch {
            handleIntent(intent)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE


        UnifiedPush.tryUseCurrentOrDefaultDistributor(this) { success ->
            val saved = UnifiedPush.getSavedDistributor(this)
            val dists = UnifiedPush.getDistributors(this)
            Log.i(
                "UP-Mages",
                "tryUseCurrentOrDefaultDistributor success=$success, savedDistributor=$saved, distributors=$dists"
            )

            if (success) {
                UnifiedPush.register(this, PREF_INSTANCE)
                lifecycleScope.launch {
                    runCatching { PusherReconciler.ensureServerPusherRegistered(this@MainActivity) }
                }
            } else {
                PushManager.registerWithDialog(this, PREF_INSTANCE)
            }
        }

        setContent {
            KoinApp(settingsRepository) {
                App(settingsRepository, deepLinks)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lifecycleScope.launch {
            handleIntent(intent)
        }
    }

    private fun handleIntent(intent: Intent) {
        val snackbarManager: SnackbarManager by inject()
        intent.data?.let { uri ->

            if (uri.scheme == "mages" && uri.host == "room") {
                val roomId = uri.getQueryParameter("id")
                val eventId = uri.getQueryParameter("event")
                val joinCall = uri.getQueryParameter("join_call") == "1"

                if (!roomId.isNullOrBlank()) {
                    if (joinCall) {
                        AndroidNotificationHelper.cancelCallNotification(this, roomId)
                        // Also cancel the placeholder "New message"s, doesn't seem to work?
                        if (!eventId.isNullOrBlank()) {
                            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            nm.cancel((roomId + eventId).hashCode())
                        }
                    }

                    deepLinkActions.trySend(
                        DeepLinkAction(
                            roomId = roomId,
                            eventId = eventId,
                            joinCall = joinCall
                        )
                    )
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

                    handleMatrixLink(
                        service = service,
                        link = link,
                    ) { roomId, eventId ->
                        deepLinkActions.trySend(
                            DeepLinkAction(
                                roomId = roomId,
                                eventId = eventId,
                                joinCall = false
                            )
                        )
                    }
                }
            }
        }
    }
}