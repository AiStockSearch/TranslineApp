package org.transline.geoworker.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TripNotifySessionTest {
    @Test
    fun session_roundTrip_keepsPointsAndNotifyKeys() {
        val session =
            TripNotifySession(
                orderId = "ord-1",
                driverUuid = "drv",
                locale = "en",
                loadingTimeEpochMs = 2_000L,
                firstTrackingEpochMs = 2_000L - 3_600_000L,
                intervalMinutes = 30,
                points =
                    listOf(
                        TripNotifyPoint(
                            type = "loading",
                            address = "City St 1",
                            dateEpochMs = 2_000L,
                            lat = null,
                            lon = null,
                        ),
                    ),
                notifyKeys =
                    mapOf(
                        "coordsSentTitle" to "geo_notify_coords_sent_title",
                        "coordsSentBody" to "geo_notify_coords_sent_body",
                    ),
            )
        val parsed = decodeTripNotifySession(encodeTripNotifySession(session))
        assertNotNull(parsed)
        assertEquals("ord-1", parsed.orderId)
        assertEquals("City St 1", parsed.points.first().address)
        assertEquals("geo_notify_coords_sent_body", parsed.notifyKeys["coordsSentBody"])
    }

    @Test
    fun storage_clear_session() {
        val storage = InMemoryTrackingStorage()
        storage.setTripNotifySessionJson(
            encodeTripNotifySession(TripNotifySession(orderId = "x")),
        )
        assertNotNull(decodeTripNotifySession(storage.getTripNotifySessionJson()))
        storage.clearTripNotifySession()
        assertEquals(null, decodeTripNotifySession(storage.getTripNotifySessionJson()))
    }
}
