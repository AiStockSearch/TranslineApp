# План 03 — KMP API (`LocationTrackerController`)

Источник: `app/shared/src/commonMain/kotlin/.../LocationTrackerController.kt`  
и соседние типы в том же пакете `org.transline.geoworker.tracker`.

---

## 3.1. Фабрика

### `LocationControllerFactory.createController(provider, storage, networkChecker)`

**Зачем:** единая точка сборки контроллера с платформенным HTTP-клиентом (`createPlatformHttpClient`).

**Возвращает:** `LocationTrackerController`.

---

## 3.2. Слушатели

### `addListener(TrackingListener)` / `removeListener`

**Зачем:** колбэки в нативный/RN слой без знания о React.

| Метод listener | Когда |
|----------------|--------|
| `onLocationSent(lat, lon, timestamp)` | Успешный HTTP 200 |
| `onLocationFailed(message)` | Ошибка/очередь |
| `onLocationServicesDisabled()` | `getCurrentLocation()` вернул null (рейсовый путь) |

---

## 3.3. Конфигурация и continuous

### `saveLocationConfiguration(apiEndpoint, driverUuid, orderNumber, updateIntervalMinutes?, authHeader?)`

**Зачем:** сохранить настройки **без** старта GPS.

- Нормализует endpoint → `…/api/coordinates`
- Пишет `orderNumber`, interval (если > 0)
- Auth: явный header **или** default Basic из ТЗ, если в storage пусто
- While `registration_locked`: identical rewrite → `true`; real overwrite of endpoint/uuid/auth/interval → `false` (orderNumber still applies)

**Не** трогает `tracking_active` и GPS.

---

### `startLocationService(apiEndpoint, driverUuid, orderNumber, updateIntervalMinutes?, authHeader?)`

**Зачем:** полный старт непрерывного мониторинга (как старый `LocationService`).

1. `saveLocationConfiguration` (while locked: identical → ok; overwrite rejected, fields untouched)
2. Если endpoint или driverUuid пусты после save → `false` (не стартует GPS)
3. `clearLastSentTimestamp` — первая отправка сразу разрешена
4. `setTrackingActive(true)`
5. `locationProvider.startTracking { onLocationUpdate }`

На Android RN-мост дополнительно поднимает **FGS**.

---

### `stopLocationService()`

**Зачем:** остановить continuous **без** полной очистки конфига рейса и **без** unlock registration (D-05).

- `tracking_active = false`
- clear `lastSent`
- reset `isRequestInProgress`
- `locationProvider.stopTracking()`

**Не** вызывает `setRegistrationLocked(false)` и **не** вызывает `storage.clear()` (в отличие от `completeTripAfterModeration` / `clearTripState`).

---

### `isLocationServiceRunning(): Boolean`

**Зачем:** зеркало `storage.isTrackingActive()` для JS `isLocationServiceRunning()`.

### `isRegistrationLocked(): Boolean`

**Зачем:** зеркало `storage.isRegistrationLocked()` для bridge/JS probe (GEO_LOCKED detection).

---

### `resumeLocationServiceIfActive(): Boolean`

**Зачем:** после reboot / перезапуска FGS **не** сбрасывать `lastSent` и не перезаписывать конфиг.

- Если inactive / нет endpoint/uuid → `false`
- Иначе снова `startTracking` → `onLocationUpdate`

---

### `onLocationUpdate(location)` (suspend)

**Зачем:** единственная точка throttle + send для continuous GPS.

Алгоритм:

1. Не active → return  
2. `!shouldSendLocation()` → return (interval или lock)  
3. `isRequestInProgress = true`, `lastSent = now`  
4. `sendOrQueueLocation`  
5. success → listeners sent  
6. fail → backoff `lastSent = now - intervalMs + 30_000` → listeners failed  
7. finally unlock  

---

### `shouldSendLocation(now?)` (internal)

**Зачем:** проверка throttle/lock (также используется в тестах).

---

## 3.4. Рейс (schedule)

### `startTrip(loadingTimeEpochMs)`

**Зачем:** назначить рейс.

- `firstTracking = loadingTime - 1 hour`
- `tracking_active = true`
- `nextScheduled = firstTracking`

Не стартует continuous GPS сам по себе (это делает RN/FGS при вызове с моста).

---

### `executePendingOrScheduledTracking(force = false): TrackingScheduleState`

**Зачем:** «тик» рейсового планировщика (boot, ручной sync, periodic).

- Если не active → только state  
- Если `force || now >= nextScheduled` → one-shot GPS → send → `next = now + 30min`  
- Иначе — только вернуть state  

---

### `completeTripAfterModeration()` (suspend)

**Зачем:** финал рейса после модерации.

1. Последняя точка (если есть) → send/queue  
2. `clearTripState()` — schedule/lock сброс; **endpoint/uuid/auth сохраняются** (DONE-02)  
3. `stopTracking()`  

Полный wipe registration — только `TrackingStorage.clear()` (logout).

### `clearTripState()`

Сбрасывает schedule / tracking_active / orderNumber / registration lock. Не трогает registration и offline queue.

### Registration lock (LOCK-01 soft)

`startTrip` → `registration_locked=true`.

**Locked fields (D-01, D-02):** endpoint, `driverUuid`, prefs `authHeader`; positive **interval overwrite is rejected** while locked. `orderNumber` may still update (non-empty). `saveSecureConfig` / `saveTokens` (OAuth blob) are **not** locked.

**Identical rewrite (D-04):** if proposed endpoint (via `normalizeCoordinatesEndpoint`), uuid, authHeader, and interval match stored values → save returns `true` (no-op). Null/empty authHeader and null/≤0 interval count as “no change intent”. Real overwrite → `false` without mutating locked identity fields.

**Unlock (D-05, D-06):** `clearTripState`, `completeTripAfterModeration` (uses clearTripState), and full `TrackingStorage.clear()` only. **`stopLocationService` does NOT unlock** — it only stops continuous GPS (`tracking_active=false`, clear lastSent, stopTracking).

**start while locked (D-08):** `startLocationService` may resume GPS using the **existing** stored registration after save (identical/no-op). Hard-fails (`false`, no GPS start) if endpoint or driverUuid is missing after save. Does not write rejected overwrite fields.

**Logout residual (D-07):** Menu logout today stops continuous + clears JS session flags only; full wipe is deferred. After this semantics change, `registration_locked` may remain `true` after logout until `clearTripState` / complete / full clear (Phase 9).

No `unlockRegistration()` JS API.

---

### `initializeAndSyncOnAppStart(): TrackingScheduleState` (suspend)

**Зачем:** cold start приложения.

1. `flushOfflineQueue()`  
2. `executePendingOrScheduledTracking(force = false)`  
3. Вернуть schedule state  

---

### `getScheduleState(): TrackingScheduleState`

**Зачем:** UI/JS статус без побочных эффектов.

Поля: `lastSentTimestamp`, `nextScheduledTimestamp`, `isTrackingActive`.

---

## 3.5. Repository / утилы

### `DefaultLocationRepository.sendOrQueueLocation(location)`

Собирает payload, clamp speed, POST; при fail — JSON-очередь в storage.

### `flushOfflineQueue()`

Повторная отправка накопленных точек при наличии сети.

### `normalizeCoordinatesEndpoint(host)`

Срезает `/api` и `/`, добавляет `/api/coordinates`.

### `clampSpeedMps(speed)`

`max(0, speed)`.

### Константы

| Имя | Значение | Смысл |
|-----|----------|--------|
| `DEFAULT_UPDATE_INTERVAL_MINUTES` | 1 | Дефолт continuous |
| `FAILURE_BACKOFF_EXTRA_MS` | 30_000 | Сдвиг при ошибке |
| `DEFAULT_COORDINATES_BASIC_AUTH` | Basic … | Дефолт auth |
| `THIRTY_MINUTES_MS` | 30 мин | Слот рейса |
| `ONE_HOUR_MS` | 1 час | Старт до погрузки |

---

## 3.6. PlatformLocationProvider

| Метод | Зачем |
|-------|--------|
| `getCurrentLocation()` | One-shot (рейс / getCurrentLocation RN) |
| `startTracking(onLocation)` | Continuous updates |
| `stopTracking()` | Остановка GPS |

Реализации: `IosLocationProvider`, `AndroidLocationProvider` (в androidApp).
