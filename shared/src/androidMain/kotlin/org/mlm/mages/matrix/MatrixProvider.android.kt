package org.mlm.mages.matrix

import android.content.Context
import kotlinx.coroutines.flow.first
import org.mlm.mages.MatrixService
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.platform.SettingsProvider

object MatrixProvider {
    @Volatile private var service: MatrixService? = null

    fun get(context: Context): MatrixService {
        service?.let { return it }
        synchronized(this) {
            service?.let { return it }
            // Ensure store paths
            MagesPaths.init(context)
            val s = MatrixService(createMatrixPort())
            service = s
            return s
        }
    }

    @Volatile private var syncStarted = false

    fun ensureSyncStarted() {
        val s = service ?: return
        if (!syncStarted && s.isLoggedIn()) {
            syncStarted = true
            s.startSupervisedSync(object : MatrixPort.SyncObserver {
                override fun onState(status: MatrixPort.SyncStatus) {
                }
            })
        }
    }

    suspend fun getReady(context: Context): MatrixService? {
        val svc = get(context)
        val settingsRepo = SettingsProvider.get(context)
        val hs = settingsRepo.flow.first().homeserver
        if (hs.isBlank()) return null

        runCatching { svc.init(hs) }
        return if (svc.isLoggedIn()) {
            ensureSyncStarted()
            svc
        } else null
    }
}
