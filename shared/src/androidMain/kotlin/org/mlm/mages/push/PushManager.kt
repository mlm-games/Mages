package org.mlm.mages.push

import android.content.Context
import android.util.Log
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import androidx.core.content.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.mlm.mages.platform.SettingsProvider

object PushManager {
    private const val PREFS_NAME = "unifiedpush_prefs"
    private const val TAG = "UP-PushManager"
    const val DEFAULT_INSTANCE = "default"

    fun registerSilently(context: Context, instance: String = DEFAULT_INSTANCE) {
        val settingsRepo = SettingsProvider.get(context)
        val settings = runBlocking { settingsRepo.flow.first() }
        if (!settings.autoRegisterPushDistributor) {
            Log.i(TAG, "Auto-register disabled, skipping silent registration")
            return
        }

        val saved = UnifiedPush.getSavedDistributor(context)
        if (!saved.isNullOrBlank()) {
            Log.i(TAG, "Re-registering with saved distributor: $saved")
            UnifiedPush.register(context, instance)
            return
        }

        val distributors = UnifiedPush.getDistributors(context)
        Log.i(TAG, "No saved distributor. Available: $distributors")
        if (distributors.size == 1) {
            UnifiedPush.saveDistributor(context, distributors.first())
            UnifiedPush.register(context, instance)
        } else {
            Log.w(TAG, "Cannot register silently: ${distributors.size} distributors, none saved")
        }
    }

    fun unregister(context: Context, instance: String = DEFAULT_INSTANCE) {
        UnifiedPush.unregister(context, instance)
    }

    fun saveEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString("endpoint_$instance", endpoint.url) }
    }

    fun getEndpoint(context: Context, instance: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("endpoint_$instance", null)
    }

    fun removeEndpoint(context: Context, instance: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { remove("endpoint_$instance") }
    }
}