package org.mlm.mages

import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.util.Log
import io.github.mlmgames.settings.core.actions.ActionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.mlm.mages.di.appModules
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.platform.SettingsProvider
import org.mlm.mages.push.AndroidNotificationHelper
import org.mlm.mages.push.AppNotificationChannels
import org.mlm.mages.push.PREF_INSTANCE
import org.mlm.mages.push.PushManager
import org.mlm.mages.push.PushManager.getEndpoint
import org.mlm.mages.push.PusherReconciler
import org.mlm.mages.settings.CopyUnifiedPushEndpointAction
import org.mlm.mages.settings.OpenSystemNotificationSettingsAction
import org.mlm.mages.settings.ReRegisterUnifiedPushAction
import org.mlm.mages.settings.SelectUnifiedPushDistributorAction
import org.unifiedpush.android.connector.UnifiedPush

class MagesApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        MagesPaths.init(this)

        val settingsRepo = SettingsProvider.get(this)

        AppNotificationChannels.ensureCreated(this)

        ActionRegistry.register(OpenSystemNotificationSettingsAction::class) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        ActionRegistry.register(SelectUnifiedPushDistributorAction::class) {
            // Doesn't seem to do anything.
            PushManager.registerWithDialog(this, PREF_INSTANCE)
        }

        ActionRegistry.register(ReRegisterUnifiedPushAction::class) {
            // Need to show a snackbar message, though it is internal (toast handling).
            UnifiedPush.register(this, PREF_INSTANCE)
            PusherReconciler.ensureServerPusherRegistered(this, PREF_INSTANCE)
        }

        ActionRegistry.register(CopyUnifiedPushEndpointAction::class) {
            val ep = getEndpoint(this, PREF_INSTANCE) ?: "<none>"
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("UnifiedPush endpoint", ep))
        }


        startKoin {
            androidContext(this@MagesApp)
            modules(appModules(settingsRepo))
        }

        Log.i("Mages", "App initialized")

        appScope.launch {
            runCatching { PusherReconciler.ensureServerPusherRegistered(this@MagesApp) }
        }
    }
}