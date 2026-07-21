package org.transline.geoworker.tracker

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D-14: registration soft-lock — identical no-op, stop≠unlock, interval reject, orderNumber, WithAuth.
 */
class RegistrationLockTest {

    private fun controller(
        storage: TrackingStorage = InMemoryTrackingStorage(),
        provider: FakeLocationProvider = FakeLocationProvider(),
    ): LocationTrackerController {
        return LocationTrackerController(
            provider,
            FakeLocationRepository(),
            storage,
            InMemorySecureConfigStore(),
        )
    }

    private fun lockedStorage(
        endpoint: String = "https://a.example/api/coordinates",
        uuid: String = "driver-1",
        auth: String = "Basic abc",
        interval: Int = 30,
        order: String = "ORD-1",
    ): InMemoryTrackingStorage {
        val storage = InMemoryTrackingStorage()
        storage.setApiEndpoint(endpoint)
        storage.setDriverUuid(uuid)
        storage.setAuthHeader(auth)
        storage.setUpdateIntervalMinutes(interval)
        storage.setOrderNumber(order)
        storage.setRegistrationLocked(true)
        storage.setTrackingActive(true)
        return storage
    }

    @Test
    fun save_whenLocked_identicalRewrite_returnsTrue_andKeepsFields() {
        val storage = lockedStorage()
        val c = controller(storage)

        val ok = c.saveLocationConfiguration(
            apiEndpoint = "https://a.example",
            driverUuid = "driver-1",
            orderNumber = "",
            updateIntervalMinutes = 30,
            authHeader = "Basic abc",
        )

        assertTrue(ok)
        assertEquals("https://a.example/api/coordinates", storage.getApiEndpoint())
        assertEquals("driver-1", storage.getDriverUuid())
        assertEquals("Basic abc", storage.getAuthHeader())
        assertEquals(30, storage.getUpdateIntervalMinutes())
        assertTrue(storage.isRegistrationLocked())
        assertTrue(c.isRegistrationLocked())
    }

    @Test
    fun save_whenLocked_nullAuthAndInterval_countsAsIdentical() {
        val storage = lockedStorage()
        val c = controller(storage)

        val ok = c.saveLocationConfiguration(
            apiEndpoint = "https://a.example/api/coordinates",
            driverUuid = "driver-1",
            authHeader = null,
            updateIntervalMinutes = null,
        )

        assertTrue(ok)
        assertEquals("Basic abc", storage.getAuthHeader())
        assertEquals(30, storage.getUpdateIntervalMinutes())
    }

    @Test
    fun stopLocationService_whenLocked_doesNotUnlock() {
        val storage = lockedStorage()
        val provider = FakeLocationProvider()
        val c = controller(storage, provider)

        c.stopLocationService()

        assertFalse(storage.isTrackingActive())
        assertTrue(storage.isRegistrationLocked())
        assertTrue(c.isRegistrationLocked())
        assertEquals(1, provider.stopCalls)
    }

    @Test
    fun save_whenLocked_differentPositiveInterval_returnsFalse_keepsInterval() {
        val storage = lockedStorage(interval = 30)
        val c = controller(storage)

        val ok = c.saveLocationConfiguration(
            apiEndpoint = "https://a.example/api/coordinates",
            driverUuid = "driver-1",
            updateIntervalMinutes = 1,
            authHeader = "Basic abc",
        )

        assertFalse(ok)
        assertEquals(30, storage.getUpdateIntervalMinutes())
        assertTrue(storage.isRegistrationLocked())
    }

    @Test
    fun save_whenLocked_nonEmptyOrderNumber_applies_evenOnOverwriteReject() {
        val storage = lockedStorage(order = "ORD-1")
        val c = controller(storage)

        val ok = c.saveLocationConfiguration(
            apiEndpoint = "https://evil.example",
            driverUuid = "hijack",
            orderNumber = "ORD-2",
        )

        assertFalse(ok)
        assertEquals("ORD-2", storage.getOrderNumber())
        assertEquals("https://a.example/api/coordinates", storage.getApiEndpoint())
        assertEquals("driver-1", storage.getDriverUuid())
    }

    @Test
    fun save_whenLocked_authOverwrite_returnsFalse() {
        val storage = lockedStorage()
        val c = controller(storage)

        // WithAuth path is the same controller method with non-empty authHeader
        val ok = c.saveLocationConfiguration(
            apiEndpoint = "https://a.example/api/coordinates",
            driverUuid = "driver-1",
            updateIntervalMinutes = 30,
            authHeader = "Bearer hijack-token",
        )

        assertFalse(ok)
        assertEquals("Basic abc", storage.getAuthHeader())
    }

    @Test
    fun clearTripState_unlocksRegistration() {
        val storage = lockedStorage()
        val c = controller(storage)

        c.clearTripState()

        assertFalse(storage.isRegistrationLocked())
        assertFalse(c.isRegistrationLocked())
        assertEquals("https://a.example/api/coordinates", storage.getApiEndpoint())
        assertEquals("driver-1", storage.getDriverUuid())
    }

    @Test
    fun completeTripAfterModeration_unlocksRegistration() = runBlocking {
        val storage = lockedStorage()
        val c = controller(storage)

        c.completeTripAfterModeration()

        assertFalse(storage.isRegistrationLocked())
        assertFalse(storage.isTrackingActive())
    }

    @Test
    fun startLocationService_whenLocked_missingRegistration_returnsFalse_doesNotStartGps() {
        val storage = InMemoryTrackingStorage()
        storage.setRegistrationLocked(true)
        // no endpoint / uuid
        val provider = FakeLocationProvider()
        val c = controller(storage, provider)

        val ok = c.startLocationService(
            apiEndpoint = "https://evil.example",
            driverUuid = "hijack",
        )

        assertFalse(ok)
        assertFalse(storage.isTrackingActive())
        assertEquals(0, provider.startCalls)
        assertNullEndpointAndUuid(storage)
    }

    @Test
    fun startLocationService_whenLocked_identical_resumesGpsWithStoredRegistration() {
        val storage = lockedStorage()
        val provider = FakeLocationProvider()
        val c = controller(storage, provider)

        val ok = c.startLocationService(
            apiEndpoint = "https://a.example",
            driverUuid = "driver-1",
            updateIntervalMinutes = 30,
            authHeader = "Basic abc",
        )

        assertTrue(ok)
        assertTrue(storage.isTrackingActive())
        assertEquals(1, provider.startCalls)
        assertEquals("https://a.example/api/coordinates", storage.getApiEndpoint())
        assertTrue(storage.isRegistrationLocked())
    }

    private fun assertNullEndpointAndUuid(storage: TrackingStorage) {
        assertTrue(storage.getApiEndpoint().isNullOrEmpty())
        assertTrue(storage.getDriverUuid().isNullOrEmpty())
    }
}
