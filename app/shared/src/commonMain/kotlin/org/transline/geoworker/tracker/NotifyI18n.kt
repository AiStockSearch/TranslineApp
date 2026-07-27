package org.transline.geoworker.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Native i18n snapshot for product Notify (JS pushes any Record; no key filter).
 * Replace-on-sync: [TrackingStorage.setNotifyI18nBundleJson] overwrites the whole map.
 */
@Serializable
data class NotifyI18nBundle(
    val locale: String,
    val updatedAtEpochMs: Long,
    val strings: Map<String, String> = emptyMap(),
)

object NotifyI18nStorageKeys {
    const val BUNDLE_JSON = "notify_i18n_bundle_json"
}

private val notifyI18nJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Simple `{{name}}` replace; unknown placeholders left intact. */
fun formatNotifyTemplate(template: String, params: Map<String, String>): String {
    var out = template
    for ((k, v) in params) {
        out = out.replace("{{$k}}", v)
    }
    return out
}

fun encodeNotifyI18nBundle(bundle: NotifyI18nBundle): String =
    notifyI18nJson.encodeToString(bundle)

fun decodeNotifyI18nBundle(raw: String?): NotifyI18nBundle? {
    if (raw.isNullOrBlank()) return null
    return runCatching { notifyI18nJson.decodeFromString<NotifyI18nBundle>(raw) }.getOrNull()
}
