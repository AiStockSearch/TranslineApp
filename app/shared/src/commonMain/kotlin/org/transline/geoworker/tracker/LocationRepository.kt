package org.transline.geoworker.tracker

class LocationRepository(
    private val apiService: LocationApiService,
    private val networkChecker: NetworkChecker,
    private val offlineQueue: OfflineQueueStorage
) {
    suspend fun sendOrQueueLocation(location: Location): Boolean {
        offlineQueue.savePendingLocation(location)

        if (!networkChecker.isNetworkAvailable()) {
            return false 
        }

        return flushOfflineQueue()
    }

    // Отправляет все накопившиеся офлайн-локации на сервер
    suspend fun flushOfflineQueue(): Boolean {
        if (!networkChecker.isNetworkAvailable()) {
            return false
        }

        val pending = offlineQueue.getAllPendingLocations()
        for (item in pending) {
            val isSuccess = apiService.sendLocation(item)
            if (isSuccess) {
                offlineQueue.removeLocation(item.timestampMs)
            } else {
                return false
            }
        }
        return true
    }
}
