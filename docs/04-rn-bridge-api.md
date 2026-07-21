# План 04 — RN Native Bridge

## 4.1. Имена модулей в JS

| NativeModules key | Платформы | Класс |
|-------------------|-----------|--------|
| `LocationTracking` | Android + iOS | `LocationTrackerModule` (remap) |
| `SystemBars` | Android | `SystemBarsModule` |

Регистрация Android: `GeoWorkerPackage` → `MainApplication.getPackages()`.  
iOS: `RCT_EXTERN_REMAP_MODULE(LocationTracking, LocationTrackerModule, …)`.

Событие: **`onGeoWorkerEvent`** (payload содержит поле `type`).

---

## 4.2. Методы `LocationTracking` — continuous (ТЗ)

| Метод | Зачем | Побочные эффекты |
|-------|--------|------------------|
| `saveLocationConfiguration(endpoint, uuid, orderNumber, interval)` | Конфиг без GPS | Storage |
| `saveLocationConfigurationWithAuth(..., username, password)` | Конфиг + Basic из JS | Storage auth |
| `startLocationService(...)` | Старт continuous | Controller + **Android FGS** |
| `stopLocationService()` | Стоп | Controller + **stop FGS** |
| `isLocationServiceRunning()` | Статус флага | — |
| `requestLocationPermission()` | Диалог Always/Fine | System UI |
| `requestForegroundServicePermission()` | Android 13+ notifications | System UI |
| `hasRequiredPermissions()` | Код `"1"|"2"|"3"` (Always/background) | — |
| `getLocationPermissionStatus()` | Код `"1"|"2"|"3"` (whenInUse ok) | — |

---

## 4.3. Методы — рейс / утилиты

| Метод | Зачем |
|-------|--------|
| `getCurrentLocation()` | One-shot координаты в Promise map |
| `openGpsSettings()` | Системные настройки локации |
| `requestLocationPermissions()` | Legacy `"GRANTED"|"DENIED"|…` |
| `initializeAndSyncOnAppStart()` | Flush queue + schedule tick |
| `getScheduleState()` | Состояние (timestamps: `-1` = null) |
| `checkAndSyncTracking()` | `executePendingOrScheduledTracking(force=true)` |
| `startTrip(loadingTimeEpochMs)` | Рейс + FGS на Android |
| `completeTripAfterModeration()` | Финал + clearTripState (registration остаётся) + stop FGS |

---

## 4.4. `SystemBars` (Android)

| Метод | Зачем |
|-------|--------|
| `setModeStyle(light: boolean, flags: number)` | Стиль status/nav bar; JS передаёт `flags=3` для совместимости со старым кодом |

На iOS модуля нет — JS no-op.

---

## 4.5. Android-only классы рядом с мостом

| Класс | Зачем |
|-------|--------|
| `LocationForegroundService` | Держит процесс, resume GPS, sync queue |
| `BootReceiver` | После reboot → FGS если `tracking_active` |
| `LocationServiceController` | Старт/стоп из FCM без RN |
| `GeoNotificationHelper` | Локальные нотификации sent/offline |
| `AndroidLocationProvider` | Fused continuous + one-shot |
| `AndroidNetworkChecker` | Connectivity для repository |

---

## 4.6. iOS bridge особенности

- `LocationTrackerModule` — `RCTEventEmitter`
- Permission: `requestAlwaysAuthorization`
- GPS config в KMP `IosLocationProvider`: best accuracy, distanceFilter=1, background updates, indicator
- Suspend Kotlin → completion handlers в Swift (экспорт фреймворка)
