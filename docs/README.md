# TranslineGeoWorker — документация

Модуль **фонового геомониторинга водителя**: сбор координат и скорости, периодическая отправка на бэкенд (`POST …/api/coordinates`), работа в фоне (iOS CoreLocation / Android Foreground Service) и в активном режиме (JS foreground logger), плюс утилита SystemBars для Android.

Документация разбита на **планы** (самостоятельные разделы). Читайте по порядку при первом знакомстве или точечно по задаче.

**Подключение к React Native (единый практический гайд):** → **[RN-INTEGRATION.md](./RN-INTEGRATION.md)**

---

## Оглавление планов

| План | Файл | О чём |
|------|------|--------|
| **RN** | [RN-INTEGRATION.md](./RN-INTEGRATION.md) | Сборка, нативные модули, Android/iOS, хуки, пример JS, отладка |
| **01** | [overview.md](./01-overview.md) | Зачем модуль, границы, что внутри репозитория |
| **02** | [architecture.md](./02-architecture.md) | Слои: KMP → Native bridge → JS → UI |
| **03** | [kmp-api.md](./03-kmp-api.md) | Каждая функция `LocationTrackerController` и связанные типы |
| **04** | [rn-bridge-api.md](./04-rn-bridge-api.md) | NativeModule `LocationTracking` / `SystemBars` (Android & iOS) |
| **05** | [js-api.md](./05-js-api.md) | Публичный JS/TS API из `src/native` |
| **06** | [hooks-and-combos.md](./06-hooks-and-combos.md) | Хуки, зависимости, **комбинации сценариев** |
| **07** | [build-android.md](./07-build-android.md) | Сборка Android (shared AAR, APK, тесты) |
| **08** | [build-ios.md](./08-build-ios.md) | Сборка iOS (XCFramework, Xcode, тесты) |
| **09** | [patch-package.md](./09-patch-package.md) | Patch-package, патчи в `app/connect`, как применять |
| **10** | [integration-rn.md](./10-integration-rn.md) | Подключение к действующему React Native приложению |
| **11** | [events-permissions-network.md](./11-events-permissions-network.md) | События, коды прав, HTTP payload, auth |
| **12** | [scenarios.md](./12-scenarios.md) | Пошаговые сценарии (рейс, continuous, boot, offline) |
| **13** | [turbomodule-events.md](./13-turbomodule-events.md) | TurboModule Spec + codegenConfig + JS Event Emitter |
| **14** | [gitlab-releases.md](./14-gitlab-releases.md) | GitLab Releases + CI (AAR + XCFramework + npm .tgz) |
| **15** | [notify-manager.md](./15-notify-manager.md) | KMP Notify Manager: show / handleRemote / cancel / snooze |

Быстрая интеграция (чеклисты и diff-патчи): [`../app/connect/`](../app/connect/README.md).

---

## Карта репозитория (кратко)

```
TranslineGeoWorker/
├── app/shared/          # KMP бизнес-логика → AAR + XCFramework
├── app/androidApp/      # Android RN-мост, FGS, sample UI
├── app/iosApp/          # iOS RN-мост, sample
├── app/connect/         # Патчи и гайды подключения к RN-хосту
├── src/native/          # JS/TS facade + хуки
├── core/                # Общий KMP core
├── server/              # Отдельный Ktor server (не часть трекера)
└── docs/                # ← эта документация
```

## Минимальный путь «хочу в прод RN»

1. **[RN-INTEGRATION.md](./RN-INTEGRATION.md)** — основной путь (сборка → мост → хуки → пример → отладка)  
2. При необходимости: план **01** + **02** — модель; **09** — patch-package; **12** — сценарии рейса/offline  

## Makefile (шпаргалка)

```bash
make test                 # все тесты
make test-android         # Android unit
make test-ios             # iOS simulator tests
make build-apk            # Release APK sample
make build-xcframework    # SharedLocationTracker.xcframework
make build-all            # APK + XCFramework
make publish              # :app:shared:publish (нужен publishing block)
```
