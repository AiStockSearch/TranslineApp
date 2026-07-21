package org.transline.geoworker.tracker

/**
 * Нормализует host до полного URL `…/api/coordinates`
 * (как getCoordinatesEndpoint в старом LocationTracker.ts).
 */
fun normalizeCoordinatesEndpoint(host: String): String {
    val trimmed = host.trim()
    if (trimmed.isEmpty()) return trimmed

    val withoutTrailingSlash = trimmed.trimEnd('/')
    // Idempotent: strip existing coordinates path or bare /api before re-appending
    val withoutCoordinates = withoutTrailingSlash
        .removeSuffix("/api/coordinates")
        .trimEnd('/')
    val withoutApiSuffix = withoutCoordinates
        .removeSuffix("/api")
        .trimEnd('/')

    return "$withoutApiSuffix/api/coordinates"
}

const val DEFAULT_UPDATE_INTERVAL_MINUTES = 1
const val FAILURE_BACKOFF_EXTRA_MS = 30_000L

/** Max offline queue entries (oldest dropped). SharedPreferences / UserDefaults bound. */
const val MAX_OFFLINE_QUEUE_SIZE = 500

fun isCoordinatesHttpSuccess(status: Int): Boolean = status in 200..299

fun clampSpeedMps(speedMps: Double): Double = maxOf(0.0, speedMps)

/**
 * Build `Authorization: Bearer …` for GpsService / JWT hosts.
 * Idempotent if [accessToken] already starts with `Bearer `.
 */
fun buildBearerAuthHeader(accessToken: String): String? {
    val trimmed = accessToken.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
        "Bearer ${trimmed.substring(7).trim()}"
    } else {
        "Bearer $trimmed"
    }
}

/**
 * Trim queue to [maxSize] after append (drop oldest). Exposed for unit tests.
 */
fun <T> appendBounded(queue: MutableList<T>, item: T, maxSize: Int = MAX_OFFLINE_QUEUE_SIZE): MutableList<T> {
    queue.add(item)
    while (queue.size > maxSize) {
        queue.removeAt(0)
    }
    return queue
}

/**
 * Temporary default Basic for `/api/coordinates` (D-07 dual auth).
 *
 * **Security debt:** credential is shipped in the client artifact. Prefer
 * [LocationTrackerController.saveLocationConfiguration] with explicit `authHeader`
 * / RN `saveLocationConfigurationWithAuth`, then rotate server-side.
 * Do not treat this as a long-term secret store.
 */
@Deprecated("Inject auth via WithAuth / authHeader; rotate server credentials")
const val DEFAULT_COORDINATES_BASIC_AUTH =
    "Basic dHJhbnNsaW5lX3VzZXI6VHJhbjMkU2wxMkBuZUA="
