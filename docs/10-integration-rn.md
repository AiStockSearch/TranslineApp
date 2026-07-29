# План 10 — Интеграция в действующее React Native приложение

**Единый практический гайд:** [`RN-INTEGRATION.md`](./RN-INTEGRATION.md).  
Полные чеклисты и файлы: [`app/connect/`](../app/connect/README.md).

## 10.1. Порядок работ (рекомендуемый)

```
1. make build-xcframework
2. Подключить :app:shared в android/settings.gradle
3. Скопировать Android Kotlin-мост (FILES_TO_COPY)
4. Применить android patches (MainApplication, Manifest, build.gradle)
5. Podfile + pod install (или Embed XCFramework)
6. Скопировать iOS Swift/ObjC мост
7. Info.plist location keys
8. Скопировать src/native в JS
9. npm i @react-native-community/geolocation (если нужен foreground logger)
10. Вставить бутстрап из Плана 06 (комбинация A или B)
11. Проверить NativeModules + start/stop на устройстве
```

## 10.2. Android — минимальный diff в голове

```kotlin
// MainApplication
add(GeoWorkerPackage())
```

```xml
<!-- Manifest: permissions + LocationForegroundService + BootReceiver -->
```

```gradle
implementation project(':geoworker-shared')
implementation("com.google.android.gms:play-services-location:21.2.0")
// + ktor / coroutines-play-services как в патче
```

## 10.3. iOS — минимальный diff в голове

```ruby
pod 'TranslineGeoWorker', :path => '.../app/connect'
```

+ Capabilities: Background Modes → Location updates  
+ Always usage strings в Info.plist  

## 10.4. JS — минимальный wiring

```ts
import {
  startLocationService,
  stopLocationService,
  requestLocationPermission,
  hasRequiredPermissions,
  useSystemBarStyle,
  useForegroundLocationLogger,
  subscribeToEvents,
} from './native/geoworker';
```

Пример компонента: `app/connect/templates/js/usage.example.tsx`.

## 10.5. Проверка линковки

```ts
import { NativeModules, Platform } from 'react-native';

console.log('LocationTracking', !!NativeModules.LocationTracking);
console.log('SystemBars', Platform.OS === 'android' ? !!NativeModules.SystemBars : 'n/a');
```

Если `false` — мост не в пакетах / не в Pod / wrong getName.

## 10.6. FCM (опционально)

На Android из notification handler:

```kotlin
LocationServiceController.startLocationService(context)
// или stopLocationService(context)
```

Конфиг (endpoint/uuid) должен быть заранее сохранён через `saveLocationConfiguration*`.

## 10.7. New Architecture / TurboModules

Текущий мост — **классический** NativeModule / RCTEventEmitter.  
Для Fabric/Turbo нужна отдельная спецификация (не входит в текущий scope). На Bridge mode работает.
