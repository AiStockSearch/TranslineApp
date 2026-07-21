package org.transline.geoworker.tracker

import platform.Foundation.NSUserDefaults

class NSUserDefaultsTrackingStorage : TrackingStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getLastSentTimestamp(): Long? {
        val time = defaults.doubleForKey("last_sent").toLong()
        return if (time == 0L && defaults.objectForKey("last_sent") == null) null else time
    }

    override fun setLastSentTimestamp(time: Long) {
        defaults.setDouble(time.toDouble(), "last_sent")
    }

    override fun getNextScheduledTimestamp(): Long? {
        val time = defaults.doubleForKey("next_scheduled").toLong()
        return if (time == 0L && defaults.objectForKey("next_scheduled") == null) null else time
    }

    override fun setNextScheduledTimestamp(time: Long) {
        defaults.setDouble(time.toDouble(), "next_scheduled")
    }

    override fun isTrackingActive(): Boolean {
        return defaults.boolForKey("tracking_active")
    }

    override fun setTrackingActive(active: Boolean) {
        defaults.setBool(active, forKey = "is_active")
    }

    override fun getString(key: String): String? {
        return defaults.stringForKey(key)
    }

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun clear() {
        defaults.removeObjectForKey("last_sent")
        defaults.removeObjectForKey("next_scheduled")
        defaults.removeObjectForKey("tracking_active")
    }
}
