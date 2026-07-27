package org.transline.geoworker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.transline.geoworker.tracker.LocationTrackerController
import org.transline.geoworker.tracker.SharedPreferencesTrackingStorage
import org.transline.geoworker.tracker.NotifyI18nBundle
import org.transline.geoworker.tracker.decodeNotifyI18nBundle
import org.transline.geoworker.tracker.decodeTripNotifySession
import org.transline.geoworker.tracker.encodeNotifyI18nBundle
import org.transline.geoworker.tracker.encodeTripNotifySession
import org.transline.geoworker.notify.NotifyAction
import org.transline.geoworker.notify.NotifyActionId
import org.transline.geoworker.notify.NotifyAndroidContext
import org.transline.geoworker.notify.NotifyManagerHolder
import org.transline.geoworker.notify.NotifyPayload
import org.json.JSONObject

/**
 * RN NativeModule. Имя [LocationTracking] совместимо со старым LocationTracker.ts.
 * GPS/HTTP — через [GeoWorkerRuntime] (общий с FGS).
 */
class LocationTrackerModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "LocationTracking"

    private val storage: SharedPreferencesTrackingStorage
        get() = GeoWorkerRuntime.storage(reactContext)

    private val controller: LocationTrackerController
        get() = GeoWorkerRuntime.controller(reactContext)

    private val notificationHelper: GeoNotificationHelper by lazy {
        GeoNotificationHelper(reactContext)
    }

    private var rnListenerAttached = false

    init {
        attachRnListenerIfNeeded()
    }

    private fun attachRnListenerIfNeeded() {
        if (rnListenerAttached) return
        rnListenerAttached = true
        controller.addListener(object : org.transline.geoworker.tracker.TrackingListener {
            override fun onLocationSent(latitude: Double, longitude: Double, timestamp: Long) {
                // N1 geo success shade: skip when trip notify session is active (product N5 owns shade)
                val hasTripSession = !storage.getTripNotifySessionJson().isNullOrBlank()
                if (!hasTripSession) {
                    notificationHelper.showSuccessNotification(latitude, longitude)
                }
                val params = Arguments.createMap().apply {
                    putDouble("latitude", latitude)
                    putDouble("longitude", longitude)
                    putDouble("timestamp", timestamp.toDouble())
                }
                sendGeoEvent("LOCATION_SENT", params)
            }

            override fun onLocationFailed(message: String) {
                notificationHelper.showOfflineNotification()
                val params = Arguments.createMap().apply {
                    putString("message", message)
                }
                sendGeoEvent("LOCATION_FAILED", params)
            }

            override fun onLocationServicesDisabled() {
                sendGeoEvent("LOCATION_SERVICES_DISABLED")
            }

            override fun onProductNotify(title: String, body: String, deepLink: String) {
                showProductNotifyShade(title, body, deepLink)
                val params = Arguments.createMap().apply {
                    putString("title", title)
                    putString("body", body)
                    putString("deepLink", deepLink)
                }
                sendGeoEvent("PRODUCT_NOTIFY", params)
            }

            override fun onHttpResult(
                ok: Boolean,
                method: String,
                url: String,
                status: Int?,
                message: String,
            ) {
                val params = Arguments.createMap().apply {
                    putString("method", method)
                    putString("url", url)
                    if (status != null) putInt("status", status)
                    putString("message", message)
                }
                sendGeoEvent(if (ok) "HTTP_OK" else "HTTP_FAILED", params)
            }

            override fun onSecureConfigEvent(type: String, message: String?) {
                val params = Arguments.createMap().apply {
                    if (message != null) putString("message", message)
                }
                sendGeoEvent(type, params)
            }
        })
    }

    fun sendGeoEvent(type: String, payload: WritableMap = Arguments.createMap()) {
        payload.putString("type", type)
        if (reactContext.hasActiveReactInstance()) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("onGeoWorkerEvent", payload)
        }
    }

    private fun scheduleStateToMap(state: org.transline.geoworker.tracker.TrackingScheduleState): WritableMap {
        return Arguments.createMap().apply {
            putBoolean("isTrackingActive", state.isTrackingActive)
            putDouble("lastSentTimestamp", state.lastSentTimestamp?.toDouble() ?: -1.0)
            putDouble("nextScheduledTimestamp", state.nextScheduledTimestamp?.toDouble() ?: -1.0)
        }
    }

    private fun buildAuthHeader(username: String?, password: String?): String? {
        if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
            val credentials = "$username:$password"
            return "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        }
        return null
    }

    private fun buildBearerHeader(accessToken: String?): String? {
        if (accessToken.isNullOrBlank()) return null
        return org.transline.geoworker.tracker.buildBearerAuthHeader(accessToken)
    }

    /** Defensive JSON object → string map; ignore non-objects (T-01-09). Never log raw map. */
    private fun parseHeadersJson(headersJson: String?): Map<String, String> {
        if (headersJson.isNullOrBlank()) return emptyMap()
        return try {
            val obj = org.json.JSONObject(headersJson)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.opt(key)
                when {
                    value == null || value == org.json.JSONObject.NULL -> Unit
                    value is String -> map[key] = value
                    else -> map[key] = value.toString()
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun isLocationServicesEnabled(): Boolean {
        val lm = reactContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(
            reactContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasFineLocation()
        return ContextCompat.checkSelfPermission(
            reactContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Continuous / trip: Always-equivalent — background on Q+, fine below. */
    private fun hasRequiredTrackingPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasBackgroundLocation()
        } else {
            hasFineLocation()
        }

    // --- Continuous tracking API (совместимо со старым LocationTracking) ---

    @ReactMethod
    fun saveLocationConfiguration(
        apiEndpoint: String,
        driverUuid: String,
        orderNumber: String?,
        updateIntervalMinutes: Double?,
        promise: Promise
    ) {
        try {
            val interval = updateIntervalMinutes?.toInt()
            val ok = controller.saveLocationConfiguration(
                apiEndpoint = apiEndpoint,
                driverUuid = driverUuid,
                orderNumber = orderNumber.orEmpty(),
                updateIntervalMinutes = interval,
                authHeader = null
            )
            promise.resolve(ok)
        } catch (e: Exception) {
            promise.reject("CONFIG_ERROR", e.message, e)
        }
    }

    /** Расширенный save с username/password (рекомендуемое улучшение из ТЗ). */
    @ReactMethod
    fun saveLocationConfigurationWithAuth(
        apiEndpoint: String,
        driverUuid: String,
        orderNumber: String?,
        updateIntervalMinutes: Double?,
        username: String?,
        password: String?,
        promise: Promise
    ) {
        try {
            val ok = controller.saveLocationConfiguration(
                apiEndpoint = apiEndpoint,
                driverUuid = driverUuid,
                orderNumber = orderNumber.orEmpty(),
                updateIntervalMinutes = updateIntervalMinutes?.toInt(),
                authHeader = buildAuthHeader(username, password)
            )
            promise.resolve(ok)
        } catch (e: Exception) {
            promise.reject("CONFIG_ERROR", e.message, e)
        }
    }

    /** GpsService / JWT: `Authorization: Bearer <accessToken>`. */
    @ReactMethod
    fun saveLocationConfigurationWithBearer(
        apiEndpoint: String,
        driverUuid: String,
        orderNumber: String?,
        updateIntervalMinutes: Double?,
        accessToken: String?,
        promise: Promise
    ) {
        try {
            val ok = controller.saveLocationConfiguration(
                apiEndpoint = apiEndpoint,
                driverUuid = driverUuid,
                orderNumber = orderNumber.orEmpty(),
                updateIntervalMinutes = updateIntervalMinutes?.toInt(),
                authHeader = buildBearerHeader(accessToken)
            )
            promise.resolve(ok)
        } catch (e: Exception) {
            promise.reject("CONFIG_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun startLocationService(
        apiEndpoint: String,
        driverUuid: String,
        orderNumber: String?,
        updateIntervalMinutes: Double?,
        promise: Promise
    ) {
        try {
            // Parity with iOS Always: continuous FGS needs background location on Q+
            if (!hasRequiredTrackingPermission()) {
                promise.reject("PERMISSION_DENIED", "Required permissions not granted")
                return
            }
            if (!isLocationServicesEnabled()) {
                promise.reject("PERMISSION_DENIED", "Location services disabled")
                return
            }
            attachRnListenerIfNeeded()
            val ok = controller.startLocationService(
                apiEndpoint = apiEndpoint,
                driverUuid = driverUuid,
                orderNumber = orderNumber.orEmpty(),
                updateIntervalMinutes = updateIntervalMinutes?.toInt(),
                authHeader = null
            )
            if (ok) {
                LocationForegroundService.start(reactContext)
            }
            promise.resolve(ok)
        } catch (e: Exception) {
            promise.reject("START_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun stopLocationService(promise: Promise) {
        try {
            controller.stopLocationService()
            LocationForegroundService.stop(reactContext)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("STOP_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun isLocationServiceRunning(promise: Promise) {
        promise.resolve(controller.isLocationServiceRunning())
    }

    @ReactMethod
    fun isRegistrationLocked(promise: Promise) {
        try {
            promise.resolve(controller.isRegistrationLocked())
        } catch (e: Exception) {
            promise.reject("STATE_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun requestLocationPermission(promise: Promise) {
        if (!isLocationServicesEnabled()) {
            promise.reject("PERMISSION_DENIED", "3", null as Throwable?)
            return
        }
        if (hasFineLocation()) {
            promise.resolve(true)
            return
        }

        val activity = reactContext.currentActivity as? PermissionAwareActivity
        if (activity == null) {
            promise.reject("PERMISSION_DENIED", "3", null as Throwable?)
            return
        }

        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        activity.requestPermissions(
            permissions,
            REQUEST_LOCATION_PERMISSION,
            PermissionListener { _, _, grantResults ->
                val granted = grantResults.isNotEmpty() &&
                    grantResults.any { it == PackageManager.PERMISSION_GRANTED }
                if (granted) {
                    promise.resolve(true)
                } else {
                    promise.reject("PERMISSION_DENIED", "3", null as Throwable?)
                }
                true
            }
        )
    }

    @ReactMethod
    fun requestForegroundServicePermission(promise: Promise) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            promise.resolve(true)
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            reactContext, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            promise.resolve(true)
            return
        }
        val activity = reactContext.currentActivity as? PermissionAwareActivity
        if (activity == null) {
            promise.resolve(false)
            return
        }
        activity.requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATION_PERMISSION,
            PermissionListener { _, _, grantResults ->
                promise.resolve(
                    grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED
                )
                true
            }
        )
    }

    /**
     * "1" — Always (background), "2" — отклонено, "3" — гео выключена / не определено
     */
    @ReactMethod
    fun hasRequiredPermissions(promise: Promise) {
        if (!isLocationServicesEnabled()) {
            promise.resolve("3")
            return
        }
        if (hasBackgroundLocation()) {
            promise.resolve("1")
            return
        }
        if (!hasFineLocation()) {
            promise.resolve("3")
            return
        }
        promise.resolve("2")
    }

    /**
     * "1" — whenInUse или Always, "2" — denied, "3" — off / notDetermined
     */
    @ReactMethod
    fun getLocationPermissionStatus(promise: Promise) {
        if (!isLocationServicesEnabled()) {
            promise.resolve("3")
            return
        }
        if (hasFineLocation() || hasBackgroundLocation()) {
            promise.resolve("1")
            return
        }
        promise.resolve("2")
    }

    // --- Secure config / HTTP probe (PKG-01, D-08) — no public getSecrets/load ---

    @ReactMethod
    fun saveSecureConfig(
        access: String,
        refresh: String,
        endpointUrl: String?,
        headersJson: String?,
        promise: Promise,
    ) {
        try {
            val ok = controller.saveSecureConfig(
                access = access,
                refresh = refresh,
                endpointUrl = endpointUrl,
                customHeaders = parseHeadersJson(headersJson),
            )
            promise.resolve(ok)
        } catch (e: Exception) {
            promise.reject("KEYCHAIN_ERROR", e.message, e)
        }
    }

    /** Thin alias → saveSecureConfig preserving endpoint/headers when present. */
    @ReactMethod
    fun saveTokens(access: String, refresh: String, promise: Promise) {
        try {
            promise.resolve(controller.saveTokens(access, refresh))
        } catch (e: Exception) {
            promise.reject("KEYCHAIN_ERROR", e.message, e)
        }
    }

    /** Clears SecureConfigStore only (D-06) — never TrackingStorage.clear. */
    @ReactMethod
    fun clearSecrets(promise: Promise) {
        try {
            controller.clearSecrets()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("KEYCHAIN_ERROR", e.message, e)
        }
    }

    /**
     * KMP HTTP probe. Promise returns ok/method/url/status/message — never body.
     * Events (HTTP_OK / HTTP_FAILED / AUTH_MISSING) remain source of truth.
     */
    @ReactMethod
    fun httpProbe(url: String, method: String, body: String?, promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = controller.httpProbe(url, method, body)
                val map = Arguments.createMap().apply {
                    putBoolean("ok", result.ok)
                    putString("method", result.method)
                    putString("url", result.url)
                    val status = result.status
                    if (status != null) putInt("status", status)
                    putString("message", result.message)
                }
                promise.resolve(map)
            } catch (e: Exception) {
                promise.reject("HTTP_PROBE_ERROR", e.message, e)
            }
        }
    }

    // --- Существующие методы рейса / утилит ---

    @ReactMethod
    fun getCurrentLocation(promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // One-shot via shared provider (same Fused client as continuous)
                val location = AndroidLocationProvider(reactContext).getCurrentLocation()
                if (location != null) {
                    val map = Arguments.createMap().apply {
                        putDouble("latitude", location.latitude)
                        putDouble("longitude", location.longitude)
                        putDouble("speedMps", location.speedMps)
                        putDouble("timestamp", location.timestampMs.toDouble())
                    }
                    promise.resolve(map)
                } else {
                    promise.reject("LOCATION_NULL", "Геопозиция недоступна")
                }
            } catch (e: Exception) {
                promise.reject("LOCATION_ERROR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun openGpsSettings(promise: Promise) {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactContext.startActivity(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("SETTINGS_ERROR", "Ошибка открытия настроек", e)
        }
    }

    @ReactMethod
    fun requestLocationPermissions(promise: Promise) {
        if (hasFineLocation()) {
            promise.resolve("GRANTED")
        } else {
            promise.resolve("DENIED")
        }
    }

    @ReactMethod
    fun initializeAndSyncOnAppStart(promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val state = controller.initializeAndSyncOnAppStart()
                promise.resolve(scheduleStateToMap(state))
            } catch (e: Exception) {
                promise.reject("INIT_ERROR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun getScheduleState(promise: Promise) {
        try {
            promise.resolve(scheduleStateToMap(controller.getScheduleState()))
        } catch (e: Exception) {
            promise.reject("STATE_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun checkAndSyncTracking(promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val state = controller.executePendingOrScheduledTracking(force = true)
                promise.resolve(scheduleStateToMap(state))
            } catch (e: Exception) {
                promise.reject("SYNC_ERROR", e.message, e)
            }
        }
    }

    /**
     * WR-02 / REL-03: validate-before-mutate, sync resume, await initializeAndSyncOnAppStart
     * before resolve — never false-success after fire-and-forget FGS start.
     * Continuous interval 30 is written by KMP [LocationTrackerController.startTrip] prefs.
     * FGS remains async backup after successful resume.
     */
    @ReactMethod
    fun startTrip(loadingTimeEpochMs: Double, promise: Promise) {
        try {
            // WR-01: parity with startLocationService / iOS Always
            if (!hasRequiredTrackingPermission()) {
                promise.reject("PERMISSION_DENIED", "Required permissions not granted")
                return
            }
            if (!isLocationServicesEnabled()) {
                promise.reject("PERMISSION_DENIED", "Location services disabled")
                return
            }
            if (storage.getApiEndpoint().isNullOrEmpty() || storage.getDriverUuid().isNullOrEmpty()) {
                promise.reject("CONFIG_MISSING", "Save endpoint and driver UUID via GEO first")
                return
            }

            attachRnListenerIfNeeded()
            controller.startTrip(loadingTimeEpochMs.toLong())

            if (!controller.resumeLocationServiceIfActive()) {
                // WR-04: config already validated — distinct code; full stop clears mutate
                try {
                    controller.stopLocationService()
                } catch (_: Exception) {
                }
                promise.reject("RESUME_FAILED", "Cannot resume continuous tracking")
                return
            }

            LocationForegroundService.start(reactContext)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    controller.initializeAndSyncOnAppStart()
                    promise.resolve(null)
                } catch (e: Exception) {
                    // CR-01: stop GPS + FGS — flag-only rollback left hardware/FGS running
                    try {
                        controller.stopLocationService()
                    } catch (_: Exception) {
                    }
                    try {
                        LocationForegroundService.stop(reactContext)
                    } catch (_: Exception) {
                    }
                    promise.reject("START_TRIP_ERROR", e.message, e)
                }
            }
        } catch (e: Exception) {
            // CR-02: startTrip mutates tracking_active before resume/FGS/sync —
            // outer catch must roll back flag + stop provider/FGS.
            try {
                controller.stopLocationService()
            } catch (_: Exception) {
            }
            try {
                LocationForegroundService.stop(reactContext)
            } catch (_: Exception) {
            }
            promise.reject("START_TRIP_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun completeTripAfterModeration(promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                controller.completeTripAfterModeration()
                LocationForegroundService.stop(reactContext)
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("COMPLETE_TRIP_ERROR", e.message, e)
            }
        }
    }

    private fun showProductNotifyShade(title: String, body: String, deepLink: String) {
        try {
            NotifyAndroidContext.init(reactContext)
            val id = "geo_coords_${System.currentTimeMillis()}"
            val payload =
                NotifyPayload(
                    id = id,
                    title = title,
                    body = body,
                    deepLink = deepLink,
                    actions =
                        listOf(
                            NotifyAction(
                                id = NotifyActionId.OPEN,
                                title = "Open",
                                deepLink = deepLink,
                            ),
                        ),
                )
            NotifyManagerHolder.getOrCreate().show(payload)
        } catch (_: Exception) {
            // best-effort; PRODUCT_NOTIFY event still emitted for JS
        }
    }

    @ReactMethod
    fun setNotifyI18nBundle(locale: String, stringsJson: String, promise: Promise) {
        try {
            val obj = JSONObject(stringsJson)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { key -> map[key] = obj.optString(key) }
            val bundle =
                NotifyI18nBundle(
                    locale = locale,
                    updatedAtEpochMs = System.currentTimeMillis(),
                    strings = map,
                )
            storage.setNotifyI18nBundleJson(encodeNotifyI18nBundle(bundle))
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("NOTIFY_I18N_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun getNotifyI18nBundle(promise: Promise) {
        try {
            val bundle = decodeNotifyI18nBundle(storage.getNotifyI18nBundleJson())
            if (bundle == null) {
                promise.resolve(null)
                return
            }
            val stringsMap = Arguments.createMap()
            bundle.strings.forEach { (k, v) -> stringsMap.putString(k, v) }
            promise.resolve(
                Arguments.createMap().apply {
                    putString("locale", bundle.locale)
                    putDouble("updatedAtEpochMs", bundle.updatedAtEpochMs.toDouble())
                    putMap("strings", stringsMap)
                },
            )
        } catch (e: Exception) {
            promise.reject("NOTIFY_I18N_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun clearNotifyI18nBundle(promise: Promise) {
        storage.clearNotifyI18nBundle()
        promise.resolve(true)
    }

    @ReactMethod
    fun saveTripNotifySession(sessionJson: String, promise: Promise) {
        try {
            val session = decodeTripNotifySession(sessionJson)
                ?: run {
                    promise.reject("TRIP_SESSION_INVALID", "Invalid TripNotifySession JSON")
                    return
                }
            storage.setTripNotifySessionJson(encodeTripNotifySession(session))
            if (session.orderId.isNotBlank()) {
                storage.setOrderNumber(session.orderId)
            }
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("TRIP_SESSION_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun getTripNotifySession(promise: Promise) {
        try {
            val session = decodeTripNotifySession(storage.getTripNotifySessionJson())
            if (session == null) {
                promise.resolve(null)
                return
            }
            // Return parsed object via JSON round-trip for WritableMap simplicity
            promise.resolve(sessionJsonToWritableMap(storage.getTripNotifySessionJson()!!))
        } catch (e: Exception) {
            promise.reject("TRIP_SESSION_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun clearTripNotifySession(promise: Promise) {
        storage.clearTripNotifySession()
        promise.resolve(true)
    }

    private fun sessionJsonToWritableMap(json: String): WritableMap {
        val obj = JSONObject(json)
        return jsonObjectToWritableMap(obj)
    }

    private fun jsonObjectToWritableMap(obj: JSONObject): WritableMap {
        val map = Arguments.createMap()
        obj.keys().forEach { key ->
            when (val v = obj.get(key)) {
                is Boolean -> map.putBoolean(key, v)
                is Int -> map.putInt(key, v)
                is Long -> map.putDouble(key, v.toDouble())
                is Double -> map.putDouble(key, v)
                is String -> map.putString(key, v)
                is JSONObject -> map.putMap(key, jsonObjectToWritableMap(v))
                is org.json.JSONArray -> {
                    val arr = Arguments.createArray()
                    for (i in 0 until v.length()) {
                        when (val item = v.get(i)) {
                            is JSONObject -> arr.pushMap(jsonObjectToWritableMap(item))
                            is String -> arr.pushString(item)
                            is Boolean -> arr.pushBoolean(item)
                            is Int -> arr.pushInt(item)
                            is Double -> arr.pushDouble(item)
                            is Long -> arr.pushDouble(item.toDouble())
                            else -> arr.pushString(item.toString())
                        }
                    }
                    map.putArray(key, arr)
                }
                JSONObject.NULL -> map.putNull(key)
                else -> map.putString(key, v.toString())
            }
        }
        return map
    }

    /**
     * Требуются для NativeEventEmitter / TurboModule New Arch.
     * Без них JS-подписка на onGeoWorkerEvent падает на Android.
     */
    @ReactMethod
    fun addListener(eventName: String) {
        // no-op: DeviceEventManager не требует явной регистрации
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // no-op
    }

    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 10041
        private const val REQUEST_NOTIFICATION_PERMISSION = 10042
    }
}
