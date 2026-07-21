package org.transline.geoworker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LocationTrackerModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "LocationTracker"

    // Lazy-инициализация KMP контроллера
    private val controller: org.transline.geoworker.tracker.LocationTrackerController by lazy {
        val storage = org.transline.geoworker.tracker.SharedPreferencesTrackingStorage(reactContext)
        val provider = AndroidLocationProvider(reactContext)
        val networkChecker = AndroidNetworkChecker(reactContext)
        val offlineQueue = org.transline.geoworker.tracker.StorageOfflineQueueStorage(storage)
        val apiService = object : org.transline.geoworker.tracker.LocationApiService {
            override suspend fun sendLocation(location: org.transline.geoworker.tracker.Location): Boolean {
                // TODO: заменить на реальный HTTP-вызов к серверу
                return true
            }
        }
        val locationRepository = org.transline.geoworker.tracker.LocationRepository(apiService, networkChecker, offlineQueue)
        org.transline.geoworker.tracker.LocationTrackerController(provider, locationRepository, storage)
    }

    private val notificationHelper: GeoNotificationHelper by lazy {
        GeoNotificationHelper(reactContext)
    }

    // Метод отправки событий в React Native
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
            state.lastSentTimestamp?.let { putDouble("lastSentTimestamp", it.toDouble()) }
            state.nextScheduledTimestamp?.let { putDouble("nextScheduledTimestamp", it.toDouble()) }
        }
    }

    // --- 1. Получение текущей геокоординаты по ручке ---
    @ReactMethod
    fun getCurrentLocation(promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val provider = AndroidLocationProvider(reactContext)
                val location = provider.getCurrentLocation()

                if (location != null) {
                    val map = Arguments.createMap().apply {
                        putDouble("latitude", location.latitude)
                        putDouble("longitude", location.longitude)
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

    // --- 2. Переход в окно настроек геолокации ---
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

    // --- 3. Проверка статуса разрешений ---
    @ReactMethod
    fun requestLocationPermissions(promise: Promise) {
        val fineGranted = ContextCompat.checkSelfPermission(
            reactContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted) {
            promise.resolve("GRANTED")
        } else {
            promise.resolve("DENIED")
        }
    }

    // --- 4. Инициализация при старте + синхронизация офлайн-очереди ---
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

    // --- 5. Получить текущее состояние расписания ---
    @ReactMethod
    fun getScheduleState(promise: Promise) {
        try {
            val state = controller.getScheduleState()
            promise.resolve(scheduleStateToMap(state))
        } catch (e: Exception) {
            promise.reject("STATE_ERROR", e.message, e)
        }
    }

    // --- 6. Принудительная проверка и отправка ---
    @ReactMethod
    fun checkAndSyncTracking(promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val state = controller.executePendingOrScheduledTracking()
                promise.resolve(scheduleStateToMap(state))
            } catch (e: Exception) {
                promise.reject("SYNC_ERROR", e.message, e)
            }
        }
    }
}
