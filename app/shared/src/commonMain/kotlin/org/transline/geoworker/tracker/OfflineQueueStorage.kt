package org.transline.geoworker.tracker

interface OfflineQueueStorage {
    fun savePendingLocation(location: Location)
    fun getAllPendingLocations(): List<Location>
    fun removeLocation(timestamp: Long)
}
