package org.transline.geoworker.tracker

import platform.Foundation.NSDate
import platform.Foundation.NSString
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(Darwin)
}

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

class NSUserDefaultsTrackingStorage : TrackingStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getLastSentTimestamp(): Long? {
        val time = defaults.doubleForKey("last_sent").toLong()
        return if (time == 0L && defaults.objectForKey("last_sent") == null) null else time
    }

    override fun setLastSentTimestamp(time: Long) {
        defaults.setDouble(time.toDouble(), forKey = "last_sent")
    }

    override fun getNextScheduledTimestamp(): Long? {
        val time = defaults.doubleForKey("next_scheduled").toLong()
        return if (time == 0L && defaults.objectForKey("next_scheduled") == null) null else time
    }

    override fun setNextScheduledTimestamp(time: Long) {
        defaults.setDouble(time.toDouble(), forKey = "next_scheduled")
    }

    override fun isTrackingActive(): Boolean = defaults.boolForKey("tracking_active")

    override fun setTrackingActive(active: Boolean) {
        defaults.setBool(active, forKey = "tracking_active")
    }

    override fun getApiEndpoint(): String? = defaults.stringForKey("api_endpoint")

    override fun setApiEndpoint(endpoint: String) {
        defaults.setObject(endpoint as NSString, forKey = "api_endpoint")
    }

    override fun getDriverUuid(): String? = defaults.stringForKey("driver_uuid")

    override fun setDriverUuid(uuid: String) {
        defaults.setObject(uuid as NSString, forKey = "driver_uuid")
    }

    override fun getAuthHeader(): String? = defaults.stringForKey("auth_header")

    override fun setAuthHeader(header: String) {
        defaults.setObject(header as NSString, forKey = "auth_header")
    }

    override fun getOfflineQueueJson(): String? = defaults.stringForKey("offline_queue")

    override fun setOfflineQueueJson(json: String?) {
        if (json == null) {
            defaults.removeObjectForKey("offline_queue")
        } else {
            defaults.setObject(json as NSString, forKey = "offline_queue")
        }
    }

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) {
        defaults.setObject(value as NSString, forKey = key)
    }

    override fun clear() {
        defaults.removeObjectForKey("last_sent")
        defaults.removeObjectForKey("next_scheduled")
        defaults.removeObjectForKey("tracking_active")
        defaults.removeObjectForKey("api_endpoint")
        defaults.removeObjectForKey("driver_uuid")
        defaults.removeObjectForKey("auth_header")
        defaults.removeObjectForKey("offline_queue")
    }
}