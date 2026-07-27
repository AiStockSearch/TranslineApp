package org.transline.geoworker.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductNotifyResolverTest {
    private fun sampleBundle() =
        NotifyI18nBundle(
            locale = "ru",
            updatedAtEpochMs = 1L,
            strings =
                mapOf(
                    "geo_notify_coords_sent_title" to "Sent",
                    "geo_notify_coords_sent_body" to "{{lat}}, {{lon}} — {{address}}",
                ),
        )

    private fun sampleSession(orderId: String = "o1", address: String = "Warehouse") =
        TripNotifySession(
            orderId = orderId,
            notifyKeys =
                mapOf(
                    "coordsSentTitle" to "geo_notify_coords_sent_title",
                    "coordsSentBody" to "geo_notify_coords_sent_body",
                ),
            points = listOf(TripNotifyPoint(type = "loading", address = address)),
        )

    @Test
    fun resolver_null_when_no_session() {
        assertNull(
            resolveProductCoordsNotify(
                bundle = sampleBundle(),
                session = null,
                lat = 1.0,
                lon = 2.0,
            ),
        )
    }

    @Test
    fun resolver_builds_map_deep_link_and_body() {
        val payload =
            resolveProductCoordsNotify(
                bundle = sampleBundle(),
                session = sampleSession(orderId = "o1", address = "Warehouse"),
                lat = 55.1,
                lon = 37.2,
            )
        assertNotNull(payload)
        assertEquals("Sent", payload.title)
        assertEquals("55.1, 37.2 — Warehouse", payload.body)
        assertTrue(payload.deepLink.startsWith("app://orders/o1/map"))
        assertTrue(payload.deepLink.contains("lat=55.1"))
        assertTrue(payload.deepLink.contains("lon=37.2"))
    }

    @Test
    fun resolver_null_when_template_key_missing_in_bundle() {
        assertNull(
            resolveProductCoordsNotify(
                bundle = NotifyI18nBundle(locale = "ru", updatedAtEpochMs = 1L, strings = emptyMap()),
                session = sampleSession(),
                lat = 1.0,
                lon = 2.0,
            ),
        )
    }
}
