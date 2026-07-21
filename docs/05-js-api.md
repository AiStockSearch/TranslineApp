# План 05 — JS/TS API (`src/native`)

Импорт:

```ts
import {
  startLocationService,
  useStartLocationService,
  useForegroundLocationLogger,
  useSystemBarStyle,
  // …
} from './native'; // или путь к src/native
```

Точка входа: `src/native/index.ts`.

---

## 5.1. Утилиты

| Функция | Зачем |
|---------|--------|
| `getCoordinatesEndpoint(host)` | Host → `…/api/coordinates` |
| `getDriverUuid(user)` | `user_uuid \|\| driver_uuid \|\| uuid \|\| id` |
| `normalizeScheduleState(raw)` | Нативные `-1` → `null` для TS |

---

## 5.2. Continuous API (зеркало ТЗ)

| Функция | Зачем | Натив |
|---------|--------|-------|
| `startLocationService(api, uuid, order?, interval?)` | Старт фона | `startLocationService` |
| `saveLocationConfiguration(...)` | Конфиг без старта | same |
| `saveLocationConfigurationWithAuth(..., user?, pass?)` | Конфиг + Basic Auth из JS | `…WithAuth` |
| `saveLocationConfigurationWithBearer(..., accessToken?)` | Конфиг + `Authorization: Bearer` (GpsService) | `…WithBearer` |
| `stopLocationService()` | Стоп | same |
| `isLocationServiceRunning()` | Флаг | same |
| `requestLocationPermission()` | Диалог | same |
| `requestForegroundServicePermission()` | Нотификации FGS | same |
| `hasRequiredPermissions()` | `"1"|"2"|"3"` Always | same |
| `getLocationPermissionStatus()` | `"1"|"2"|"3"` мягче | same |

### Коды `LocationPermissionStatus`

| Код | Смысл |
|-----|--------|
| `"1"` | Разрешено (для hasRequired — Always/background; для getStatus — также whenInUse) |
| `"2"` | Отклонено / restricted |
| `"3"` | Не определено / гео выключена |

---

## 5.3. Рейс / sync

| Функция | Зачем |
|---------|--------|
| `getCurrentLocation()` | One-shot |
| `openGpsSettings()` | Настройки ОС |
| `initializeAndSyncOnAppStart()` | Старт приложения |
| `getScheduleState()` | UI расписания |
| `checkAndSyncTracking()` | Принудительный тик |
| `startTrip(loadingTimeEpochMs)` | Назначение рейса |
| `completeTripAfterModeration()` | Завершение |

---

## 5.4. События

### `subscribeToEvents(listener) → unsubscribe`

Слушает `onGeoWorkerEvent`.

| `event.type` | Когда |
|--------------|--------|
| `LOCATION_SENT` | Успех (+ lat/lon/timestamp) |
| `LOCATION_FAILED` | Ошибка (+ message) |
| `LOCATION_SERVICES_DISABLED` | GPS недоступен |
| `PERMISSION_DENIED` | (зарезервировано) |

---

## 5.5. SystemBars

| API | Зачем |
|-----|--------|
| `setSystemBarsStyle(light)` | Ручной стиль (только Android) |
| `useSystemBarStyle()` | На mount: если Android API &lt; 32 → `setSystemBarsStyle(false)` |

---

## 5.6. Deprecated

`LocationTrackerService` — объект-агрегатор тех же функций для старых импортов.  
Предпочтительно named exports.
