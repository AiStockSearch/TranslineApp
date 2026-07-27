package org.transline.geoworker.tracker

data class ProductNotifyPayload(
    val title: String,
    val body: String,
    val deepLink: String,
)

/**
 * Builds product coords Notify from native i18n bundle + trip session.
 * Returns null when gated off (missing bundle/session/keys/templates).
 */
fun resolveProductCoordsNotify(
    bundle: NotifyI18nBundle?,
    session: TripNotifySession?,
    lat: Double,
    lon: Double,
): ProductNotifyPayload? {
    if (bundle == null || session == null) return null
    if (session.orderId.isBlank()) return null
    val titleKey = session.notifyKeys["coordsSentTitle"] ?: return null
    val bodyKey = session.notifyKeys["coordsSentBody"] ?: return null
    val titleTpl = bundle.strings[titleKey] ?: return null
    val bodyTpl = bundle.strings[bodyKey] ?: return null
    val address =
        session.points.firstOrNull { it.type == "loading" }?.address
            ?: session.points.firstOrNull()?.address
            ?: ""
    val params =
        mapOf(
            "lat" to lat.toString(),
            "lon" to lon.toString(),
            "address" to address,
            "orderId" to session.orderId,
        )
    val deepLink = "app://orders/${session.orderId}/map?lat=$lat&lon=$lon"
    return ProductNotifyPayload(
        title = formatNotifyTemplate(titleTpl, params),
        body = formatNotifyTemplate(bodyTpl, params),
        deepLink = deepLink,
    )
}

fun resolveProductCoordsNotifyFromStorage(
    storage: TrackingStorage,
    lat: Double,
    lon: Double,
): ProductNotifyPayload? =
    resolveProductCoordsNotify(
        bundle = decodeNotifyI18nBundle(storage.getNotifyI18nBundleJson()),
        session = decodeTripNotifySession(storage.getTripNotifySessionJson()),
        lat = lat,
        lon = lon,
    )
