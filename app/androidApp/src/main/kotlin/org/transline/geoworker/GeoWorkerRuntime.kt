package org.transline.geoworker

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.transline.geoworker.tracker.DefaultLocationRepository
import org.transline.geoworker.tracker.EncryptedPrefsSecureConfigStore
import org.transline.geoworker.tracker.LocationTrackerController
import org.transline.geoworker.tracker.SharedPreferencesTrackingStorage

/**
 * Process-wide single [LocationTrackerController] + FusedLocation provider.
 *
 * [LocationTrackerModule] and [LocationForegroundService] must share this instance —
 * otherwise each owns a GPS stream and a separate `isRequestInProgress` lock → duplicate POSTs.
 */
object GeoWorkerRuntime {
    private val lock = Any()

    @Volatile
    private var controller: LocationTrackerController? = null

    fun controller(context: Context): LocationTrackerController {
        synchronized(lock) {
            controller?.let { return it }
            val app = context.applicationContext
            val storage = SharedPreferencesTrackingStorage(app)
            val secureStore = EncryptedPrefsSecureConfigStore(app)
            val provider = AndroidLocationProvider(app)
            val httpClient = HttpClient(OkHttp)
            val networkChecker = AndroidNetworkChecker(app)
            val repository = DefaultLocationRepository(httpClient, storage, networkChecker)
            val created = LocationTrackerController(
                provider,
                repository,
                storage,
                secureStore,
                httpClient,
            )
            controller = created
            return created
        }
    }

    fun storage(context: Context): SharedPreferencesTrackingStorage =
        SharedPreferencesTrackingStorage(context.applicationContext)

    /** Test / process teardown only. */
    fun resetForTests() {
        synchronized(lock) {
            try {
                controller?.stopLocationService()
            } catch (_: Exception) {
            }
            controller = null
        }
    }
}
