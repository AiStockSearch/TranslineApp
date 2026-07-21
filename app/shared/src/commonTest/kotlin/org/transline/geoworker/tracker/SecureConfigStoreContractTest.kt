package org.transline.geoworker.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RED/contract tests for SecureConfigStore round-trip and isolation from TrackingStorage.
 */
class SecureConfigStoreContractTest {

    @Test
    fun inMemory_saveLoadClear_roundTripsFullBlob() {
        val store = InMemorySecureConfigStore()
        assertNull(store.load())

        val config = SecureConfig(
            access = "access-token",
            refresh = "refresh-token",
            endpointUrl = "https://api.example.com/probe",
            customHeaders = mapOf("X-Custom" to "v1", "Authorization" to "should-be-overwritten-later"),
        )
        store.save(config)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals("access-token", loaded.access)
        assertEquals("refresh-token", loaded.refresh)
        assertEquals("https://api.example.com/probe", loaded.endpointUrl)
        assertEquals("v1", loaded.customHeaders["X-Custom"])
        assertEquals("should-be-overwritten-later", loaded.customHeaders["Authorization"])

        store.clear()
        assertNull(store.load())
    }

    @Test
    fun secureStore_isSeparateFromTrackingStorage_noAuthHeaderWrite() {
        val tracking = InMemoryTrackingStorage()
        val secure = InMemorySecureConfigStore()

        secure.save(
            SecureConfig(
                access = "Bearer-secret",
                refresh = "refresh-secret",
                endpointUrl = "https://secure.example/ep",
            )
        )
        tracking.setAuthHeader("Basic abc")

        assertEquals("Basic abc", tracking.getAuthHeader())
        assertEquals("Bearer-secret", secure.load()?.access)
        assertTrue(tracking.getString("secure_config") == null)
        assertNull(tracking.getApiEndpoint()) // tokens must not land in tracking prefs keys
    }

    @Test
    fun secureConfigJson_roundTrips() {
        val config = SecureConfig(
            access = "a",
            refresh = "r",
            endpointUrl = null,
            customHeaders = mapOf("H" to "1"),
        )
        val encoded = SecureConfigJson.encode(config)
        val decoded = SecureConfigJson.decode(encoded)
        assertEquals(config, decoded)
    }
}
