package org.mlm.mages.push

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import androidx.core.content.edit

object PushManager {
    private const val PREFS_NAME = "unifiedpush_prefs"
    const val DEFAULT_INSTANCE = "default"

    fun registerSilently(context: Context, instance: String = DEFAULT_INSTANCE) {
        val saved = UnifiedPush.getSavedDistributor(context)
        if (!saved.isNullOrBlank()) {
            UnifiedPush.register(context, instance)
            return
        }

        val distributors = UnifiedPush.getDistributors(context)
        if (distributors.size == 1) {
            UnifiedPush.saveDistributor(context, distributors.first())
            UnifiedPush.register(context, instance)
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