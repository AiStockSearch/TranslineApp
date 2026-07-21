# Подключение TranslineGeoWorker к React Native

> **Полная инструкция для RN-хоста** (сборка, модули, Android/iOS файлы, хуки, пример, отладка):  
> **[../../docs/RN-INTEGRATION.md](../../docs/RN-INTEGRATION.md)**
>
> Детальные планы (API, сценарии, TurboModule): **[../../docs/README.md](../../docs/README.md)**

Папка описывает **все изменения хост-приложения** при интеграции модуля геомониторинга.

```
app/connect/
├── README.md                 ← вы здесь
├── CHECKLIST.md              ← чеклист интеграции
├── package.json.snippet      ← npm-зависимости
├── react-native.config.js    ← autolinking (опционально)
├── patches/                  ← unified diff «как patch-package»
│   ├── android/
│   │   ├── 01-settings.gradle.patch
│   │   ├── 02-app-build.gradle.patch
│   │   ├── 03-MainApplication.kt.patch
│   │   └── 04-AndroidManifest.xml.patch
│   └── ios/
│       ├── 01-Podfile.patch
│       ├── 02-Info.plist.patch
│       └── 03-AppDelegate.mm.patch
├── templates/                ← готовые фрагменты для копирования
│   ├── android/
│   ├── ios/
│   └── js/                   ← usage.example + notify-bootstrap.example
└── TranslineGeoWorker.podspec
```

Notify Manager (кастомные пуши): [docs/15-notify-manager.md](../../docs/15-notify-manager.md) §15.9; шаблон [`templates/js/notify-bootstrap.example.tsx`](templates/js/notify-bootstrap.example.tsx).

## Архитектура после интеграции

```
RN JS (src/native) ──► NativeModules.LocationTracking / SystemBars / NotifyApp
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
     Android GeoWorkerPackage          iOS LocationTrackerModule + NotifyAppModule
              │                               │
              ▼                               ▼
     :app:shared (KMP AAR)           SharedLocationTracker.xcframework
```

## Быстрый старт

### 0. Собрать артефакты KMP

```bash
# из корня TranslineGeoWorker
make build-xcframework   # iOS → app/shared/build/XCFrameworks/release/
./gradlew :app:shared:assembleRelease   # Android AAR (или подключить как project)
```

### 1. JS

Скопируйте [`../../src/native`](../../src/native) в хост, например в `src/native/geoworker/`,  
или укажите path-зависимость. См. [`package.json.snippet`](package.json.snippet) и [`templates/js/usage.example.tsx`](templates/js/usage.example.tsx).

```ts
import {
  startLocationService,
  useSystemBarStyle,
  useForegroundLocationLogger,
} from './native/geoworker';
```

Native module name: **`LocationTracking`** (как в старом приложении).

### 2. Android

1. Подключите KMP shared + код моста (`GeoWorkerPackage`, FGS, …) — см. патчи в `patches/android/`.
2. Зарегистрируйте `GeoWorkerPackage()` в `MainApplication`.
3. Добавьте permissions / service / receiver в `AndroidManifest.xml`.

Подробно: [`templates/android/INTEGRATION.md`](templates/android/INTEGRATION.md).

### 3. iOS

1. Положите `SharedLocationTracker.xcframework` (или pod).
2. Добавьте нативные файлы моста (`LocationTrackerModule.swift/.m`, helpers).
3. `Info.plist` — ключи локации + `UIBackgroundModes: location`.
4. `pod install`.

Подробно: [`templates/ios/INTEGRATION.md`](templates/ios/INTEGRATION.md).

## Применение патчей (patch-package стиль)

Патчи — **unified diff относительно типичного RN 0.73+ проекта**.  
Пути вроде `android/app/...` и `ios/YourApp/...` — замените `YourApp` на имя таргета.

### Авто-apply

```bash
# из корня хост RN
node /path/to/TranslineGeoWorker/scripts/apply-geoworker-patches.js \
  --root /path/to/TranslineGeoWorker \
  --platform all

# или из TranslineGeoWorker
make apply-host-patches HOST=/path/to/YourReactNativeApp
```

Скрипт: [`../../scripts/apply-geoworker-patches.js`](../../scripts/apply-geoworker-patches.js).  
Индекс: [`patch-package.index.js`](patch-package.index.js). Документация: [docs/09-patch-package.md](../../docs/09-patch-package.md).

### Вручную

```bash
# пример ручного применения
cd YourReactNativeApp
patch -p1 < path/to/TranslineGeoWorker/app/connect/patches/android/03-MainApplication.kt.patch
```

Или скопируйте готовые блоки из `templates/` вручную (надёжнее, если структура хоста отличается).

## Что НЕ входит в патчи хоста

| Компонент | Где живёт |
|-----------|-----------|
| KMP бизнес-логика | `app/shared` |
| Android RN-мост + FGS | `app/androidApp/src/main/kotlin/org/transline/geoworker/` |
| iOS RN-мост | `app/iosApp/iosApp/LocationTrackerModule.*` |
| JS facade | `src/native/` |

Их нужно **подключить как зависимость / скопировать**, а патчи ниже — только правки **уже существующего** RN-приложения.
