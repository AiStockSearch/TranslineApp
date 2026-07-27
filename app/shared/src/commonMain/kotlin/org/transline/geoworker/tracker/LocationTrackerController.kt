@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.transline.geoworker.tracker

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

expect fun currentTimeMillis(): Long

expect fun createPlatformHttpClient(): HttpClient

object LocationControllerFactory {
    fun createController(
            provider: PlatformLocationProvider,
            storage: TrackingStorage,
            networkChecker: NetworkChecker,
            secureStore: SecureConfigStore,
    ): LocationTrackerController {
        val httpClient = createPlatformHttpClient()
        val repository = DefaultLocationRepository(httpClient, storage, networkChecker)
        return LocationTrackerController(provider, repository, storage, secureStore, httpClient)
    }
}

class LocationTrackerController(
        private val locationProvider: PlatformLocationProvider,
        private val locationRepository: LocationRepository,
        private val storage: TrackingStorage,
        private val secureStore: SecureConfigStore,
        private val httpClient: HttpClient = createPlatformHttpClient(),
) {
    companion object {
        const val THIRTY_MINUTES_MS = 30 * 60 * 1000L
        const val ONE_HOUR_MS = 60 * 60 * 1000L
    }

    private val listeners = mutableListOf<TrackingListener>()
    @Volatile private var isRequestInProgress: Boolean = false

    private val trackingJob = SupervisorJob()
    private val trackingScope = CoroutineScope(trackingJob + Dispatchers.Default)

    fun addListener(listener: TrackingListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TrackingListener) {
        listeners.remove(listener)
    }

    fun getScheduleState(): TrackingScheduleState {
        val lastSent = storage.getLastSentTimestamp()
        val nextScheduled = storage.getNextScheduledTimestamp()
        val isActive = storage.isTrackingActive()

        return TrackingScheduleState(
                lastSentTimestamp = lastSent,
                nextScheduledTimestamp = nextScheduled,
                isTrackingActive = isActive
        )
    }

    fun saveLocationConfiguration(
            apiEndpoint: String,
            driverUuid: String,
            orderNumber: String = "",
            updateIntervalMinutes: Int? = null,
            authHeader: String? = null
    ): Boolean {
        // LOCK-01 soft: while locked, identical rewrite → true; real overwrite → false (D-01–D-04)
        if (storage.isRegistrationLocked()) {
            if (orderNumber.isNotEmpty()) {
                storage.setOrderNumber(orderNumber)
            }
            val sameEndpoint = normalizeCoordinatesEndpoint(apiEndpoint) == storage.getApiEndpoint()
            val sameUuid = driverUuid == storage.getDriverUuid()
            val sameAuth = authHeader.isNullOrEmpty() || authHeader == storage.getAuthHeader()
            val sameInterval =
                    updateIntervalMinutes == null ||
                            updateIntervalMinutes <= 0 ||
                            updateIntervalMinutes == storage.getUpdateIntervalMinutes()
            return sameEndpoint && sameUuid && sameAuth && sameInterval
        }
        storage.setApiEndpoint(normalizeCoordinatesEndpoint(apiEndpoint))
        storage.setDriverUuid(driverUuid)
        storage.setOrderNumber(orderNumber)
        if (updateIntervalMinutes != null && updateIntervalMinutes > 0) {
            storage.setUpdateIntervalMinutes(updateIntervalMinutes)
        }
        applyAuthHeader(authHeader)
        return true
    }

    fun startLocationService(
            apiEndpoint: String,
            driverUuid: String,
            orderNumber: String = "",
            updateIntervalMinutes: Int? = null,
            authHeader: String? = null
    ): Boolean {
        saveLocationConfiguration(
                apiEndpoint = apiEndpoint,
                driverUuid = driverUuid,
                orderNumber = orderNumber,
                updateIntervalMinutes = updateIntervalMinutes,
                authHeader = authHeader
        )
        // D-08: hard-fail if registration missing after save (do not hijack / start empty)
        if (storage.getApiEndpoint().isNullOrEmpty() || storage.getDriverUuid().isNullOrEmpty()) {
            return false
        }
        // Первая отправка разрешена (как при lastRequestTime == nil в старом модуле)
        storage.clearLastSentTimestamp()
        storage.setTrackingActive(true)
        locationProvider.startTracking { location ->
            trackingScope.launch { onLocationUpdate(location) }
        }
        return true
    }

    fun stopLocationService() {
        storage.setTrackingActive(false)
        // D-05: stop continuous only — do NOT unlock registration
        storage.clearLastSentTimestamp()
        isRequestInProgress = false
        locationProvider.stopTracking()
    }

    fun isLocationServiceRunning(): Boolean = storage.isTrackingActive()

    /** Bridge/JS symmetry for lock probe (feeds 08-02). */
    fun isRegistrationLocked(): Boolean = storage.isRegistrationLocked()

    /** Восстановление continuous GPS после reboot/FGS без сброса lastSent и конфига. */
    fun resumeLocationServiceIfActive(): Boolean {
        if (!storage.isTrackingActive()) return false
        if (storage.getApiEndpoint().isNullOrEmpty() || storage.getDriverUuid().isNullOrEmpty()) {
            return false
        }
        locationProvider.startTracking { location ->
            trackingScope.launch { onLocationUpdate(location) }
        }
        return true
    }

    /**
     * Точка входа для платформенного continuous GPS. Троттлинг по updateIntervalMinutes, lock
     * isRequestInProgress, backoff при ошибке.
     */
    suspend fun onLocationUpdate(location: Location) {
        if (!storage.isTrackingActive()) return
        if (!shouldSendLocation()) return

        val now = currentTimeMillis()
        val intervalMs = storage.getUpdateIntervalMinutes() * 60_000L

        isRequestInProgress = true
        storage.setLastSentTimestamp(now)

        try {
            val success = locationRepository.sendOrQueueLocation(location)
            if (success) {
                listeners.forEach {
                    it.onLocationSent(location.latitude, location.longitude, location.timestampMs)
                }
            } else {
                // Как в LocationService.swift: lastRequestTime = previous - interval + 30s
                val failureTime = now - intervalMs + FAILURE_BACKOFF_EXTRA_MS
                storage.setLastSentTimestamp(failureTime)
                listeners.forEach {
                    it.onLocationFailed("Ошибка отправки (сохранено в офлайн-очередь)")
                }
            }
        } finally {
            isRequestInProgress = false
        }
    }

    internal fun shouldSendLocation(now: Long = currentTimeMillis()): Boolean {
        if (isRequestInProgress) return false

        val lastSent = storage.getLastSentTimestamp() ?: return true
        val intervalMs = storage.getUpdateIntervalMinutes() * 60_000L
        return (now - lastSent) >= intervalMs
    }

    /** Для тестов: симулирует занятый HTTP-запрос. */
    internal fun setRequestInProgressForTest(inProgress: Boolean) {
        isRequestInProgress = inProgress
    }

    fun startTrip(loadingTimeEpochMs: Long) {
        val firstTrackingTime = loadingTimeEpochMs - ONE_HOUR_MS
        storage.setTrackingActive(true)
        storage.setNextScheduledTimestamp(firstTrackingTime)
        // Trip continuous throttle: 30 min (D-26/D-29) — FGS/resume/FCM read prefs
        storage.setUpdateIntervalMinutes(30)
        storage.setRegistrationLocked(true)
    }

    suspend fun executePendingOrScheduledTracking(force: Boolean = false): TrackingScheduleState {
        if (!storage.isTrackingActive()) {
            return getScheduleState()
        }

        val now = currentTimeMillis()
        val nextScheduled = storage.getNextScheduledTimestamp() ?: now

        if (force || now >= nextScheduled) {
            val location = locationProvider.getCurrentLocation()
            if (location != null) {
                val success = locationRepository.sendOrQueueLocation(location)
                if (success) {
                    val currentSentTime = now
                    val newNextTime = currentSentTime + THIRTY_MINUTES_MS

                    storage.setLastSentTimestamp(currentSentTime)
                    storage.setNextScheduledTimestamp(newNextTime)

                    listeners.forEach {
                        it.onLocationSent(
                                location.latitude,
                                location.longitude,
                                location.timestampMs
                        )
                    }
                    val productNotify =
                            resolveProductCoordsNotifyFromStorage(
                                    storage,
                                    location.latitude,
                                    location.longitude,
                            )
                    if (productNotify != null) {
                        listeners.forEach {
                            it.onProductNotify(
                                    productNotify.title,
                                    productNotify.body,
                                    productNotify.deepLink,
                            )
                        }
                    }
                } else {
                    listeners.forEach {
                        it.onLocationFailed("Ошибка отправки (сохранено в офлайн-очередь)")
                    }
                }
            } else {
                listeners.forEach { it.onLocationServicesDisabled() }
            }
        }

        return getScheduleState()
    }

    suspend fun completeTripAfterModeration() {
        val lastLocation = locationProvider.getCurrentLocation()
        if (lastLocation != null) {
            val success = locationRepository.sendOrQueueLocation(lastLocation)
            if (success) {
                listeners.forEach {
                    it.onLocationSent(
                            lastLocation.latitude,
                            lastLocation.longitude,
                            lastLocation.timestampMs
                    )
                }
            } else {
                listeners.forEach {
                    it.onLocationFailed("Ошибка отправки (сохранено в офлайн-очередь)")
                }
            }
        }

        // DONE-02: keep endpoint/uuid/auth for next trip; only clear trip schedule
        clearTripState()
        locationProvider.stopTracking()
    }

    /**
     * Clears trip schedule + unlocks registration. Keeps endpoint/uuid/auth/offline queue. Full
     * wipe remains [TrackingStorage.clear] for logout / explicit reset.
     */
    fun clearTripState() {
        storage.setTrackingActive(false)
        storage.clearLastSentTimestamp()
        storage.clearNextScheduledTimestamp()
        storage.setOrderNumber("")
        storage.setRegistrationLocked(false)
        storage.clearTripNotifySession()
        isRequestInProgress = false
    }

    suspend fun initializeAndSyncOnAppStart(): TrackingScheduleState {
        locationRepository.flushOfflineQueue()
        executePendingOrScheduledTracking(force = false)
        return getScheduleState()
    }

    /** Persist D-01 secure blob. Never writes prefs [TrackingStorage] auth_header. */
    fun saveSecureConfig(
            access: String,
            refresh: String,
            endpointUrl: String? = null,
            customHeaders: Map<String, String> = emptyMap(),
    ): Boolean {
        return try {
            secureStore.save(
                    SecureConfig(
                            access = access,
                            refresh = refresh,
                            endpointUrl = endpointUrl,
                            customHeaders = customHeaders,
                    )
            )
            safeEmitSecureEvent(SecureConfigEventType.KEYCHAIN_SAVED, null)
            true
        } catch (e: Exception) {
            safeEmitSecureEvent(SecureConfigEventType.KEYCHAIN_ERROR, e.message?.take(200))
            false
        }
    }

    /**
     * Thin alias: updates tokens while preserving endpoint/headers when present.
     * Must never throw across ObjC — undeclared Kotlin exceptions abort the iOS process.
     */
    fun saveTokens(access: String, refresh: String): Boolean {
        return try {
            val existing =
                    try {
                        secureStore.load()
                    } catch (_: Exception) {
                        null
                    }
            saveSecureConfig(
                    access = access,
                    refresh = refresh,
                    endpointUrl = existing?.endpointUrl,
                    customHeaders = existing?.customHeaders ?: emptyMap(),
            )
        } catch (e: Exception) {
            safeEmitSecureEvent(SecureConfigEventType.KEYCHAIN_ERROR, e.message?.take(200))
            false
        }
    }

    /** Clears only [SecureConfigStore] (D-06). Does not touch [TrackingStorage]. */
    fun clearSecrets() {
        try {
            secureStore.clear()
            safeEmitSecureEvent(SecureConfigEventType.KEYCHAIN_CLEARED, null)
        } catch (e: Exception) {
            safeEmitSecureEvent(SecureConfigEventType.KEYCHAIN_ERROR, e.message?.take(200))
        }
    }

    private fun safeEmitSecureEvent(type: String, message: String?) {
        try {
            listeners.forEach { it.onSecureConfigEvent(type, message) }
        } catch (_: Exception) {
            // Listener failures must not abort ObjC callers.
        }
    }

    /**
     * KMP-only configurable HTTP probe (D-02, D-07, D-09). Emits status + short message + method +
     * redacted URL — no response body, no tokens. No automatic 401→refresh→retry.
     */
    suspend fun httpProbe(url: String, method: String, body: String? = null): HttpProbeResult {
        val normalized = HttpProbe.normalizeMethod(method)
        val safeUrl = HttpProbe.redactUrl(url)

        if (!HttpProbe.isAllowedMethod(normalized)) {
            return emitHttpFailed(
                    normalized,
                    safeUrl,
                    status = null,
                    message = "HTTP method not allowed"
            )
        }

        if (HttpProbe.isBodyTooLarge(body)) {
            return emitHttpFailed(
                    normalized,
                    safeUrl,
                    status = null,
                    message = "Request body too large (max 256KB)",
            )
        }

        val config = secureStore.load()
        val access = config?.access
        if (access.isNullOrEmpty()) {
            listeners.forEach {
                it.onSecureConfigEvent(SecureConfigEventType.AUTH_MISSING, "Access token missing")
            }
            return emitHttpFailed(
                    normalized,
                    safeUrl,
                    status = null,
                    message = "Access token missing"
            )
        }

        val headers = HttpProbe.buildProbeHeaders(config.customHeaders, access)

        return try {
            val response: HttpResponse =
                    httpClient.request(url) {
                        this.method = probeHttpMethod(normalized)
                        headers.forEach { (key, value) -> header(key, value) }
                        if (body != null && normalized != "GET") {
                            // Coordinates (and most JSON APIs) reject missing/wrong type with HTTP 415.
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                    }
            val status = response.status.value
            val ok = status in 200..299
            val message = if (ok) "OK" else "HTTP $status"
            val result =
                    HttpProbeResult(
                            ok = ok,
                            method = normalized,
                            url = safeUrl,
                            status = status,
                            message = message,
                    )
            listeners.forEach {
                it.onHttpResult(result.ok, result.method, result.url, result.status, result.message)
            }
            result
        } catch (e: Exception) {
            emitHttpFailed(
                    normalized,
                    safeUrl,
                    status = null,
                    message = (e.message ?: "Request failed").take(200),
            )
        }
    }

    private fun emitHttpFailed(
            method: String,
            url: String,
            status: Int?,
            message: String,
    ): HttpProbeResult {
        val result =
                HttpProbeResult(
                        ok = false,
                        method = method,
                        url = url,
                        status = status,
                        message = message,
                )
        listeners.forEach {
            it.onHttpResult(result.ok, result.method, result.url, result.status, result.message)
        }
        return result
    }

    private fun probeHttpMethod(method: String): HttpMethod =
            when (method) {
                "GET" -> HttpMethod.Get
                "POST" -> HttpMethod.Post
                "PUT" -> HttpMethod.Put
                "PATCH" -> HttpMethod.Patch
                else -> HttpMethod.parse(method)
            }

    /**
     * Persist auth when provided; otherwise keep existing or fall back to deprecated default
     * (D-07). Prefer explicit [authHeader] / RN WithAuth / WithBearer — default is client-shipped
     * Basic debt.
     */
    @Suppress("DEPRECATION")
    private fun applyAuthHeader(authHeader: String?) {
        if (!authHeader.isNullOrEmpty()) {
            storage.setAuthHeader(authHeader)
        } else if (storage.getAuthHeader().isNullOrEmpty()) {
            storage.setAuthHeader(DEFAULT_COORDINATES_BASIC_AUTH)
        }
    }
}
