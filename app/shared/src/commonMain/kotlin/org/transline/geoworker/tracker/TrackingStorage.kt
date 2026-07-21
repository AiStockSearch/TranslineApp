package org.transline.geoworker.tracker

interface TrackingStorage {
    fun getLastSentTimestamp(): Long?
    fun setLastSentTimestamp(time: Long)
    fun clearLastSentTimestamp()

    fun getNextScheduledTimestamp(): Long?
    fun setNextScheduledTimestamp(time: Long)

    fun isTrackingActive(): Boolean
    fun setTrackingActive(active: Boolean)

    // Конфигурация бэкенда и водителя
    fun getApiEndpoint(): String?
    fun setApiEndpoint(endpoint: String)

    fun getDriverUuid(): String?
    fun setDriverUuid(uuid: String)

    fun getAuthHeader(): String?
    fun setAuthHeader(header: String)

    fun getUpdateIntervalMinutes(): Int
    fun setUpdateIntervalMinutes(minutes: Int)

    fun getOrderNumber(): String?
    fun setOrderNumber(value: String)

    /** Soft registration lock (LOCK-01): block endpoint/uuid/auth overwrite while trip active. */
    fun isRegistrationLocked(): Boolean
    fun setRegistrationLocked(locked: Boolean)

    fun clearNextScheduledTimestamp()

    // Работа с офлайн-очередью точек (в формате JSON-строки)
    fun getOfflineQueueJson(): String?
    fun setOfflineQueueJson(json: String?)

    fun getString(key: String): String?
    fun putString(key: String, value: String)

    /** Full wipe including registration (endpoint/uuid/auth). Prefer [clearTripState] for trip end. */
    fun clear()
}