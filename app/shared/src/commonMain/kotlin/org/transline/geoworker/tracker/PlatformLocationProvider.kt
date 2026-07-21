package org.transline.geoworker.tracker

interface PlatformLocationProvider {
    suspend fun getCurrentLocation(): Location?
    fun stopTracking()
}
