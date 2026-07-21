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
        configureManager()
    }

    private fun configureManager() {
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = 1.0
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.showsBackgroundLocationIndicator = true
    }

    override suspend fun getCurrentLocation(): Location? {
        val deferred = CompletableDeferred<Location?>()
        delegate.pendingDeferred = deferred

        locationManager.requestLocation()
        return deferred.await()
    }

    override fun startTracking(onLocation: (Location) -> Unit) {
        configureManager()
        delegate.onLocationUpdate = onLocation
        locationManager.startUpdatingLocation()
    }

    override fun stopTracking() {
        locationManager.stopUpdatingLocation()
        delegate.onLocationUpdate = null
        delegate.pendingDeferred?.complete(null)
        delegate.pendingDeferred = null
    }

    private class LocationDelegate : NSObject(), CLLocationManagerDelegateProtocol {
        var pendingDeferred: CompletableDeferred<Location?>? = null
        var onLocationUpdate: ((Location) -> Unit)? = null

        @OptIn(ExperimentalForeignApi::class)
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val clLocation = didUpdateLocations.lastOrNull() as? CLLocation ?: run {
                pendingDeferred?.complete(null)
                pendingDeferred = null
                return
            }

            val rawSpeed = clLocation.speed
            val speed = if (rawSpeed < 0) 0.0 else rawSpeed

            val location = Location(
                latitude = clLocation.coordinate.useContents { latitude },
                longitude = clLocation.coordinate.useContents { longitude },
                timestampMs = (clLocation.timestamp.timeIntervalSince1970 * 1000).toLong(),
                speedMps = speed
            )

            pendingDeferred?.complete(location)
            pendingDeferred = null
            onLocationUpdate?.invoke(location)
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            pendingDeferred?.complete(null)
            pendingDeferred = null
        }
    }
}
