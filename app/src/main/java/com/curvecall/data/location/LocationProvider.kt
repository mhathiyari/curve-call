package com.curvecall.data.location

import android.annotation.SuppressLint
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/**
 * Wrapper around Google's FusedLocationProviderClient.
 * Exposes GPS location updates as a Kotlin Flow<Location>.
 *
 * Uses high-accuracy mode with ~1 second update interval, optimized
 * for real-time driving use where current speed and position must be
 * as accurate as possible.
 *
 * The caller is responsible for ensuring ACCESS_FINE_LOCATION permission
 * is granted before collecting the flow.
 */
class LocationProvider @Inject constructor(
    private val fusedLocationClient: FusedLocationProviderClient
) {

    companion object {
        /** Target interval between location updates in milliseconds. */
        private const val UPDATE_INTERVAL_MS = 1000L

        /** Fastest acceptable update interval in milliseconds. */
        private const val FASTEST_INTERVAL_MS = 500L

        /** Minimum displacement in meters before an update is delivered. */
        private const val MIN_DISPLACEMENT_METERS = 2.0f
    }

    /**
     * Flow of GPS location updates at high accuracy with ~1 second intervals.
     *
     * The flow automatically starts location updates when collected and stops
     * when the collector is cancelled. Multiple collectors share the same
     * underlying location request.
     *
     * @return Flow emitting Location objects as they arrive from GPS.
     * @throws SecurityException if location permission is not granted.
     */
    @SuppressLint("MissingPermission")
    fun locationUpdates(): Flow<Location> = callbackFlow {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISPLACEMENT_METERS)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    trySend(location)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        awaitClose {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    /**
     * Request the last known location, if available.
     * Returns null if no location has been cached by the provider.
     */
    @SuppressLint("MissingPermission")
    suspend fun getLastLocation(): Location? {
        return try {
            val task = fusedLocationClient.lastLocation
            kotlinx.coroutines.tasks.await(task)
        } catch (e: Exception) {
            null
        }
    }
}
