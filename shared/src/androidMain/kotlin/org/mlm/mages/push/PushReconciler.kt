package org.mlm.mages.push

import android.content.Context
import android.util.Log
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mlm.mages.MatrixService
import org.mlm.mages.push.PushManager.getEndpoint

object PusherReconciler : KoinComponent {

    private const val TAG = "PusherReconciler"

    private val service: MatrixService by inject()

    suspend fun ensureServerPusherRegistered(context: Context, instance: String = PREF_INSTANCE) {
        val endpoint = getEndpoint(context, instance) ?: run {
            Log.i(TAG, "No saved UnifiedPush endpoint; nothing to reconcile")
            return
        }

        val gatewayUrl = GatewayResolver.resolveGateway(endpoint)

        runCatching { service.initFromDisk() }

        val port = service.portOrNull
        val accountId = service.activeAccount.value?.id

        if (port == null || !service.isLoggedIn() || accountId == null) {
            Log.w(TAG, "Not logged in / no active account; will reconcile later")
            return
        }

        val ok = runCatching {
            port.registerUnifiedPush(
                appId = context.packageName,
                pushKey = endpoint,
                gatewayUrl = gatewayUrl,
                deviceName = android.os.Build.MODEL ?: "Android",
                lang = java.util.Locale.getDefault().toLanguageTag(),
                profileTag = accountId
            )
        }.getOrDefault(false)

        Log.i(TAG, "registerUnifiedPush(pushKey=$endpoint, gateway=$gatewayUrl, profileTag=$accountId, ok=$ok)")
    }
}