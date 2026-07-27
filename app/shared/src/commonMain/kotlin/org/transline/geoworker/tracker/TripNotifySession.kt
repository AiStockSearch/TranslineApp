package org.transline.geoworker.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TripNotifyPoint(
    val type: String,
    val address: String = "",
    val dateEpochMs: Long? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

/**
 * Per-trip metadata for product Notify (points + key names into [NotifyI18nBundle]).
 */
@Serializable
data class TripNotifySession(
    val orderId: String,
    val driverUuid: String = "",
    val locale: String = "",
    val loadingTimeEpochMs: Long = 0L,
    val firstTrackingEpochMs: Long = 0L,
    val intervalMinutes: Int = 30,
    val points: List<TripNotifyPoint> = emptyList(),
    val notifyKeys: Map<String, String> = emptyMap(),
)

object TripNotifySessionStorageKeys {
    const val SESSION_JSON = "trip_notify_session_json"
}

private val tripNotifyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun encodeTripNotifySession(session: TripNotifySession): String =
    tripNotifyJson.encodeToString(session)

fun decodeTripNotifySession(raw: String?): TripNotifySession? {
    if (raw.isNullOrBlank()) return null
    return runCatching { tripNotifyJson.decodeFromString<TripNotifySession>(raw) }.getOrNull()
}
