package org.transline.geoworker.tracker

interface PlatformLocationProvider {
    suspend fun getCurrentLocation(): Location?

    /**
     * Непрерывный мониторинг GPS. Каждый апдейт передаётся в [onLocation].
     * Бизнес-троттлинг/отправка — в [LocationTrackerController.onLocationUpdate].
     */
    fun startTracking(onLocation: (Location) -> Unit)

    fun stopTracking()
}
