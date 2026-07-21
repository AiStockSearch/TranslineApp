package org.transline.geoworker.tracker

import platform.CoreLocation.*
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents

class IosLocationProvider : PlatformLocationProvider {
    private val locationManager = CLLocationManager()
    private val delegate = LocationDelegate()

    init {
        locationManager.delegate = delegate
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = kCLDistanceFilterNone
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
    }

    override suspend fun getCurrentLocation(): Location? {
        val deferred = CompletableDeferred<Location?>()
        delegate.pendingDeferred = deferred
        
        locationManager.requestLocation()
        return deferred.await()
    }

    override fun stopTracking() {
        locationManager.stopUpdatingLocation()
    }

    private class LocationDelegate : NSObject(), CLLocationManagerDelegateProtocol {
        var pendingDeferred: CompletableDeferred<Location?>? = null

        @OptIn(ExperimentalForeignApi::class)
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val clLocation = didUpdateLocations.lastOrNull() as? CLLocation
            if (clLocation != null) {
                val rawSpeed = clLocation.speed
                val speed = if (rawSpeed < 0) 0.0 else rawSpeed

                val location = Location(
                    latitude = clLocation.coordinate.useContents { latitude },
                    longitude = clLocation.coordinate.useContents { longitude },
                    timestampMs = (clLocation.timestamp.timeIntervalSince1970 * 1000).toLong(),
                    speedMps = speed
                )
                pendingDeferred?.complete(location)
            } else {
                pendingDeferred?.complete(null)
            }
            pendingDeferred = null
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            pendingDeferred?.complete(null)
            pendingDeferred = null
        }
    }
}