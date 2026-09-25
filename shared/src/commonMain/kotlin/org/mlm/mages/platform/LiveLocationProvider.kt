package org.mlm.mages.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    val altitude: Double? = null,
    val speed: Float? = null,
    val bearing: Float? = null
)

sealed class LocationResult {
    data object Started : LocationResult()
    data class Success(val location: LocationData) : LocationResult()
    data class Error(val message: String) : LocationResult()
    data object NotSupported : LocationResult()
    data object PermissionDenied : LocationResult()
}

interface LocationSource {
    val isSupported: Boolean
    val canSend: Boolean
    val isForeground: StateFlow<Boolean>

    suspend fun getCurrentLocation(): LocationResult

    suspend fun startLocationUpdates(): LocationResult

    fun locationUpdates(): Flow<LocationData>

    fun locationErrors(): Flow<String>

    fun stopLocationUpdates()

    fun hasLocationPermission(): Boolean

    suspend fun requestLocationPermission(): Boolean
}

expect class LiveLocationProvider() : LocationSource {
    override val isSupported: Boolean
    override val canSend: Boolean
    override val isForeground: StateFlow<Boolean>

    override suspend fun getCurrentLocation(): LocationResult
    override suspend fun startLocationUpdates(): LocationResult
    override fun locationUpdates(): Flow<LocationData>
    override fun locationErrors(): Flow<String>
    override fun stopLocationUpdates()
    override fun hasLocationPermission(): Boolean
    override suspend fun requestLocationPermission(): Boolean
}
