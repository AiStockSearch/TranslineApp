package org.transline.geoworker.tracker

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingTrackingListener : TrackingListener {
    data class HttpEvent(
        val ok: Boolean,
        val method: String,
        val url: String,
        val status: Int?,
        val message: String,
    )

    data class SecureEvent(val type: String, val message: String?)

    val httpEvents = mutableListOf<HttpEvent>()
    val secureEvents = mutableListOf<SecureEvent>()

    override fun onLocationSent(latitude: Double, longitude: Double, timestamp: Long) = Unit
    override fun onLocationFailed(message: String) = Unit
    override fun onLocationServicesDisabled() = Unit

    override fun onHttpResult(ok: Boolean, method: String, url: String, status: Int?, message: String) {
        httpEvents += HttpEvent(ok, method, url, status, message)
    }

    override fun onSecureConfigEvent(type: String, message: String?) {
        secureEvents += SecureEvent(type, message)
    }
}

class SecureConfigHttpProbeTest {

    private fun controller(
        storage: TrackingStorage = InMemoryTrackingStorage(),
        secureStore: SecureConfigStore = InMemorySecureConfigStore(),
    ): Pair<LocationTrackerController, RecordingTrackingListener> {
        val listener = RecordingTrackingListener()
        val c = LocationTrackerController(
            FakeLocationProvider(),
            FakeLocationRepository(),
            storage,
            secureStore,
        )
        c.addListener(listener)
        return c to listener
    }

    @Test
    fun buildProbeHeaders_bearerWinsOverCustomAuthorization() {
        val headers = HttpProbe.buildProbeHeaders(
            customHeaders = mapOf(
                "X-Custom" to "1",
                "Authorization" to "Basic should-lose",
            ),
            accessToken = "access-xyz",
        )
        assertEquals("Bearer access-xyz", headers["Authorization"])
        assertEquals("1", headers["X-Custom"])
    }

    @Test
    fun redactUrl_stripsSensitiveQueryKeysAndTruncates() {
        val redacted = HttpProbe.redactUrl(
            "https://api.example.com/path?foo=1&access_token=SECRET&refresh_token=R&token=T&ok=yes"
        )
        assertFalse(redacted.contains("SECRET"))
        assertFalse(redacted.contains("access_token"))
        assertFalse(redacted.contains("refresh_token"))
        assertTrue(redacted.contains("foo=1"))
        assertTrue(redacted.contains("ok=yes"))

        val longUrl = "https://example.com/" + "a".repeat(600)
        val truncated = HttpProbe.redactUrl(longUrl)
        assertTrue(truncated.length <= HttpProbe.MAX_URL_CHARS + 1)
        assertTrue(truncated.endsWith("…"))
    }

    @Test
    fun saveSecureConfig_persistsBlobAndEmitsKeychainSaved_notAuthHeader() {
        val storage = InMemoryTrackingStorage()
        storage.setAuthHeader("Basic keep-me")
        val secure = InMemorySecureConfigStore()
        val (c, listener) = controller(storage, secure)

        c.saveSecureConfig(
            access = "acc",
            refresh = "ref",
            endpointUrl = "https://ep.example",
            customHeaders = mapOf("X-H" to "v"),
        )

        assertEquals("acc", secure.load()?.access)
        assertEquals("ref", secure.load()?.refresh)
        assertEquals("https://ep.example", secure.load()?.endpointUrl)
        assertEquals("v", secure.load()?.customHeaders?.get("X-H"))
        assertEquals("Basic keep-me", storage.getAuthHeader())
        assertTrue(listener.secureEvents.any { it.type == SecureConfigEventType.KEYCHAIN_SAVED })
    }

    @Test
    fun clearSecrets_clearsOnlySecureStore() {
        val storage = InMemoryTrackingStorage()
        storage.setApiEndpoint("https://coords")
        storage.setAuthHeader("Basic abc")
        val secure = InMemorySecureConfigStore()
        secure.save(SecureConfig(access = "a", refresh = "r"))
        val (c, listener) = controller(storage, secure)

        c.clearSecrets()

        assertNull(secure.load())
        assertEquals("https://coords", storage.getApiEndpoint())
        assertEquals("Basic abc", storage.getAuthHeader())
        assertTrue(listener.secureEvents.any { it.type == SecureConfigEventType.KEYCHAIN_CLEARED })
    }

    @Test
    fun completeTrip_keepsRegistration_clearsTripSchedule() = runBlocking {
        val storage = InMemoryTrackingStorage()
        storage.setApiEndpoint("https://coords.example/api/coordinates")
        storage.setDriverUuid("driver-1")
        storage.setAuthHeader("Basic abc")
        storage.setOrderNumber("ORD-1")
        val secure = InMemorySecureConfigStore()
        secure.save(SecureConfig(access = "keep-access", refresh = "keep-refresh"))
        val (c, _) = controller(storage, secure)

        c.startTrip(1_000_000L)
        assertTrue(storage.isRegistrationLocked())
        c.completeTripAfterModeration()

        assertEquals("https://coords.example/api/coordinates", storage.getApiEndpoint())
        assertEquals("driver-1", storage.getDriverUuid())
        assertEquals("Basic abc", storage.getAuthHeader())
        assertFalse(storage.isTrackingActive())
        assertFalse(storage.isRegistrationLocked())
        assertNull(storage.getNextScheduledTimestamp())
        assertEquals("", storage.getOrderNumber())
        assertNotNull(secure.load())
        assertEquals("keep-access", secure.load()?.access)
    }

    @Test
    fun saveLocationConfiguration_whenLocked_rejectsEndpointOverwrite() {
        val storage = InMemoryTrackingStorage()
        storage.setApiEndpoint("https://a.example/api/coordinates")
        storage.setDriverUuid("u1")
        storage.setRegistrationLocked(true)
        val (c, _) = controller(storage)

        val ok = c.saveLocationConfiguration(
            apiEndpoint = "https://evil.example",
            driverUuid = "hijack",
            orderNumber = "ORD-2",
        )

        assertFalse(ok)
        assertEquals("https://a.example/api/coordinates", storage.getApiEndpoint())
        assertEquals("u1", storage.getDriverUuid())
        assertEquals("ORD-2", storage.getOrderNumber())
    }

    @Test
    fun httpProbe_rejectsDisallowedMethod() = runBlocking {
        val secure = InMemorySecureConfigStore()
        secure.save(SecureConfig(access = "a", refresh = "r"))
        val (c, listener) = controller(secureStore = secure)

        c.httpProbe("https://example.com/x", "DELETE", null)

        assertEquals(1, listener.httpEvents.size)
        val ev = listener.httpEvents.first()
        assertFalse(ev.ok)
        assertEquals("DELETE", ev.method)
        assertTrue(ev.message.contains("method", ignoreCase = true) || ev.message.contains("not allowed", ignoreCase = true))
    }

    @Test
    fun httpProbe_missingAccess_emitsAuthMissingPath() = runBlocking {
        val (c, listener) = controller()

        c.httpProbe("https://example.com/x", "GET", null)

        assertTrue(listener.secureEvents.any { it.type == SecureConfigEventType.AUTH_MISSING })
        assertEquals(1, listener.httpEvents.size)
        assertFalse(listener.httpEvents.first().ok)
    }

    @Test
    fun httpProbe_rejectsBodyOver256KbWithoutNetwork() = runBlocking {
        val secure = InMemorySecureConfigStore()
        secure.save(SecureConfig(access = "a", refresh = "r"))
        val (c, listener) = controller(secureStore = secure)
        val huge = "x".repeat(HttpProbe.MAX_BODY_BYTES + 1)

        c.httpProbe("https://example.com/x", "POST", huge)

        assertEquals(1, listener.httpEvents.size)
        val ev = listener.httpEvents.first()
        assertFalse(ev.ok)
        assertTrue(ev.message.contains("large", ignoreCase = true) || ev.message.contains("256", ignoreCase = true))
        // No status from network
        assertNull(ev.status)
    }

    @Test
    fun isAllowedMethod_allowlist() {
        assertTrue(HttpProbe.isAllowedMethod("get"))
        assertTrue(HttpProbe.isAllowedMethod("POST"))
        assertTrue(HttpProbe.isAllowedMethod("PATCH"))
        assertTrue(HttpProbe.isAllowedMethod("PUT"))
        assertFalse(HttpProbe.isAllowedMethod("DELETE"))
        assertFalse(HttpProbe.isAllowedMethod("HEAD"))
    }
}
