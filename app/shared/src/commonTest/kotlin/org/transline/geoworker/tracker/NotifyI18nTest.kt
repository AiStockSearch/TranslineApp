package org.transline.geoworker.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NotifyI18nTest {
    @Test
    fun format_replacesKnownPlaceholders() {
        val out =
            formatNotifyTemplate(
                "Coords {{lat}}, {{lon}} @ {{address}}",
                mapOf("lat" to "1.5", "lon" to "2.5", "address" to "A"),
            )
        assertEquals("Coords 1.5, 2.5 @ A", out)
    }

    @Test
    fun format_leavesUnknownPlaceholders() {
        val out = formatNotifyTemplate("Hi {{name}}", emptyMap())
        assertEquals("Hi {{name}}", out)
    }

    @Test
    fun bundle_json_roundTrip_preservesArbitraryKeys() {
        val json =
            encodeNotifyI18nBundle(
                NotifyI18nBundle(
                    locale = "ru",
                    updatedAtEpochMs = 1L,
                    strings =
                        mapOf(
                            "custom.foo" to "Bar {{x}}",
                            "geo_notify_coords_sent_body" to "{{lat}}",
                        ),
                ),
            )
        val parsed = decodeNotifyI18nBundle(json)
        assertNotNull(parsed)
        assertEquals("ru", parsed.locale)
        assertEquals("Bar {{x}}", parsed.strings["custom.foo"])
        assertEquals("{{lat}}", parsed.strings["geo_notify_coords_sent_body"])
    }

    @Test
    fun storage_default_helpers_replace_bundle() {
        val storage = InMemoryTrackingStorage()
        storage.setNotifyI18nBundleJson(
            encodeNotifyI18nBundle(
                NotifyI18nBundle(locale = "en", updatedAtEpochMs = 2L, strings = mapOf("a" to "1")),
            ),
        )
        assertEquals("en", decodeNotifyI18nBundle(storage.getNotifyI18nBundleJson())!!.locale)
        storage.setNotifyI18nBundleJson(
            encodeNotifyI18nBundle(
                NotifyI18nBundle(locale = "kz", updatedAtEpochMs = 3L, strings = mapOf("b" to "2")),
            ),
        )
        val second = decodeNotifyI18nBundle(storage.getNotifyI18nBundleJson())!!
        assertEquals("kz", second.locale)
        assertEquals(null, second.strings["a"])
        assertEquals("2", second.strings["b"])
        storage.clearNotifyI18nBundle()
        assertEquals(null, decodeNotifyI18nBundle(storage.getNotifyI18nBundleJson()))
    }
}
