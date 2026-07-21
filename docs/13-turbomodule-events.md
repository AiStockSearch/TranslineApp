# План 13 — TurboModule Spec + Event Emitter (JS)

## 13.1. Файлы Spec

| Файл | Модуль | Codegen |
|------|--------|---------|
| [`src/native/NativeLocationTracking.ts`](../src/native/NativeLocationTracking.ts) | `LocationTracking` | да (`Native*` prefix) |
| [`src/native/NativeSystemBars.ts`](../src/native/NativeSystemBars.ts) | `SystemBars` | да |
| [`package.json` → `codegenConfig`](../package.json) | `TranslineGeoWorkerSpec` | `jsSrcsDir: ./src/native` |

## 13.2. Методы в Spec `LocationTracking`

Все Promise-методы из текущего моста + события:

- continuous: save / saveWithAuth / start / stop / isRunning / permissions…
- trip: getCurrentLocation, schedule, startTrip, complete…
- **EventEmitter:** `readonly onGeoWorkerEvent`
- **для NativeEventEmitter:** `addListener` / `removeListeners`

## 13.3. Event Emitter в JS

Натив шлёт **один** канал: `onGeoWorkerEvent` (поле `type` внутри).

Слой [`GeoWorkerEvents.ts`](../src/native/GeoWorkerEvents.ts):

| API | Назначение |
|-----|------------|
| `subscribeToEvents(cb)` | все события |
| `subscribeToGeoWorkerHandlers({ onLocationSent, … })` | разнос по типам |
| `useGeoWorkerEvents(handlers)` | хук на время жизни компонента |
| `subscribeToEventsPreferTurbo(cb)` | New Arch EventEmitter → fallback NativeEventEmitter |

Типы событий:

- `LOCATION_SENT`
- `LOCATION_FAILED`
- `LOCATION_SERVICES_DISABLED`
- `PERMISSION_DENIED`

Пример: [`app/connect/templates/js/events.example.tsx`](../app/connect/templates/js/events.example.tsx)

```tsx
useGeoWorkerEvents({
  onEvent: (e) => console.log(e.type, e),
  onLocationSent: (e) => { /* UI */ },
  onLocationFailed: (e) => { /* toast */ },
});
```

## 13.4. Android stub для emitter

В `LocationTrackerModule` добавлены no-op:

```kotlin
@ReactMethod fun addListener(eventName: String)
@ReactMethod fun removeListeners(count: Int)
```

Без них `NativeEventEmitter` на Android падает.

## 13.5. Codegen в RN-хосте

1. Подключите пакет (`file:../TranslineGeoWorker` или скопируйте `src/native` + `codegenConfig` в свой package.json).
2. Включите New Architecture (опционально).
3. Сборка сгенерирует `NativeLocationTrackingSpec` в `org.transline.geoworker`.
4. Следующий шаг (отдельная задача): унаследовать `LocationTrackerModule` от сгенерированного Spec и вызывать `emitOnGeoWorkerEvent` вместо `RCTDeviceEventEmitter`.

До миграции класса на Spec **текущий bridge + DeviceEventEmitter уже работает** с `useGeoWorkerEvents`.

## 13.6. Резолв модуля в JS

```ts
const LocationTracking =
  NativeLocationTracking /* Turbo */ ?? NativeModules.LocationTracking /* Bridge */;
```
