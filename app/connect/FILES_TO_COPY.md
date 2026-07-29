# Файлы для копирования в RN-хост

Относительные пути от корня `TranslineGeoWorker`.

## JS

```
src/native/index.ts
src/native/LocationTracker.ts
src/native/LocationTrackerService.ts
src/native/SystemBars.ts
```

→ в хост: `src/native/geoworker/` (или npm path)

## Android (Kotlin мост)

```
app/androidApp/src/main/kotlin/org/transline/geoworker/GeoWorkerPackage.kt
app/androidApp/src/main/kotlin/org/transline/geoworker/GeoWorkerRuntime.kt
app/androidApp/src/main/kotlin/org/transline/geoworker/LocationTrackerModule.kt
app/androidApp/src/main/kotlin/org/transline/geoworker/SystemBarsModule.kt
app/androidApp/src/main/kotlin/org/transline/geoworker/AndroidLocationProvider.kt
app/androidApp/src/main/kotlin/org/transline/geoworker/AndroidNetworkChecker.kt
app/androidApp/src/main/kotlin/org/transline/geoworker/LocationForegroundService.kt
app/androidApp/src/main/kotlin/org/transline/geoworker/BootReceiver.kt
app/androidApp/src/main/kotlin/org/transline/geoworker/LocationServiceController.kt
app/androidApp/src/main/kotlin/org/transline/geoworker/GeoNotificationHelper.kt
```

→ `android/app/src/main/java/org/transline/geoworker/`

## Android (KMP library — не копировать исходники, подключить Gradle)

```
app/shared/   → project(':geoworker-shared')
core/         → project(':geoworker-core')
```

## iOS (мост)

```
app/iosApp/iosApp/LocationTrackerModule.swift
app/iosApp/iosApp/LocationTrackerModule.m
app/iosApp/iosApp/IOSNetworkChecker.swift
app/iosApp/iosApp/IOSNotificationHelper.swift
```

## iOS (бинарник)

```
app/shared/build/XCFrameworks/release/SharedLocationTracker.xcframework
```

Сборка: `make build-xcframework`

## Патчи хоста (только diff)

```
app/connect/patches/android/*.patch
app/connect/patches/ios/*.patch
```
