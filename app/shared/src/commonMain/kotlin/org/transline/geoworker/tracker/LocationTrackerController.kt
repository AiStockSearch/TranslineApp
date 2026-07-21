@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.transline.geoworker.tracker
import io.ktor.client.HttpClient

expect fun currentTimeMillis(): Long
expect fun createPlatformHttpClient(): HttpClient

object LocationControllerFactory {
    fun createController(
        provider: PlatformLocationProvider,
        storage: TrackingStorage
    ): LocationTrackerController {
        val httpClient = createPlatformHttpClient()
        val repository = DefaultLocationRepository(httpClient, storage)
        return LocationTrackerController(provider, repository, storage)
    }
}

class LocationTrackerController(
    private val locationProvider: PlatformLocationProvider,
    private val locationRepository: LocationRepository,
    private val storage: TrackingStorage
) {
    companion object {
        const val THIRTY_MINUTES_MS = 30 * 60 * 1000L
        const val ONE_HOUR_MS = 60 * 60 * 1000L
    }

    fun getScheduleState(): TrackingScheduleState {
        val lastSent = storage.getLastSentTimestamp()
        val nextScheduled = storage.getNextScheduledTimestamp()
        val isActive = storage.isTrackingActive()

        return TrackingScheduleState(
            lastSentTimestamp = lastSent,
            nextScheduledTimestamp = nextScheduled,
            isTrackingActive = isActive
        )
    }

    fun startTrip(loadingTimeEpochMs: Long) {
        val firstTrackingTime = loadingTimeEpochMs - ONE_HOUR_MS
        storage.setTrackingActive(true)
        storage.setNextScheduledTimestamp(firstTrackingTime)
    }

    suspend fun executePendingOrScheduledTracking(): TrackingScheduleState {
        if (!storage.isTrackingActive()) {
            return getScheduleState()
        }

        val now = currentTimeMillis()
        val nextScheduled = storage.getNextScheduledTimestamp() ?: now

        if (now >= nextScheduled) {
            val location = locationProvider.getCurrentLocation()
            if (location != null) {
                val success = locationRepository.sendOrQueueLocation(location)
                if (success) {
                    val currentSentTime = now
                    val newNextTime = currentSentTime + THIRTY_MINUTES_MS

                    storage.setLastSentTimestamp(currentSentTime)
                    storage.setNextScheduledTimestamp(newNextTime)
                }
            }
        }

        return getScheduleState()
    }

    suspend fun completeTripAfterModeration() {
        val lastLocation = locationProvider.getCurrentLocation()
        if (lastLocation != null) {
            locationRepository.sendOrQueueLocation(lastLocation)
        }

        storage.clear()
        locationProvider.stopTracking()
    }

    suspend fun initializeAndSyncOnAppStart(): TrackingScheduleState {
        locationRepository.flushOfflineQueue()
        executePendingOrScheduledTracking()
        return getScheduleState()
    }
}