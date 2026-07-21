package org.transline.geoworker.tracker

interface TrackingListener {
    fun onLocationSent(latitude: Double, longitude: Double, timestamp: Long)
    fun onLocationFailed(message: String)
    fun onLocationServicesDisabled()

    /** HTTP probe result — status + short message + method + redacted URL; never body/tokens (D-03, D-04). */
    fun onHttpResult(
        ok: Boolean,
        method: String,
        url: String,
        status: Int?,
        message: String,
    ) {
    }

    /** KEYCHAIN_* / AUTH_* style events for secure config (KMP-06 additive). */
    fun onSecureConfigEvent(type: String, message: String? = null) {
    }
}
