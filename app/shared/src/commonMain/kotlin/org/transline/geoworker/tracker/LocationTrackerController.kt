@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.transline.geoworker.tracker

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

class LocationTrackerController(
    private val locationProvider: PlatformLocationProvider,
    private val locationRepository: LocationRepository,
    private val storage: TrackingStorage // Кэш-хранилище (SharedPreferences / UserDefaults)
) {
    companion object {
        const val THIRTY_MINUTES_MS = 30 * 60 * 1000L
        const val ONE_HOUR_MS = 60 * 60 * 1000L
    }

    // Получить текущий график
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

    // Вызывается при назначении рейса
    fun startTrip(loadingTimeEpochMs: Long) {
        val firstTrackingTime = loadingTimeEpochMs - ONE_HOUR_MS
        storage.setTrackingActive(true)
        storage.setNextScheduledTimestamp(firstTrackingTime)
    }

    // Главный метод выполнения отправки (вызывается фоновой службой или при восстановлении)
    suspend fun executePendingOrScheduledTracking(): TrackingScheduleState {
        if (!storage.isTrackingActive()) {
            return getScheduleState()
        }

        val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
        val nextScheduled = storage.getNextScheduledTimestamp() ?: now

        // Если время прошло (например, телефон был выключен и включился) или настало
        if (now >= nextScheduled) {
            val location = locationProvider.getCurrentLocation()
            if (location != null) {
                val success = locationRepository.sendOrQueueLocation(location)
                if (success) {
                    val currentSentTime = now
                    val newNextTime = currentSentTime + THIRTY_MINUTES_MS

                    // Атомарно сохраняем в кэш-файл
                    storage.setLastSentTimestamp(currentSentTime)
                    storage.setNextScheduledTimestamp(newNextTime)
                }
            }
        }

        return getScheduleState()
    }

    // Завершение рейса после модерации
    suspend fun completeTripAfterModeration() {
        val lastLocation = locationProvider.getCurrentLocation()
        if (lastLocation != null) {
            locationRepository.sendOrQueueLocation(lastLocation)
        }
        
        // Очищаем кэш и останавливаем
        storage.clear()
        locationProvider.stopTracking()
    }

    // Вызывается сразу при старте приложения / инициализации модуля
    suspend fun initializeAndSyncOnAppStart(): TrackingScheduleState {
        // 1. Проверяем и отправляем накопившуюся офлайн-очередь
        locationRepository.flushOfflineQueue()

        // 2. Проверяем, не пропущен ли 30-минутный интервал
        executePendingOrScheduledTracking()

        // 3. Возвращаем актуальное состояние
        return getScheduleState()
    }
}