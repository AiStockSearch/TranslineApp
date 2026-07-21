package org.transline.geoworker.tracker

class StorageOfflineQueueStorage(private val trackingStorage: TrackingStorage) : OfflineQueueStorage {
    private val key = "offline_location_queue"

    override fun savePendingLocation(location: Location) {
        val existingStr = trackingStorage.getString(key) ?: ""
        val newEntry = "${location.latitude},${location.longitude},${location.timestampMs}"
        val updatedStr = if (existingStr.isEmpty()) newEntry else "$existingStr;$newEntry"
        trackingStorage.putString(key, updatedStr)
    }

    override fun getAllPendingLocations(): List<Location> {
        val str = trackingStorage.getString(key) ?: return emptyList()
        if (str.isEmpty()) return emptyList()
        return str.split(";").mapNotNull {
            val parts = it.split(",")
            if (parts.size == 3) {
                try {
                    Location(parts[0].toDouble(), parts[1].toDouble(), parts[2].toLong())
                } catch (e: Exception) {
                    null
                }
            } else null
        }
    }

    override fun removeLocation(timestamp: Long) {
        val locations = getAllPendingLocations().filter { it.timestampMs != timestamp }
        val updatedStr = locations.joinToString(";") { "${it.latitude},${it.longitude},${it.timestampMs}" }
        trackingStorage.putString(key, updatedStr)
    }
}
