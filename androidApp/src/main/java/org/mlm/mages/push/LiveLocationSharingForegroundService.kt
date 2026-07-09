package org.mlm.mages.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.os.ExecutorCompat
import org.mlm.mages.MainActivity
import org.mlm.mages.platform.LiveLocationSharingCoordinator

class LiveLocationSharingForegroundService : Service() {

    private lateinit var locationManager: LocationManager
    private var handlerThread: HandlerThread? = null
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var hasLastLocation = false
    private val locationListener = LocationListenerCompat { location ->
        val lat = location.latitude
        val lon = location.longitude
        if (hasLastLocation) {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(lastLat, lastLon, lat, lon, results)
            if (results[0] < MIN_DISTANCE_M) return@LocationListenerCompat
        }
        lastLat = lat
        lastLon = lon
        hasLastLocation = true
        LiveLocationSharingCoordinator.dispatchLocation(lat, lon, location.accuracy)
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
        if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return

        val thread = HandlerThread("LiveLocationUpdates").also {
            it.start()
            handlerThread = it
        }
        val executor = ExecutorCompat.create(Handler(thread.looper))

        val providers = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
        val provider = providers.firstOrNull { LocationManagerCompat.hasProvider(locationManager, it) } ?: return

        val request = LocationRequestCompat.Builder(UPDATE_INTERVAL_MS)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
            .build()

        LocationManagerCompat.requestLocationUpdates(
            locationManager,
            provider,
            request,
            executor,
            locationListener,
        )
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
        handlerThread?.let {
            LocationManagerCompat.removeUpdates(locationManager, locationListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                it.quitSafely()
            } else {
                @Suppress("DEPRECATION")
                it.quit()
            }
        }
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
