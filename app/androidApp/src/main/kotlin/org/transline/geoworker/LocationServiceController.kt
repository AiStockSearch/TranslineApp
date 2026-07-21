package org.transline.geoworker

import android.content.Context

/**
 * Аналог старого LocationServiceController: старт/стоп по внешнему триггеру (FCM, boot, RN).
 * GPS поднимает только FGS через [GeoWorkerRuntime] — без второго controller.
 */
object LocationServiceController {
    fun startLocationService(context: Context) {
        val storage = GeoWorkerRuntime.storage(context)
        if (storage.getApiEndpoint().isNullOrEmpty() || storage.getDriverUuid().isNullOrEmpty()) {
            return
        }
        storage.setTrackingActive(true)
        LocationForegroundService.start(context.applicationContext)
    }

    fun stopLocationService(context: Context) {
        LocationForegroundService.stop(context.applicationContext)
    }
}
