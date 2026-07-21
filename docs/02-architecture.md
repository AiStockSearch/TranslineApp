# План 02 — Архитектура

## 2.1. Слои (сверху вниз)

```
┌─────────────────────────────────────────────────────────────┐
│  React Native JS                                            │
│  src/native/LocationTracker.ts  +  SystemBars.ts            │
│  хуки: useStartLocationService, useForegroundLocationLogger │
│        useSystemBarStyle                                    │
└───────────────────────────┬─────────────────────────────────┘
                            │ NativeModules.LocationTracking
                            │ NativeModules.SystemBars
                            │ event: onGeoWorkerEvent
┌───────────────────────────▼─────────────────────────────────┐
│  Platform bridge                                            │
│  Android: LocationTrackerModule + GeoWorkerPackage + FGS    │
│  iOS:     LocationTrackerModule.swift/.m                    │
└───────────────────────────┬─────────────────────────────────┘
                            │ LocationTrackerController
┌───────────────────────────▼─────────────────────────────────┐
│  KMP commonMain (app/shared)                                │
│  Controller · Repository · Storage · Filter · Endpoint util │
└───────────────────────────┬─────────────────────────────────┘
                            │
        ┌───────────────────┴───────────────────┐
        ▼                                       ▼
 PlatformLocationProvider              HTTP (Ktor)
 iOS CLLocation / Android Fused        POST /api/coordinates
```

## 2.2. Поток continuous-отправки

```
GPS update
  → PlatformLocationProvider.startTracking callback
  → Controller.onLocationUpdate
      → shouldSend? (active + !inProgress + interval elapsed)
      → sendOrQueueLocation
          → 200: keep lastSent, notify LOCATION_SENT
          → fail: backoff lastSent, queue, LOCATION_FAILED
```

## 2.3. Поток рейса

```
startTrip(loadingTime)
  → nextScheduled = loadingTime - 1h
  → tracking_active = true
executePendingOrScheduledTracking / initializeAndSyncOnAppStart
  → if now >= nextScheduled → getCurrentLocation → send
  → nextScheduled = now + 30min
completeTripAfterModeration
  → последняя точка → clearTripState (keep registration) → stop GPS
```

## 2.4. Android: процесс и reboot

```
startLocationService (RN)
  → GeoWorkerRuntime.controller (singleton)
  → Controller.startLocationService
  → LocationForegroundService.start
       → same controller.resumeLocationServiceIfActive

BOOT_COMPLETED
  → BootReceiver
  → if tracking_active → FGS.start → same GeoWorkerRuntime
```

## 2.5. Где какой код

| Слой | Каталог |
|------|---------|
| KMP | `app/shared/src/commonMain/.../tracker/` |
| Android storage/provider expect/actual | `app/shared/src/androidMain/` |
| iOS storage/provider | `app/shared/src/iosMain/` |
| Android RN + FGS | `app/androidApp/.../org/transline/geoworker/` |
| iOS RN | `app/iosApp/iosApp/LocationTrackerModule.*` |
| JS | `src/native/` |
| Connect | `app/connect/` |
