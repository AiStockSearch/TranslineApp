package org.transline.geoworker.tracker

interface TrackingStorage {
    fun getLastSentTimestamp(): Long?
    fun setLastSentTimestamp(time: Long)

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

    // Работа с офлайн-очередью точек (в формате JSON-строки)
    fun getOfflineQueueJson(): String?
    fun setOfflineQueueJson(json: String?)

    fun getString(key: String): String?
    fun putString(key: String, value: String)

    fun clear()
}