package org.mlm.mages.push

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.mlm.mages.MatrixService
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.util.concurrent.TimeUnit

const val PUSH_PREFS = "unifiedpush_prefs"
const val PREF_ENDPOINT = "endpoint"
const val PREF_INSTANCE = "default"
private const val TAG = "UP-Mages"

/**
 * UnifiedPush entrypoint
 */
class AppPushService : PushService(), KoinComponent {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val service: MatrixService by inject()

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val url = endpoint.url
        Log.i(TAG, "onNewEndpoint: instance=$instance url=$url")

        scope.launch {
            saveEndpoint(applicationContext, url, instance)

            runCatching { service.initFromDisk() }
            registerPusher(applicationContext, url, instance, token = null)
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val raw = try {
            message.content.toString(Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to decode message content", e)
            ""
        }

        val pairs = extractMatrixPushPayload(raw)
        Log.i(TAG, "Extracted ${pairs.size} events: $pairs")

        if (pairs.isEmpty()) {
            Log.w(TAG, "No events extracted from push")
            return
        }

        // Show a lightweight notification immediately, then enrich via WorkManager
        for ((roomId, eventId) in pairs.take(3)) {
            AndroidNotificationHelper.showSingleEvent(
                this,
                AndroidNotificationHelper.NotificationText("New message", "You have a new message"),
                roomId, eventId
            )
            enqueueEnrich(roomId, eventId)
        }
    }

    override fun onUnregistered(instance: String) {
        Log.i(TAG, "Unregistered: $instance")
        removeEndpoint(applicationContext, instance)

        PushManager.registerSilently(applicationContext, instance)
        scope.launch {
            runCatching { PusherReconciler.ensureServerPusherRegistered(applicationContext, instance) }
        }
    }

    override fun onRegistrationFailed(
        reason: org.unifiedpush.android.connector.FailedReason,
        instance: String
    ) {
        Log.w(TAG, "Registration failed for $instance: $reason")
    }

    override fun onTempUnavailable(instance: String) {
        Log.i(TAG, "Temp unavailable for $instance")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private suspend fun registerPusher(
        context: Context,
        endpoint: String,
        instance: String,
        token: String?,
    ) {
        val gatewayUrl = GatewayResolver.resolveGateway(endpoint)
        val pushKey = token ?: endpoint

        // Ensure the service is restored
        runCatching { service.initFromDisk() }

        val port = service.portOrNull
        val accountId = service.activeAccount.value?.id

        val loggedIn = (port != null && service.isLoggedIn() && accountId != null)
        Log.d(TAG, "registerPusher: loggedIn=$loggedIn activeAccountId=$accountId")

        if (!loggedIn) {
            Log.w(TAG, "NOT LOGGED IN or no active account - skipping pusher registration")
            return
        }

        val ok = runCatching {
            port.registerUnifiedPush(
                appId = context.packageName,
                pushKey = pushKey,
                gatewayUrl = gatewayUrl,
                deviceName = android.os.Build.MODEL ?: "Android",
                lang = java.util.Locale.getDefault().toLanguageTag(),
                profileTag = accountId
            )
        }.getOrDefault(false)

        Log.i(TAG, "registerUnifiedPush(ok=$ok, gateway=$gatewayUrl, profileTag=$accountId)")
    }

    private fun enqueueEnrich(roomId: String, eventId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val req = OneTimeWorkRequestBuilder<NotificationEnrichWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    NotificationEnrichWorker.KEY_ROOM_ID to roomId,
                    NotificationEnrichWorker.KEY_EVENT_ID to eventId
                )
            )
            .addTag("notif:$roomId:$eventId")
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "notif:$roomId:$eventId",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }
}

// Helpers
fun saveEndpoint(context: Context, endpoint: String, instance: String) {
    context.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE).edit {
        putString(PREF_ENDPOINT + "_$instance", endpoint)
    }
}

fun getEndpoint(context: Context, instance: String): String? =
    context.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE)
        .getString(PREF_ENDPOINT + "_$instance", null)

fun removeEndpoint(context: Context, instance: String) {
    context.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE).edit {
        remove(PREF_ENDPOINT + "_$instance")
    }
}

private fun extractMatrixPushPayload(raw: String): List<Pair<String, String>> {
    if (raw.isBlank()) return emptyList()
    return try {
        val obj = org.json.JSONObject(raw)
        val pairs = mutableListOf<Pair<String, String>>()

        val notification = obj.optJSONObject("notification")
        if (notification != null) {
            val eid = notification.optString("event_id", "")
            val rid = notification.optString("room_id", "")
            if (eid.isNotBlank() && rid.isNotBlank()) {
                pairs += rid to eid
                return pairs
            }
        }

        if (obj.has("event_id") && obj.has("room_id")) {
            val eid = obj.optString("event_id")
            val rid = obj.optString("room_id")
            if (eid.isNotBlank() && rid.isNotBlank()) pairs += rid to eid
        }

        val keys = arrayOf("events", "notifications")
        for (k in keys) {
            val arr = obj.optJSONArray(k) ?: continue
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val eid = it.optString("event_id")
                val rid = it.optString("room_id")
                if (eid.isNotBlank() && rid.isNotBlank()) pairs += rid to eid
            }
        }

        pairs.distinct()
    } catch (_: Throwable) {
        emptyList()
    }
}