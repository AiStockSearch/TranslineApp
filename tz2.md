Для того чтобы система работала полностью устойчиво (с проверкой сети, повторными попытками, получением гео по расстоянию/таймауту и связью с React Native), необходимо достроить **4 ключевых модуля** на уровне KMP и нативных платформ.

---

## 1. Проверка сети перед отправкой (Network Connectivity Check)

Перед выполнением запроса модуль должен знать, есть ли интернет, чтобы не тратить батарею на заведомо неудачные HTTP-запросы.

### KMP Интерфейс (`shared/src/commonMain/kotlin/org/transline/geoworker/NetworkChecker.kt`)

```kotlin
package org.transline.geoworker

interface NetworkChecker {
    fun isNetworkAvailable(): Boolean
}

```

### Android Реализация (`ConnectivityManager`)

```kotlin
package org.transline.geoworker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class AndroidNetworkChecker(private val context: Context) : NetworkChecker {
    override fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

```

### iOS Реализация (`NWPathMonitor`)

```swift
import Network

class IOSNetworkChecker: NSObject, NetworkChecker {
    private let monitor = NWPathMonitor()
    private var isConnected = false

    override init() {
        super.init()
        monitor.pathUpdateHandler = { path in
            self.isConnected = (path.status == .satisfied)
        }
        let queue = DispatchQueue(label: "NetworkMonitor")
        monitor.start(queue: queue)
    }

    func isNetworkAvailable() -> Bool {
        return isConnected
    }
}

```

---

## 2. Очередь неотправленных локаций (Retry & Offline Queue)

Если `fetch` завершился ошибкой (или нет сети), координаты не должны теряться. Их нужно **сохранять в локальную БД или файл** и отправлять при следующей успешной попытке.

### KMP Репозиторий с очередью (`OfflineQueueRepository.kt`)

```kotlin
package org.transline.geoworker

class LocationRepository(
    private val apiService: LocationApiService,
    private val networkChecker: NetworkChecker,
    private val offlineDatabase: OfflineLocationDao // SQLite (например, через SQLDelight) или File-storage
) {
    suspend fun sendOrQueueLocation(location: LocationData): Boolean {
        // 1. Сохраняем в локальную очередь
        offlineDatabase.save(location)

        // 2. Если сети нет — выходим, отправка произойдет позже
        if (!networkChecker.isNetworkAvailable()) {
            return false
        }

        // 3. Достаем все неотправленные локации
        val pendingLocations = offlineDatabase.getAllPending()
        
        for (item in pendingLocations) {
            val isSuccess = apiService.sendLocation(item)
            if (isSuccess) {
                offlineDatabase.delete(item.id) // Удаляем из очереди при успехе
            } else {
                // Если запрос упал — прерываем цикл, отправим при следующем таймере
                return false 
            }
        }
        return true
    }
}

```

---

## 3. Фильтрация геолокации (По расстоянию и таймауту)

Геолокация должна запрашиваться нативным движком по условию: **прошло $N$ минут OR водитель проехал больше $X$ метров**.

### Android (`LocationRequest` с `DistanceFilter`)

В Android настройки задаются при подписке на `FusedLocationProviderClient`:

```kotlin
val locationRequest = LocationRequest.Builder(
    Priority.PRIORITY_HIGH_ACCURACY, 
    30 * 60 * 1000L // Таймаут: каждые 30 минут
).apply {
    setMinUpdateDistanceMeters(500f) // Минимальное расстояние: например, 500 метров
}.build()

```

### iOS (`CLLocationManager`)

В iOS это встроенные свойства `CLLocationManager`:

```swift
locationManager.distanceFilter = 500 // Обновлять только если переместился на 500 метров
locationManager.allowsBackgroundLocationUpdates = true
locationManager.pausesLocationUpdatesAutomatically = false

```

### KMP Фильтр расхода батареи (`LocationFilter.kt`)

Дополнительно в KMP коде можно проверить физическое расстояние перед отправкой, чтобы не спамить одинаковыми координатами, если машина стоит в пробке:

```kotlin
import kotlin.math.*

object LocationUtils {
    // Формула Haversine для расчета расстояния в метрах между двумя точками
    fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Радиус Земли в метрах
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val a = sin(dLat / 2).pow(2) + cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun Double.toRadians(): Double = this * Double.NaN // (Math.PI / 180.0)
}

```

---

## 4. Двусторонняя связь с React Native (EventEmitter / Native Events)

Для передачи событий из нативного модуля в JS (например, «состояние сети изменилось», «координата отправлена», «произошла ошибка fetch») используются **Native Events**.

### Android (`RCTDeviceEventEmitter`)

```kotlin
package org.transline.geoworker

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

class ReactBridgeNotifier(private val reactContext: ReactApplicationContext) {

    fun sendEventToJS(eventName: String, params: WritableMap) {
        if (reactContext.hasActiveReactInstance()) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
        }
    }
}

```

### iOS (`RCTEventEmitter`)

```swift
@objc(LocationEventEmitter)
class LocationEventEmitter: RCTEventEmitter {
  
  public static var shared: LocationEventEmitter?

  override init() {
    super.init()
    LocationEventEmitter.shared = self
  }

  override func supportedEvents() -> [String]! {
    return ["onLocationSent", "onTrackingError", "onNetworkStatusChanged"]
  }

  func sendEvent(name: String, body: Any) {
    sendEvent(withName: name, body: body)
  }
}

```

### Подписка на события в React Native (JS/TS)

```typescript
import { NativeEventEmitter, NativeModules } from 'react-native';

const { LocationTracker } = NativeModules;
const locationEmitter = new NativeEventEmitter(LocationTracker);

// Подписываемся на события от KMP / Native
useEffect(() => {
  const subscription = locationEmitter.addListener('onLocationSent', (event) => {
    console.log('Локация успешно отправлена из фона:', event.timestamp);
  });

  const errorSubscription = locationEmitter.addListener('onTrackingError', (error) => {
    console.warn('Ошибка отправки (сохранено в офлайн-очередь):', error.message);
  });

  return () => {
    subscription.remove();
    errorSubscription.remove();
  };
}, []);

```

---

## Сводная схема работы всей системы

1. **Таймаут (30 мин) или Дистанция (500 м)** триггерит геолокацию на уровне OS (Android/iOS).
2. **KMP модуль** запрашивает статус у `NetworkChecker`:
* **Нет сети:** Локация пишется в `OfflineDatabase` (SQLite/Realm/File).
* **Есть сеть:** Берётся текущая локация + все накопившиеся локации из `OfflineDatabase` и отправляются одним/несколькими пакетами.


3. **Если `fetch` завершился ошибкой (5xx / 4xx):** Локация остаётся в офлайн-очереди до следующего цикла.
4. **Результат** передаётся через `EventEmitter` в **React Native**, обновляя UI приложения в реальном времени.

Отличный сквозной вопрос! Чтобы всё это работало **единой системой** (React Native $\leftrightarrow$ iOS/Android Native $\leftrightarrow$ KMP), вся цепочка вызовов (проверка разрешений, открытие настроек, получении гео по кнопке и получение событий через EventEmitter) связывается от JS до натива на обеих платформах.

Ниже полное пошаговое руководство по созданию этой сквозной интеграции.

---

## 1. Сквозная схема взаимодействия

```text
               ┌──────────────────────────────────────────┐
               │         React Native (JS / TS)           │
               └──────────┬────────────────────▲──────────┘
  1. Вызов методов        │                    │ 2. Native Events
 (getCurrentLocation,     │ Bridge / Promise   │ (onGeoWorkerEvent:
  openGpsSettings, etc.)  ▼                    │  LOCATION_SENT, GPS_OFF)
    ┌───────────────────────────┐        ┌─────┴─────────────────────┐
    │  Android (Kotlin Module)  │        │   iOS (Swift / Obj-C)     │
    └─────────────┬─────────────┘        └─────────────┬─────────────┘
                  │                                    │
                  └─────────────────┬──────────────────┘
                                    ▼
               ┌──────────────────────────────────────────┐
               │            KMP Shared Module             │
               │        (org.transline.geoworker)         │
               └──────────────────────────────────────────┘

```

---

## 2. Реализация для iOS (Swift + Objective-C)

На iOS системный диалог включения геолокации сделать через тумблер нельзя (Apple запрещает прямой доступ к системным тумблерам), но можно **открыть настройки приложения (`UIApplication.openSettingsURLString`)**, а для EventEmitter используется `RCTEventEmitter`.

### 1) Мост экспорта: `ios/LocationTrackerModule.m`

```objc
#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

@interface RCT_EXTERN_MODULE(LocationTrackerModule, RCTEventEmitter)

RCT_EXTERN_METHOD(getCurrentLocation:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(openGpsSettings:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(requestLocationPermissions:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

@end

```

### 2) Нативная реализация: `ios/LocationTrackerModule.swift`

```swift
import Foundation
import CoreLocation
import UIKit
import React

@objc(LocationTrackerModule)
class LocationTrackerModule: RCTEventEmitter {

  public static var shared: LocationTrackerModule?
  private let locationManager = CLLocationManager()

  override init() {
    super.init()
    LocationTrackerModule.shared = self
  }

  override static func requiresMainQueueSetup() -> Bool {
    return true
  }

  // Определяем список событий для JS
  override func supportedEvents() -> [String]! {
    return ["onGeoWorkerEvent"]
  }

  // Вспомогательный метод для отправки событий в React Native из любого места iOS
  func sendGeoEvent(type: String, payload: [String: Any] = [:]) {
    var body = payload
    body["type"] = type
    sendEvent(withName: "onGeoWorkerEvent", body: body)
  }

  // --- 1. Ручка получения текущей геокоординаты ---
  @objc func getCurrentLocation(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    let provider = IOSLocationProvider()
    Task {
      if let location = await provider.getCurrentLocation() {
        resolve([
          "latitude": location.latitude,
          "longitude": location.longitude,
          "timestamp": location.timestamp
        ])
      } else {
        reject("LOCATION_ERROR", "Не удалось получить геопозицию", nil)
      }
    }
  }

  // --- 2. Переход в настройки приложения (для включения GPS/разрешений) ---
  @objc func openGpsSettings(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    DispatchQueue.main.async {
      if let url = URL(string: UIApplication.openSettingsURLString) {
        if UIApplication.shared.canOpenURL(url) {
          UIApplication.shared.open(url, options: [:], completionHandler: nil)
          resolve(true)
          return
        }
      }
      reject("SETTINGS_ERROR", "Не удалось открыть настройки iOS", nil)
    }
  }

  // --- 3. Запрос разрешений на геолокацию ---
  @objc func requestLocationPermissions(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    DispatchQueue.main.async {
      self.locationManager.requestAlwaysAuthorization()
      let status = CLLocationManager.authorizationStatus()
      
      switch status {
      case .authorizedAlways, .authorizedWhenInUse:
        resolve("GRANTED")
      case .denied, .restricted:
        resolve("DENIED")
      default:
        resolve("NOT_DETERMINED")
      }
    }
  }
}

```

---

## 3. Реализация для Android (Kotlin)

На Android мы можем открыть прямой экран настроек местоположения (`Settings.ACTION_LOCATION_SOURCE_SETTINGS`) или настройки приложения.

### `android/app/src/main/java/org/transline/geoworker/LocationTrackerModule.kt`

```kotlin
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

    // Метод отправки событий в React Native
    fun sendGeoEvent(type: String, payload: WritableMap = Arguments.createMap()) {
        payload.putString("type", type)
        if (reactContext.hasActiveReactInstance()) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("onGeoWorkerEvent", payload)
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
                        putDouble("timestamp", location.timestamp.toDouble())
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
}

```

---

## 4. Единый клиентский модуль в React Native (JS / TS)

Создаем единую обертку `LocationTrackerService.ts`, которая скрывает различия платформ и одинаково работает как на iOS, так и на Android.

### `src/native/LocationTrackerService.ts`

```typescript
import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

const { LocationTracker } = NativeModules;
const geoEventEmitter = new NativeEventEmitter(LocationTracker);

export type GeoEventType = 
  | 'LOCATION_SENT' 
  | 'LOCATION_FAILED' 
  | 'LOCATION_SERVICES_DISABLED' 
  | 'PERMISSION_DENIED';

export interface GeoEventPayload {
  type: GeoEventType;
  latitude?: number;
  longitude?: number;
  timestamp?: number;
  message?: string;
}

export interface LocationCoordinates {
  latitude: number;
  longitude: number;
  timestamp: number;
}

export const LocationTrackerService = {
  /**
   * Запросить текущие геокоординаты водителя прямо сейчас
   */
  getCurrentLocation: async (): Promise<LocationCoordinates> => {
    return await LocationTracker.getCurrentLocation();
  },

  /**
   * Открыть системные настройки устройства/приложения для включения геолокации
   */
  openGpsSettings: async (): Promise<boolean> => {
    return await LocationTracker.openGpsSettings();
  },

  /**
   * Проверить статус разрешений
   */
  checkPermissions: async (): Promise<'GRANTED' | 'DENIED' | 'NOT_DETERMINED'> => {
    return await LocationTracker.requestLocationPermissions();
  },

  /**
   * Подписка на сквозные события от нативного модуля (Android/iOS)
   */
  subscribeToEvents: (listener: (event: GeoEventPayload) => void) => {
    const subscription = geoEventEmitter.addListener('onGeoWorkerEvent', listener);
    return () => subscription.remove();
  }
};

```

---

## 5. Использование в React Native компоненте

```tsx
import React, { useEffect, useState } from 'react';
import { View, Text, Button, Alert } from 'react-native';
import { LocationTrackerService, LocationCoordinates } from './src/native/LocationTrackerService';

export const GeoWorkerScreen = () => {
  const [currentLocation, setCurrentLocation] = useState<LocationCoordinates | null>(null);

  useEffect(() => {
    // 1. Слушаем события, которые отправляет iOS и Android
    const unsubscribe = LocationTrackerService.subscribeToEvents((event) => {
      console.log('Событие геолокации:', event);

      switch (event.type) {
        case 'LOCATION_SENT':
          console.log(`[Успех] Гео отправлено: ${event.latitude}, ${event.longitude}`);
          break;

        case 'LOCATION_SERVICES_DISABLED':
          Alert.alert(
            'Геолокация выключена',
            'Для отправки трекинга необходимо включить службы геолокации.',
            [
              { text: 'Отмена', style: 'cancel' },
              { 
                text: 'Настройки', 
                onPress: () => LocationTrackerService.openGpsSettings() 
              }
            ]
          );
          break;

        case 'LOCATION_FAILED':
          console.warn('Не удалось отправить локацию. Сохранено в офлайн-очередь.');
          break;
      }
    });

    return () => unsubscribe();
  }, []);

  // 2. Ручное получение локации по кнопке
  const handleFetchLocation = async () => {
    try {
      const location = await LocationTrackerService.getCurrentLocation();
      setCurrentLocation(location);
    } catch (error) {
      Alert.alert('Ошибка', 'Не удалось определить геопозицию');
    }
  };

  return (
    <View style={{ padding: 20 }}>
      <Button title="Получить текущую геопозицию" onPress={handleFetchLocation} />
      
      {currentLocation && (
        <View style={{ marginTop: 10 }}>
          <Text>Широта: {currentLocation.latitude}</Text>
          <Text>Долгота: {currentLocation.longitude}</Text>
          <Text>Время: {new Date(currentLocation.timestamp).toLocaleTimeString()}</Text>
        </View>
      )}
    </View>
  );
};

```