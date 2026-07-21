package org.transline.geoworker.tracker

interface TrackingStorage {
    fun getLastSentTimestamp(): Long?
    fun setLastSentTimestamp(time: Long)
    fun getNextScheduledTimestamp(): Long?
    fun setNextScheduledTimestamp(time: Long)
    fun isTrackingActive(): Boolean
    fun setTrackingActive(active: Boolean)
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun clear()
}
