package org.mlm.mages.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatform
import kotlin.coroutines.resume

actual class LiveLocationProvider actual constructor() {

    private val context: Context = KoinPlatform.getKoin().get()
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    actual val isSupported: Boolean = true
    actual val canSend: Boolean = true

    @SuppressLint("MissingPermission")
    actual suspend fun getCurrentLocation(): LocationResult {
        if (!hasLocationPermission()) return LocationResult.PermissionDenied

        val lastKnown = sequenceOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

        if (lastKnown != null) {
            return LocationResult.Success(lastKnown.toLocationData())
        }

        val fresh = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Location?> { continuation ->
                locationManager.getCurrentLocation(
                    LocationManager.GPS_PROVIDER,
                    null,
                    context.mainExecutor,
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }
        }

        return if (fresh != null) {
            LocationResult.Success(fresh.toLocationData())
        } else {
            LocationResult.Error("Timed out waiting for a GPS fix")
        }
    }

    actual suspend fun startLocationUpdates() = Unit

    @SuppressLint("MissingPermission")
    actual fun locationUpdates(): Flow<LocationData> = callbackFlow {
        if (!hasLocationPermission()) {
            close()
            return@callbackFlow
        }

        val listener = LocationListener { location ->
            trySend(location.toLocationData())
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            30_000L,
            0f,
            listener,
            context.mainLooper,
        )
        locationManager.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER,
            30_000L,
            0f,
            listener,
            context.mainLooper,
        )

        awaitClose {
            locationManager.removeUpdates(listener)
        }
    }

    actual fun stopLocationUpdates() = Unit

    actual fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    actual suspend fun requestLocationPermission(): Boolean = hasLocationPermission()
}

private fun Location.toLocationData() = LocationData(
    latitude = latitude,
    longitude = longitude,
    accuracy = accuracy,
    altitude = if (hasAltitude()) altitude else null,
    speed = if (hasSpeed()) speed else null,
    bearing = if (hasBearing()) bearing else null,
)
