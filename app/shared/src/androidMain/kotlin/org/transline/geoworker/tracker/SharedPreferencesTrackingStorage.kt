package org.transline.geoworker.tracker

import android.content.Context
import android.content.SharedPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(OkHttp)
}

class SharedPreferencesTrackingStorage(context: Context) : TrackingStorage {
    private val prefs: SharedPreferences = context.getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)

    override fun getLastSentTimestamp(): Long? {
        val time = prefs.getLong("last_sent", -1L)
        return if (time == -1L) null else time
    }

    override fun setLastSentTimestamp(time: Long) {
        prefs.edit().putLong("last_sent", time).apply()
    }

    override fun clearLastSentTimestamp() {
        prefs.edit().remove("last_sent").apply()
    }

    override fun getNextScheduledTimestamp(): Long? {
        val time = prefs.getLong("next_scheduled", -1L)
        return if (time == -1L) null else time
    }

    override fun setNextScheduledTimestamp(time: Long) {
        prefs.edit().putLong("next_scheduled", time).apply()
    }

    override fun isTrackingActive(): Boolean = prefs.getBoolean("tracking_active", false)
    override fun setTrackingActive(active: Boolean) {
        prefs.edit().putBoolean("tracking_active", active).apply()
    }

    override fun getApiEndpoint(): String? = prefs.getString("api_endpoint", null)
    override fun setApiEndpoint(endpoint: String) {
        prefs.edit().putString("api_endpoint", endpoint).apply()
    }

    override fun getDriverUuid(): String? = prefs.getString("driver_uuid", null)
    override fun setDriverUuid(uuid: String) {
        prefs.edit().putString("driver_uuid", uuid).apply()
    }

    override fun getAuthHeader(): String? = prefs.getString("auth_header", null)
    override fun setAuthHeader(header: String) {
        prefs.edit().putString("auth_header", header).apply()
    }

    override fun getUpdateIntervalMinutes(): Int =
        prefs.getInt("update_interval_minutes", DEFAULT_UPDATE_INTERVAL_MINUTES)

    override fun setUpdateIntervalMinutes(minutes: Int) {
        prefs.edit().putInt("update_interval_minutes", minutes).apply()
    }

    override fun getOrderNumber(): String? = prefs.getString("order_number", null)

    override fun setOrderNumber(value: String) {
        prefs.edit().putString("order_number", value).apply()
    }

    override fun isRegistrationLocked(): Boolean = prefs.getBoolean("registration_locked", false)
    override fun setRegistrationLocked(locked: Boolean) {
        prefs.edit().putBoolean("registration_locked", locked).apply()
    }

    override fun clearNextScheduledTimestamp() {
        prefs.edit().remove("next_scheduled").apply()
    }

    override fun getOfflineQueueJson(): String? = prefs.getString("offline_queue", null)
    override fun setOfflineQueueJson(json: String?) {
        prefs.edit().putString("offline_queue", json).apply()
    }

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}