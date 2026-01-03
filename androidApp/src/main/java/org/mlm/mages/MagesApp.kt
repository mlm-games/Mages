package org.mlm.mages

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.mlm.mages.di.appModules
import org.mlm.mages.matrix.MatrixProvider
import org.mlm.mages.platform.AppCtx
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.platform.SettingsProvider
import org.mlm.mages.push.PusherReconciler

class MagesApp : Application() {
    override fun onCreate() {
        super.onCreate()

        MagesPaths.init(this)
        AppCtx.init(this)

        val service = MatrixProvider.get(this)
        val settingsRepo = SettingsProvider.get(this)

        startKoin {
            androidContext(this@MagesApp)
            modules(appModules(service, settingsRepo))
        }

        Log.i("Mages", "App initialized")

        CoroutineScope(Dispatchers.Default).launch {
            MatrixProvider.ensureSyncStarted()
            runCatching { PusherReconciler.ensureServerPusherRegistered(this@MagesApp) }
        }
    }
}