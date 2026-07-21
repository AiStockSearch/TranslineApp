# Android — интеграция

## 1. Файлы моста (скопировать в хост)

Из `TranslineGeoWorker/app/androidApp/src/main/kotlin/org/transline/geoworker/`  
в `android/app/src/main/java/org/transline/geoworker/` (или kotlin):

| Файл | Назначение |
|------|------------|
| `GeoWorkerPackage.kt` | ReactPackage (должен включать `NotifyAppModule`) |
| `LocationTrackerModule.kt` | `NativeModules.LocationTracking` |
| `SystemBarsModule.kt` | `NativeModules.SystemBars` |
| `NotifyAppModule.kt` | `NativeModules.NotifyApp` (KMP Notify Manager) |
| `AndroidLocationProvider.kt` | Fused continuous GPS |
| `AndroidNetworkChecker.kt` | сеть |
| `LocationForegroundService.kt` | FGS |
| `BootReceiver.kt` | BOOT_COMPLETED |
| `LocationServiceController.kt` | старт из FCM |
| `GeoNotificationHelper.kt` | geo success/offline нотификации |

Пакет оставьте `org.transline.geoworker`.  
KMP-классы notify (`NotifyActionReceiver` и т.д.) приходят из `:app:shared` / AAR — отдельно копировать не нужно.

В `GeoWorkerPackage.createNativeModules` должны быть:

```kotlin
LocationTrackerModule(reactContext),
SystemBarsModule(reactContext),
NotifyAppModule(reactContext),
```

## 2. Gradle

См. патчи:

- `patches/android/01-settings.gradle.patch`
- `patches/android/02-app-build.gradle.patch`

Минимум в `android/app/build.gradle`:

```gradle
implementation project(':geoworker-shared')
implementation("com.google.android.gms:play-services-location:21.2.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
implementation("io.ktor:ktor-client-okhttp:2.3.9")
```

## 3. MainApplication

```kotlin
import org.transline.geoworker.GeoWorkerPackage

override fun getPackages(): List<ReactPackage> =
    PackageList(this).packages.apply {
        add(GeoWorkerPackage())
    }
```

Полный diff: `patches/android/03-MainApplication.kt.patch`.

## 4. Manifest

Полный diff: `patches/android/04-AndroidManifest.xml.patch`.

Обязательно (гео):

- permissions: fine/coarse/background location, FGS location, boot, notifications
- `org.transline.geoworker.LocationForegroundService`
- `org.transline.geoworker.BootReceiver`

Обязательно для **Notify Manager**:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<!-- при необходимости USE_EXACT_ALARM -->

<receiver
    android:name="org.transline.geoworker.notify.NotifyActionReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="org.transline.geoworker.notify.ACTION_BUTTON" />
        <action android:name="org.transline.geoworker.notify.ACTION_OPEN" />
        <action android:name="org.transline.geoworker.notify.ACTION_SNOOZE_FIRE" />
    </intent-filter>
</receiver>
```

На Android 13+ до первого `showNotify` вызовите JS `requestNotifyPermission()` (см. [docs/15-notify-manager.md](../../../docs/15-notify-manager.md)).

## 5. Proguard (release)

```
-keep class org.transline.geoworker.** { *; }
-keep class org.transline.geoworker.tracker.** { *; }
-keep class org.transline.geoworker.notify.** { *; }
```

## 6. Проверка

```bash
cd android && ./gradlew :app:assembleDebug
npx react-native run-android
```

В Metro:

```js
import { NativeModules } from 'react-native';
console.log(!!NativeModules.LocationTracking);
console.log(!!NativeModules.NotifyApp); // true, если Notify подключён
```

JS bootstrap: [`../js/notify-bootstrap.example.tsx`](../js/notify-bootstrap.example.tsx).
