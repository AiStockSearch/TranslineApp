package org.transline.geoworker.tracker

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Baseline for REL-03 / WR-02: KMP [LocationTrackerController.startTrip] intentionally
 * does **not** validate endpoint/uuid — it dumb-mutates `tracking_active=true`.
 *
 * The Android/iOS bridge CONFIG_MISSING guard (validate-before-mutate) is the REL-03 fix.
 * These tests document why that bridge guard is required: calling startTrip with empty
 * config leaves phantom schedule state, and resumeLocationServiceIfActive then fails.
 */
class LocationTrackerStartTripValidationTest {

    private fun controllerWith(storage: TrackingStorage): LocationTrackerController =
        LocationTrackerController(
            FakeLocationProvider(),
            FakeLocationRepository(),
            storage,
            InMemorySecureConfigStore(),
        )

    /** Documents why bridge CONFIG_MISSING guard is required — controller activates without config. */
    @Test
    fun startTrip_withEmptyEndpointAndUuid_setsTrackingActiveTrue() {
        val storage = InMemoryTrackingStorage()
        // Empty config (default InMemoryTrackingStorage has null endpoint/uuid)
        assertTrue(storage.getApiEndpoint().isNullOrEmpty())
        assertTrue(storage.getDriverUuid().isNullOrEmpty())

        val controller = controllerWith(storage)
        controller.startTrip(1_700_000_000_000L)

        // Dumb mutate: tracking becomes active even with empty GEO config
        assertTrue(storage.isTrackingActive())
    }

    /** Baseline for bridge validate-before-mutate: empty-config startTrip dirties prefs. */
    @Test
    fun startTrip_emptyConfig_leavesIsTrackingActiveTrue() {
        val storage = InMemoryTrackingStorage()
        val controller = controllerWith(storage)

        controller.startTrip(1_700_000_000_000L)

        assertTrue(
            storage.isTrackingActive(),
            "Controller does not validate — bridge must reject before startTrip",
        )
    }

    /**
     * Even after an incorrect startTrip with empty config, resume fails —
     * supports DoD that fixed bridge never reaches resume on empty-config path.
     */
    @Test
    fun resumeLocationServiceIfActive_returnsFalse_whenTrackingActiveButEndpointEmpty() {
        val storage = InMemoryTrackingStorage()
        val controller = controllerWith(storage)

        controller.startTrip(1_700_000_000_000L)
        assertTrue(storage.isTrackingActive())
        assertTrue(storage.getApiEndpoint().isNullOrEmpty())

        assertFalse(controller.resumeLocationServiceIfActive())
    }
}
