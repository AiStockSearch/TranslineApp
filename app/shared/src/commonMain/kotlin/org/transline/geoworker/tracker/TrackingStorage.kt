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

    /** Native notify i18n bundle JSON ([NotifyI18nBundle]); blank/missing = none. */
    fun getNotifyI18nBundleJson(): String? = getString(NotifyI18nStorageKeys.BUNDLE_JSON)

    fun setNotifyI18nBundleJson(json: String) {
        putString(NotifyI18nStorageKeys.BUNDLE_JSON, json)
    }

    fun clearNotifyI18nBundle() {
        putString(NotifyI18nStorageKeys.BUNDLE_JSON, "")
    }

    /** Trip notify session JSON ([TripNotifySession]); blank/missing = none. */
    fun getTripNotifySessionJson(): String? = getString(TripNotifySessionStorageKeys.SESSION_JSON)

    fun setTripNotifySessionJson(json: String) {
        putString(TripNotifySessionStorageKeys.SESSION_JSON, json)
    }

    fun clearTripNotifySession() {
        putString(TripNotifySessionStorageKeys.SESSION_JSON, "")
    }

    /** Full wipe including registration (endpoint/uuid/auth). Prefer [clearTripState] for trip end. */
    fun clear()
}