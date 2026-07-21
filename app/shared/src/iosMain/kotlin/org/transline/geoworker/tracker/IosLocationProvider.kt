package org.transline.geoworker.tracker

import platform.CoreLocation.*
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * One-shot fix. Must call [CLLocationManager] on the main queue (Apple),
     * and must not hang forever when GPS/simulator never delivers a fix.
     */
    override suspend fun getCurrentLocation(): Location? {
        val deferred = CompletableDeferred<Location?>()
        delegate.pendingDeferred = deferred

        dispatch_async(dispatch_get_main_queue()) {
            // Faster than Best for one-shot; continuous tracking restores Best in startTracking.
            locationManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
            locationManager.requestLocation()
        }

        val result = withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MS) {
            deferred.await()
        }

        if (result == null && delegate.pendingDeferred === deferred) {
            delegate.pendingDeferred = null
            dispatch_async(dispatch_get_main_queue()) {
                locationManager.stopUpdatingLocation()
                locationManager.desiredAccuracy = kCLLocationAccuracyBest
            }
        } else {
            dispatch_async(dispatch_get_main_queue()) {
                locationManager.desiredAccuracy = kCLLocationAccuracyBest
            }
        }

        return result
    }

    override fun startTracking(onLocation: (Location) -> Unit) {
        configureManager()
        delegate.onLocationUpdate = onLocation
        dispatch_async(dispatch_get_main_queue()) {
            locationManager.startUpdatingLocation()
        }
    }

    override fun stopTracking() {
        dispatch_async(dispatch_get_main_queue()) {
            locationManager.stopUpdatingLocation()
        }
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

    companion object {
        /** Simulator without Custom Location / cold GPS can otherwise hang indefinitely. */
        const val CURRENT_LOCATION_TIMEOUT_MS = 15_000L
    }
}
