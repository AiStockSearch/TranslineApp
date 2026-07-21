package org.transline.geoworker.tracker

import android.content.Context
import android.content.SharedPreferences

class SharedPreferencesTrackingStorage(context: Context) : TrackingStorage {
    private val prefs: SharedPreferences = context.getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)

    override fun getLastSentTimestamp(): Long? {
        val time = prefs.getLong("last_sent", -1L)
        return if (time == -1L) null else time
    }

    override fun setLastSentTimestamp(time: Long) {
        prefs.edit().putLong("last_sent", time).apply()
    }

    override fun getNextScheduledTimestamp(): Long? {
        val time = prefs.getLong("next_scheduled", -1L)
        return if (time == -1L) null else time
    }

    override fun setNextScheduledTimestamp(time: Long) {
        prefs.edit().putLong("next_scheduled", time).apply()
    }

    override fun isTrackingActive(): Boolean {
        return prefs.getBoolean("tracking_active", false)
    }

    override fun setTrackingActive(active: Boolean) {
        prefs.edit().putBoolean("is_active", active).apply()
    }
    
    override fun getString(key: String): String? {
        return prefs.getString(key, null)
    }

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}
