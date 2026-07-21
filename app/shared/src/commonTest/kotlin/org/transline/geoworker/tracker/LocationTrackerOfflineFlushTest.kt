package org.transline.geoworker.tracker

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Recording double for D-38.2: proves [LocationTrackerController.initializeAndSyncOnAppStart]
 * invokes flush and drains offline_queue storage — unlike [FakeLocationRepository] no-op.
 * Clears without HTTP (Pitfall 4); production [DefaultLocationRepository] still needs network.
 */
class RecordingFlushRepository(
    private val storage: TrackingStorage,
) : LocationRepository {
    var flushCalls: Int = 0
        private set

    override suspend fun sendOrQueueLocation(location: Location): Boolean = true

    override suspend fun flushOfflineQueue() {
        flushCalls++
        storage.setOfflineQueueJson(null)
    }
}

class LocationTrackerOfflineFlushTest {

    @Test
    fun initializeAndSync_flushesOfflineQueue() = runBlocking {
        val storage = InMemoryTrackingStorage()
        storage.setApiEndpoint("https://host.example/api/coordinates")
        storage.setDriverUuid("u1")
        storage.setOfflineQueueJson(
            """[{"latitude":1.0,"longitude":2.0,"speedMps":0.0,"driver_uuid":"u1"}]""",
        )
        val repo = RecordingFlushRepository(storage)
        val controller = LocationTrackerController(
            FakeLocationProvider(),
            repo,
            storage,
            InMemorySecureConfigStore(),
        )

        controller.initializeAndSyncOnAppStart()

        assertEquals(1, repo.flushCalls)
        assertNull(storage.getOfflineQueueJson())
    }
}
