# TranslineGeoWorker

Kotlin Multiplatform модуль геомониторинга водителя + React Native bridge (Android/iOS) + JS facade.

## Подключение в React Native

Практический гайд (сборка, нативные модули, Android/iOS файлы, хуки, пример, отладка):

**→ [docs/RN-INTEGRATION.md](./docs/RN-INTEGRATION.md)**

Патчи и чеклисты хоста: **[app/connect/](./app/connect/README.md)**

## Документация (планы)

**Полная документация разбита на планы:** → **[docs/README.md](./docs/README.md)**

| # | План |
|---|------|
| 01 | [Обзор](./docs/01-overview.md) |
| 02 | [Архитектура](./docs/02-architecture.md) |
| 03 | [KMP API — зачем каждая функция](./docs/03-kmp-api.md) |
| 04 | [RN Native bridge](./docs/04-rn-bridge-api.md) |
| 05 | [JS/TS API](./docs/05-js-api.md) |
| 06 | [Хуки и комбинации](./docs/06-hooks-and-combos.md) |
| 07 | [Сборка Android](./docs/07-build-android.md) |
| 08 | [Сборка iOS](./docs/08-build-ios.md) |
| 09 | [Patch-package и патчи](./docs/09-patch-package.md) |
| 10 | [Интеграция в RN](./docs/10-integration-rn.md) |
| 11 | [События, права, сеть](./docs/11-events-permissions-network.md) |
| 12 | [Сценарии](./docs/12-scenarios.md) |
| 13 | [TurboModule Spec + Events](./docs/13-turbomodule-events.md) |
| 14 | [GitLab Releases + CI](./docs/14-gitlab-releases.md) |
| 15 | [Notify Manager (KMP)](./docs/15-notify-manager.md) |

Быстрые патчи/чеклисты для хоста: **[app/connect/](./app/connect/README.md)**

## Раздача сторонним приложениям

Локально (AAR + XCFramework → GitLab Release):

```bash
export GITLAB_TOKEN=glpat-...
export GITLAB_PROJECT_ID=12345
export GITLAB_HOST=gitlab.example.com   # если не gitlab.com

make release VERSION=0.1.0
```

Или через CI: `git tag v0.1.0 && git push origin v0.1.0`

Установка:

```bash
npm i https://gitlab.example.com/api/v4/projects/PROJECT_ID/packages/generic/geoworker/0.1.0/transline-geoworker-0.1.0.tgz
```

Подробности: [docs/14-gitlab-releases.md](./docs/14-gitlab-releases.md).

## Структура репозитория

* [`app/shared`](./app/shared) — KMP бизнес-логика (AAR + `SharedLocationTracker.xcframework`)
* [`app/androidApp`](./app/androidApp) — Android RN-мост, FGS, sample
* [`app/iosApp`](./app/iosApp) — iOS RN-мост, sample
* [`src/native`](./src/native) — JS/TS API и хуки
* [`app/connect`](./app/connect) — патчи подключения к RN
* [`core`](./core) — общий KMP core
* [`server`](./server) — отдельный Ktor server (не coordinates API продукта)

## Сборка (шпаргалка)

```bash
make test
make build-apk
make build-xcframework
make build-all
```

- Android sample: `./gradlew :app:androidApp:assembleDebug`
- Shared tests: `./gradlew :app:shared:testAndroidHostTest`
- iOS tests: `./gradlew :app:shared:iosSimulatorArm64Test`

## Running (legacy KMP sample notes)

Use the run configurations provided by the IDE toolbar, or:

- Android app: `./gradlew :app:androidApp:assembleDebug`
- Server: `./gradlew :server:run`
- iOS app: open [`app/iosApp`](./app/iosApp) in Xcode

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html).
