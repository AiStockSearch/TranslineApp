package org.transline.geoworker.tracker

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationPayloadSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun encode_usesSpeedMpsSnakeCase_forGpsService() {
        val encoded = json.encodeToString(
            LocationPayload(
                latitude = 55.75,
                longitude = 37.61,
                speedMps = 12.5,
                driver_uuid = "u1",
            )
        )
        assertTrue(encoded.contains("\"speed_mps\""))
        assertTrue(!encoded.contains("\"speedMps\""))
    }

    @Test
    fun decode_acceptsLegacyCamelCaseSpeedMps() {
        val decoded = json.decodeFromString<LocationPayload>(
            """{"latitude":1.0,"longitude":2.0,"speedMps":3.0,"driver_uuid":"u1"}"""
        )
        assertEquals(3.0, decoded.speedMps)
        assertEquals("u1", decoded.driver_uuid)
    }

    @Test
    fun decode_acceptsSnakeCaseSpeedMps() {
        val decoded = json.decodeFromString<LocationPayload>(
            """{"latitude":1.0,"longitude":2.0,"speed_mps":4.0,"driver_uuid":"u1"}"""
        )
        assertEquals(4.0, decoded.speedMps)
    }
}
