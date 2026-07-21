package org.transline.geoworker

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import org.transline.geoworker.tracker.Location
import org.transline.geoworker.tracker.PlatformLocationProvider

class AndroidLocationProvider(private val context: Context) : PlatformLocationProvider {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): Location? {
        return try {
            Log.d("TrackerTest", "📍 GPS: Запрос реальной локации...")
            val cancellationTokenSource = CancellationTokenSource()
            
            var androidLocation = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).await()

            if (androidLocation == null) {
                Log.w("TrackerTest", "📍 GPS: High accuracy null, пробуем lastLocation...")
                androidLocation = fusedLocationClient.lastLocation.await()
            }

            if (androidLocation != null) {
                val speed = if (androidLocation.hasSpeed()) androidLocation.speed.toDouble() else 0.0
                Location(
                    latitude = androidLocation.latitude,
                    longitude = androidLocation.longitude,
                    timestampMs = androidLocation.time,
                    speedMps = speed
                )
            } else {
                Log.w("TrackerTest", "📍 GPS: Локация null. Попробуйте обновить координаты в настройках AVD.")
                null
            }
        } catch (e: Exception) {
            Log.e("Tracker", "Android location error: ${e.message}")
            null
        }
    }

    override fun stopTracking() {
        Log.d("TrackerTest", "🛑 GPS: stopTracking (если используем requestLocationUpdates, здесь удаляем listener)")
    }
}