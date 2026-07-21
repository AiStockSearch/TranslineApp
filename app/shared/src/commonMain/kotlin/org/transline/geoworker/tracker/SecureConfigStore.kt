package org.transline.geoworker.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Full secure blob (D-01): access + refresh + optional endpoint + optional custom headers.
 * Stored only via [SecureConfigStore] — never in [TrackingStorage].
 */
@Serializable
data class SecureConfig(
    val access: String,
    val refresh: String,
    val endpointUrl: String? = null,
    val customHeaders: Map<String, String> = emptyMap(),
)

interface SecureConfigStore {
    fun load(): SecureConfig?
    fun save(config: SecureConfig)
    fun clear()
}

internal object SecureConfigJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(config: SecureConfig): String = json.encodeToString(config)

    fun decode(raw: String): SecureConfig? = try {
        json.decodeFromString(raw)
    } catch (_: Exception) {
        null
    }
}
