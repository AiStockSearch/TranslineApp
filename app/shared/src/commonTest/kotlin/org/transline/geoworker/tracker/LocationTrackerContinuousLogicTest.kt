package org.transline.geoworker.tracker

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryTrackingStorage : TrackingStorage {
    private var lastSent: Long? = null
    private var nextScheduled: Long? = null
    private var trackingActive: Boolean = false
    private var apiEndpoint: String? = null
    private var driverUuid: String? = null
    private var authHeader: String? = null
    private var updateIntervalMinutes: Int = DEFAULT_UPDATE_INTERVAL_MINUTES
    private var orderNumber: String? = null
    private var offlineQueue: String? = null
    private val strings = mutableMapOf<String, String>()

    override fun getLastSentTimestamp(): Long? = lastSent
    override fun setLastSentTimestamp(time: Long) {
        lastSent = time
    }

    override fun clearLastSentTimestamp() {
        lastSent = null
    }

    override fun getNextScheduledTimestamp(): Long? = nextScheduled
    override fun setNextScheduledTimestamp(time: Long) {
        nextScheduled = time
    }

    override fun isTrackingActive(): Boolean = trackingActive
    override fun setTrackingActive(active: Boolean) {
        trackingActive = active
    }

    override fun getApiEndpoint(): String? = apiEndpoint
    override fun setApiEndpoint(endpoint: String) {
        apiEndpoint = endpoint
    }

    override fun getDriverUuid(): String? = driverUuid
    override fun setDriverUuid(uuid: String) {
        driverUuid = uuid
    }

    override fun getAuthHeader(): String? = authHeader
    override fun setAuthHeader(header: String) {
        authHeader = header
    }

    override fun getUpdateIntervalMinutes(): Int = updateIntervalMinutes
    override fun setUpdateIntervalMinutes(minutes: Int) {
        updateIntervalMinutes = minutes
    }

    override fun getOrderNumber(): String? = orderNumber
    override fun setOrderNumber(value: String) {
        orderNumber = value
    }

    private var registrationLocked: Boolean = false
    override fun isRegistrationLocked(): Boolean = registrationLocked
    override fun setRegistrationLocked(locked: Boolean) {
        registrationLocked = locked
    }

    override fun clearNextScheduledTimestamp() {
        nextScheduled = null
    }

    override fun getOfflineQueueJson(): String? = offlineQueue
    override fun setOfflineQueueJson(json: String?) {
        offlineQueue = json
    }

    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String) {
        strings[key] = value
    }

    override fun clear() {
        lastSent = null
        nextScheduled = null
        trackingActive = false
        apiEndpoint = null
        driverUuid = null
        authHeader = null
        updateIntervalMinutes = DEFAULT_UPDATE_INTERVAL_MINUTES
        orderNumber = null
        offlineQueue = null
        registrationLocked = false
        strings.clear()
    }
}

class FakeLocationRepository(
    var sendResult: Boolean = true
) : LocationRepository {
    var sendCalls: Int = 0
        private set
    var lastLocation: Location? = null
        private set

    override suspend fun sendOrQueueLocation(location: Location): Boolean {
        sendCalls++
        lastLocation = location
        return sendResult
    }

    // Intentionally no-op for continuous-send tests; drain covered by LocationTrackerOfflineFlushTest.
    override suspend fun flushOfflineQueue() = Unit
}

class FakeLocationProvider : PlatformLocationProvider {
    var location: Location? = null
    var stopCalls: Int = 0
        private set
    var startCalls: Int = 0
        private set
    var lastOnLocation: ((Location) -> Unit)? = null
        private set

    override suspend fun getCurrentLocation(): Location? = location

    override fun startTracking(onLocation: (Location) -> Unit) {
        startCalls++
        lastOnLocation = onLocation
    }

    override fun stopTracking() {
        stopCalls++
        lastOnLocation = null
    }

    fun resetCounters() {
        startCalls = 0
        stopCalls = 0
    }
}

class CoordinatesEndpointTest {

    @Test
    fun normalize_stripsApiSuffixAndAddsCoordinates() {
        assertEquals(
            "https://example.com/api/coordinates",
            normalizeCoordinatesEndpoint("https://example.com/api")
        )
        assertEquals(
            "https://example.com/api/coordinates",
            normalizeCoordinatesEndpoint("https://example.com/api/")
        )
        assertEquals(
            "https://example.com/api/coordinates",
            normalizeCoordinatesEndpoint("https://example.com/")
        )
        assertEquals(
            "https://example.com/api/coordinates",
            normalizeCoordinatesEndpoint("https://example.com")
        )
        assertEquals(
            "https://example.com/api/coordinates",
            normalizeCoordinatesEndpoint("https://example.com/api/coordinates")
        )
    }

    @Test
    fun clampSpeedMps_negativeBecomesZero() {
        assertEquals(0.0, clampSpeedMps(-3.5))
        assertEquals(0.0, clampSpeedMps(0.0))
        assertEquals(12.5, clampSpeedMps(12.5))
    }

    @Test
    fun buildBearerAuthHeader_normalizesToken() {
        assertEquals("Bearer tok", buildBearerAuthHeader("tok"))
        assertEquals("Bearer tok", buildBearerAuthHeader("Bearer tok"))
        assertEquals("Bearer tok", buildBearerAuthHeader("bearer tok"))
        assertEquals(null, buildBearerAuthHeader("  "))
        assertEquals(null, buildBearerAuthHeader(""))
    }
}

class LocationTrackerContinuousLogicTest {

    private fun sampleLocation(ts: Long = 1_000L) = Location(
        latitude = 55.75,
        longitude = 37.61,
        timestampMs = ts,
        speedMps = 5.0
    )

    @Test
    fun saveLocationConfiguration_normalizesEndpointAndStoresInterval() {
        val storage = InMemoryTrackingStorage()
        val controller = LocationTrackerController(
            FakeLocationProvider(),
            FakeLocationRepository(),
            storage,
            InMemorySecureConfigStore(),
        )

        controller.saveLocationConfiguration(
            apiEndpoint = "https://host.example/api",
            driverUuid = "driver-1",
            orderNumber = "ORD-9",
            updateIntervalMinutes = 5,
            authHeader = "Basic abc"
        )

        assertEquals("https://host.example/api/coordinates", storage.getApiEndpoint())
        assertEquals("driver-1", storage.getDriverUuid())
        assertEquals("ORD-9", storage.getOrderNumber())
        assertEquals(5, storage.getUpdateIntervalMinutes())
        assertEquals("Basic abc", storage.getAuthHeader())
    }

    @Test
    fun startAndStopLocationService_toggleActiveAndClearLastSent() {
        val storage = InMemoryTrackingStorage()
        val provider = FakeLocationProvider()
        val controller = LocationTrackerController(provider, FakeLocationRepository(), storage, InMemorySecureConfigStore())

        controller.startLocationService("https://host.example", "uuid-1", updateIntervalMinutes = 2)
        assertTrue(controller.isLocationServiceRunning())
        assertNull(storage.getLastSentTimestamp())

        storage.setLastSentTimestamp(123L)
        controller.stopLocationService()

        assertFalse(controller.isLocationServiceRunning())
        assertNull(storage.getLastSentTimestamp())
        assertEquals(1, provider.stopCalls)
        assertEquals("https://host.example/api/coordinates", storage.getApiEndpoint())
    }

    @Test
    fun resumeLocationServiceIfActive_startsProviderWithoutClearingLastSent() {
        val storage = InMemoryTrackingStorage()
        val provider = FakeLocationProvider()
        val controller = LocationTrackerController(provider, FakeLocationRepository(), storage, InMemorySecureConfigStore())

        controller.startLocationService("https://host.example", "uuid-1", updateIntervalMinutes = 1)
        storage.setLastSentTimestamp(999L)

        // Simulate process restart: new controller with same storage
        val provider2 = FakeLocationProvider()
        val controller2 = LocationTrackerController(provider2, FakeLocationRepository(), storage, InMemorySecureConfigStore())
        assertTrue(controller2.resumeLocationServiceIfActive())
        assertEquals(1, provider2.startCalls)
        assertEquals(999L, storage.getLastSentTimestamp())
    }

    @Test
    fun startLocationService_startsProviderTracking() {
        val storage = InMemoryTrackingStorage()
        val provider = FakeLocationProvider()
        val controller = LocationTrackerController(provider, FakeLocationRepository(), storage, InMemorySecureConfigStore())

        controller.startLocationService("https://host.example", "uuid-1", updateIntervalMinutes = 1)

        assertEquals(1, provider.startCalls)
        assertTrue(controller.isLocationServiceRunning())
        assertEquals(DEFAULT_COORDINATES_BASIC_AUTH, storage.getAuthHeader())
    }

    @Test
    fun throttling_skipsSecondUpdateInsideInterval() = runBlocking {
        val storage = InMemoryTrackingStorage()
        val repository = FakeLocationRepository(sendResult = true)
        val controller = LocationTrackerController(FakeLocationProvider(), repository, storage, InMemorySecureConfigStore())

        controller.startLocationService("https://host.example", "uuid-1", updateIntervalMinutes = 1)
        controller.onLocationUpdate(sampleLocation())
        assertEquals(1, repository.sendCalls)

        controller.onLocationUpdate(sampleLocation(ts = 2_000L))
        assertEquals(1, repository.sendCalls)
    }

    @Test
    fun lock_skipsWhenRequestInProgress() {
        val storage = InMemoryTrackingStorage()
        val controller = LocationTrackerController(
            FakeLocationProvider(),
            FakeLocationRepository(),
            storage,
            InMemorySecureConfigStore(),
        )
        controller.startLocationService("https://host.example", "uuid-1", updateIntervalMinutes = 1)
        controller.setRequestInProgressForTest(true)

        assertFalse(controller.shouldSendLocation())
    }

    @Test
    fun backoff_allowsRetryEarlierThanFullInterval() = runBlocking {
        val storage = InMemoryTrackingStorage()
        val repository = FakeLocationRepository(sendResult = false)
        val controller = LocationTrackerController(FakeLocationProvider(), repository, storage, InMemorySecureConfigStore())

        controller.startLocationService("https://host.example", "uuid-1", updateIntervalMinutes = 1)
        val before = currentTimeMillis()
        controller.onLocationUpdate(sampleLocation())

        val lastSent = storage.getLastSentTimestamp()
        assertTrue(lastSent != null)

        val intervalMs = 60_000L
        // lastSent ≈ now - interval + 30s → через ~30s shouldSend = true
        val retryAt = lastSent + intervalMs
        assertTrue(controller.shouldSendLocation(now = retryAt))
        assertFalse(controller.shouldSendLocation(now = before + 1_000L))
    }

    @Test
    fun startTrip_setsUpdateIntervalMinutesTo30() {
        val storage = InMemoryTrackingStorage()
        // Prior continuous default of 1 must be overwritten by startTrip
        assertEquals(DEFAULT_UPDATE_INTERVAL_MINUTES, storage.getUpdateIntervalMinutes())
        storage.setUpdateIntervalMinutes(1)

        val controller = LocationTrackerController(
            FakeLocationProvider(),
            FakeLocationRepository(),
            storage,
            InMemorySecureConfigStore(),
        )
        val loadingEpoch = 1_700_000_000_000L

        controller.startTrip(loadingEpoch)

        assertTrue(storage.isTrackingActive())
        assertEquals(loadingEpoch - LocationTrackerController.ONE_HOUR_MS, storage.getNextScheduledTimestamp())
        assertEquals(30, storage.getUpdateIntervalMinutes())
    }
}
