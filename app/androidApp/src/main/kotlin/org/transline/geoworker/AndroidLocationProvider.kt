package org.transline.geoworker

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import org.transline.geoworker.tracker.Location
import org.transline.geoworker.tracker.PlatformLocationProvider
import org.transline.geoworker.tracker.clampSpeedMps

class AndroidLocationProvider(private val context: Context) : PlatformLocationProvider {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private var locationCallback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): Location? {
        return try {
            val cancellationTokenSource = CancellationTokenSource()

            var androidLocation = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).await()

            if (androidLocation == null) {
                Log.d(TAG, "High accuracy null, trying lastLocation")
                androidLocation = fusedLocationClient.lastLocation.await()
            }

            androidLocation?.toTrackerLocation()
        } catch (e: Exception) {
            Log.e(TAG, "Android location error: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    override fun startTracking(onLocation: (Location) -> Unit) {
        stopTracking()

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateDistanceMeters(1f)
            .setMinUpdateIntervalMillis(1_000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val androidLocation = result.lastLocation ?: return
                onLocation(androidLocation.toTrackerLocation())
            }
        }
        locationCallback = callback

        fusedLocationClient.requestLocationUpdates(
            request,
            callback,
            Looper.getMainLooper()
        )
        Log.d(TAG, "continuous tracking started")
    }

    override fun stopTracking() {
        locationCallback?.let { callback ->
            fusedLocationClient.removeLocationUpdates(callback)
            Log.d(TAG, "continuous tracking stopped")
        }
        locationCallback = null
    }

    companion object {
        private const val TAG = "GeoLocationProvider"
    }

    private fun android.location.Location.toTrackerLocation(): Location {
        val speed = if (hasSpeed()) clampSpeedMps(speed.toDouble()) else 0.0
        return Location(
            latitude = latitude,
            longitude = longitude,
            timestampMs = time,
            speedMps = speed
        )
    }
}
