package org.mlm.mages.push

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.mlm.mages.MainActivity
import org.mlm.mages.platform.LiveLocationSharingCoordinator

class LiveLocationSharingForegroundService : Service() {

    private lateinit var locationManager: LocationManager
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var hasLastLocation = false
    private val locationListener = object : android.location.LocationListener {
        override fun onLocationChanged(location: android.location.Location) {
            val lat = location.latitude
            val lon = location.longitude
            if (hasLastLocation) {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(lastLat, lastLon, lat, lon, results)
                if (results[0] < MIN_DISTANCE_M) return
            }
            lastLat = lat
            lastLon = lon
            hasLastLocation = true
            LiveLocationSharingCoordinator.dispatchLocation(lat, lon, location.accuracy)
        }
        @Deprecated("Deprecated in API 29") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val roomCount = intent?.getIntExtra(EXTRA_ROOM_COUNT, 1) ?: 1
        startForeground(NOTIFICATION_ID, buildNotification(roomCount))
        startLocationUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_INTERVAL_MS,
                0f,
                locationListener,
                mainLooper,
            )
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                UPDATE_INTERVAL_MS,
                0f,
                locationListener,
                mainLooper,
            )
        } catch (_: SecurityException) {
            // Permission will have been granted before sharing starts
        }
    }

    private fun buildNotification(roomCount: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, AppNotificationChannels.CHANNEL_LIVE_LOCATION)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Sharing live location")
            .setContentText(if (roomCount == 1) "1 room" else "$roomCount rooms")
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .build()
    }

    override fun onDestroy() {
        locationManager.removeUpdates(locationListener)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        val NOTIFICATION_ID = "live_location_foreground_service".hashCode()
        private const val ACTION_STOP = "org.mlm.mages.push.LiveLocationSharingForegroundService.STOP"
        private const val EXTRA_ROOM_COUNT = "room_count"
        private const val UPDATE_INTERVAL_MS = 10_000L
        private const val MIN_DISTANCE_M = 10f

        fun start(context: Context, roomCount: Int) {
            val intent = Intent(context, LiveLocationSharingForegroundService::class.java).apply {
                putExtra(EXTRA_ROOM_COUNT, roomCount)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LiveLocationSharingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }
}
