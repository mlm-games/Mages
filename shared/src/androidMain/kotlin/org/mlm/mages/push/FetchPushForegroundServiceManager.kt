package org.mlm.mages.push

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

object FetchPushForegroundServiceManager {
    private const val TAG = "FetchPush"
    private val stopMutex = Mutex()
    private val inFlightWorkers = AtomicInteger(0)

    fun start(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isInteractive) return false

        val intent = Intent(context, FetchPushForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "Failed to start FetchPushForegroundService", it) }
        } else {
            context.startService(intent)
        }
        return true
    }

    fun acquire(context: Context): Boolean {
        inFlightWorkers.incrementAndGet()
        val started = start(context)
        if (!started) inFlightWorkers.decrementAndGet()
        return started
    }

    suspend fun release(context: Context): Boolean {
        if (inFlightWorkers.decrementAndGet() > 0) return false
        return stop(context)
    }

    suspend fun stop(context: Context): Boolean {
        return stopMutex.withLock {
            val runningServiceInfo = getRunningServiceInfo(context)
            if (runningServiceInfo != null) {
                val intent = Intent(context, FetchPushForegroundService::class.java)
                var isInForeground = runningServiceInfo.foreground
                withTimeoutOrNull(5.seconds) {
                    while (!isInForeground) {
                        delay(50)
                        val updated = getRunningServiceInfo(context)
                        if (updated == null) return@withTimeoutOrNull
                        isInForeground = updated.foreground
                    }
                }
                context.stopService(intent)
                true
            } else {
                false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getRunningServiceInfo(context: Context): ActivityManager.RunningServiceInfo? {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service.className == FetchPushForegroundService::class.java.name }
    }
}
