# План 11 — События, разрешения, сеть

## 11.1. HTTP контракт

**Метод:** `POST`  
**URL:** `{normalizedHost}/api/coordinates`  
**Headers:**

```
Authorization: Basic …   // default или из saveLocationConfigurationWithAuth
Content-Type: application/json
```

**Body:**

```json
{
  "latitude": 55.755826,
  "longitude": 37.617299,
  "speed_mps": 12.5,
  "driver_uuid": "c3017a12-8419-4171-807d-5a8a18df7907"
}
```

- `speed_mps` отрицательный → `0` (поле в коде `speedMps`, в JSON — snake_case GpsService)
- Успех: HTTP **200**  
- Иначе: точка в offline queue + backoff (continuous) / failed event  

Default Basic (ТЗ): пользователь `transline_user` / пароль из ТЗ (в коде как Base64 header).  
**Для GpsService** используйте `saveLocationConfigurationWithBearer(host, uuid, order, interval, accessToken)`.  
**Рекомендуется** не полагаться на default Basic — передавать auth явно.

---

## 11.2. Коды разрешений

### `hasRequiredPermissions()` — жёсткая проверка «Always»

| Код | iOS | Android |
|-----|-----|---------|
| `"1"` | `authorizedAlways` | Background location (+ fine) |
| `"2"` | denied/restricted / only whenInUse | Fine есть, background нет |
| `"3"` | notDetermined / services off | Services off / fine нет |

### `getLocationPermissionStatus()` — мягче (для foreground logger)

| Код | iOS | Android |
|-----|-----|---------|
| `"1"` | Always **или** WhenInUse | Fine или background |
| `"2"` | denied/restricted | нет fine |
| `"3"` | notDetermined / off | services off |

### `requestLocationPermission()`

- iOS: `requestAlwaysAuthorization`, Promise true/reject  
- Android: runtime dialog Fine (+ coarse), через `PermissionAwareActivity`

### `requestForegroundServicePermission()`

- iOS: сразу `true`  
- Android 33+: `POST_NOTIFICATIONS`  
- Ниже 33: `true`

---

## 11.3. События `onGeoWorkerEvent`

Подписка: `subscribeToEvents` / `NativeEventEmitter(LocationTracking)`.

| type | Поля | Источник |
|------|------|----------|
| `LOCATION_SENT` | latitude, longitude, timestamp | Успешная отправка |
| `LOCATION_FAILED` | message | Ошибка/очередь |
| `LOCATION_SERVICES_DISABLED` | — | null location в рейсовом пути |

На Android дополнительно локальные notifications через `GeoNotificationHelper`.

---

## 11.4. Throttle и backoff (continuous)

| Параметр | Значение |
|----------|----------|
| Interval | `updateIntervalMinutes * 60_000` ms |
| Lock | `isRequestInProgress` |
| Backoff при fail | `lastSent = now - interval + 30_000` |

Первый send после `startLocationService`: `lastSent` очищен → сразу можно.

---

## 11.5. Offline queue

Хранится JSON-массивом payload'ов в storage (`offline_queue`).  
`flushOfflineQueue` / `initializeAndSyncOnAppStart` пытаются отправить оставшееся.

---

## 11.6. Schedule state в JS

Натив отдаёт `-1` вместо null. JS нормализует:

```ts
normalizeScheduleState(raw) // -1 → null
```

Поля после нормализации:

- `isTrackingActive: boolean`
- `lastSentTimestamp: number | null`
- `nextScheduledTimestamp: number | null`
