package org.mlm.mages.matrix

import android.content.Context
import org.mlm.mages.MatrixService
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.storage.loadString
import org.mlm.mages.storage.provideAppDataStore

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

        val ds = provideAppDataStore(context)
        val hs = loadString(ds, "homeserver") ?: return null

        runCatching { svc.init(hs) }
        return if (svc.isLoggedIn()) {
            ensureSyncStarted()
            svc
        } else null
    }
}
