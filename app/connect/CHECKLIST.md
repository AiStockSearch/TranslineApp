# Чеклист интеграции TranslineGeoWorker → RN

## Общее

- [ ] Собран XCFramework: `make build-xcframework` (после изменений shared/notify — пересобрать)
- [ ] Android shared доступен как `project(':app:shared')` или AAR
- [ ] Скопирован / подключен JS: `src/native` → LocationTracking + NotifyApp API
- [ ] Peer deps: `react`, `react-native` (опц. `@react-native-community/geolocation`)

## Android

- [ ] `settings.gradle` — include `:app:shared` (или maven)
- [ ] `android/app/build.gradle` — зависимость shared + play-services-location + ktor
- [ ] Скопированы Kotlin-классы моста (`GeoWorkerPackage`, `LocationTrackerModule`, `NotifyAppModule`, FGS, …)
- [ ] `GeoWorkerPackage` регистрирует `NotifyAppModule`
- [ ] `MainApplication` → `packages.add(GeoWorkerPackage())`
- [ ] Manifest: permissions (location, FGS, boot, notifications)
- [ ] Manifest: `LocationForegroundService` + `BootReceiver`
- [ ] Manifest: `NotifyActionReceiver` + `POST_NOTIFICATIONS` + `SCHEDULE_EXACT_ALARM` (Notify Manager)
- [ ] Runtime: Always / background location запрошены в UI хоста
- [ ] Runtime: `requestNotifyPermission()` до первого `showNotify` (Android 13+)
- [ ] `NativeModules.LocationTracking` виден из JS (Hermes/bridge)
- [ ] `NativeModules.NotifyApp` виден из JS (если используете кастомные пуши)

## iOS

- [ ] XCFramework в Embed & Sign / Pod
- [ ] `LocationTrackerModule.swift` + `.m` в таргете
- [ ] `NotifyAppModule.swift` + `.m` в **Target Membership** (Notify Manager)
- [ ] Bridging Header / Swift в RN target
- [ ] `Info.plist`: 3× location usage + `UIBackgroundModes=location`
- [ ] Background Modes → Location updates в Signing & Capabilities
- [ ] (опц.) Push Notifications capability для remote notify
- [ ] `pod install` + clean build
- [ ] `NativeModules.LocationTracking` виден из JS
- [ ] `NativeModules.NotifyApp` виден из JS (если используете)

## Notify JS / навигация / FCM

- [ ] `NotifyRouter.register(...)` → `navigationRef.navigate`
- [ ] `NotifyRouter.attach()` (или [`templates/js/notify-bootstrap.example.tsx`](templates/js/notify-bootstrap.example.tsx))
- [ ] (опц.) `NotifyRouter.getReport()` / `subscribeReport` — отчёт по pattern (§15.11)
- [ ] (опц.) хаб агрегации: `NotifyRouter.register('app://sbc', …)` + `params.ids` (§15.12)
- [ ] FCM/APNs: `geo_*` → geo; `notify_app` / `tl_notify=1` → `handleRemoteNotify` (`shouldHandleRemoteNotify`); иначе Firebase/host (см. docs/15 §15.10)
- [ ] Push `data` содержит `id`, `title` (+ `body` / `actions` / `deepLink` по необходимости)

## Проверка

```ts
import { NativeModules } from 'react-native';
console.log(!!NativeModules.LocationTracking); // true
console.log(!!NativeModules.SystemBars);       // true на Android
console.log(!!NativeModules.NotifyApp);        // true если Notify Manager подключён
```

- [ ] `requestLocationPermission()` показывает диалог
- [ ] `startLocationService(host, uuid, '', 1)` → на Android появляется FGS-нотификация
- [ ] Уход в фон / reboot (Android) — трекинг восстанавливается при `tracking_active`
- [ ] `useSystemBarStyle()` на Android API < 32 не падает
- [ ] `showNotify({ id, title, body, actions })` показывает системную нотификацию
- [ ] Tap «Перейти» / «Прочитать» → экран через NotifyRouter
- [ ] `cancel` / `snooze` работают

Полный гайд Notify: [`docs/15-notify-manager.md`](../../docs/15-notify-manager.md) §15.9 Host go-live.
