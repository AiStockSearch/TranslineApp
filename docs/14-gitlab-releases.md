# План 14 — GitLab Releases + CI

Раздача `@transline/geoworker` сторонним RN-приложениям через **GitLab Package Registry + Release** (`.tgz` = JS + AAR + XCFramework + native bridge).

В одном пакете: **геомониторинг** (`LocationTracking` / FGS) и **Notify Manager** (`NotifyApp` / `NotifyRouter`). Autolinking подключает оба модуля через `GeoWorkerPackage`.

---

## 14.1. Что собирает CI

Pipeline: [`.gitlab-ci.yml`](../.gitlab-ci.yml)

| Job | Что делает |
|-----|------------|
| `pack` (macOS) | `:core` / `:app:shared` AAR + XCFramework + `scripts/pack-npm.sh` → `dist/*.tgz` |
| `publish` (Linux) | Upload в Generic Package Registry + `release-cli` → GitLab Release |

Триггер Release: push тега `v*` (например `v0.1.0`).  
Ручной прогон (Web UI) собирает артефакт без создания Release.

### Runner

XCFramework собирается только на **macOS**. В `.gitlab-ci.yml` по умолчанию tag `macos` (self-hosted).  
Для GitLab.com SaaS замените tags на `saas-macos-medium-m1` (см. комментарии в CI-файле).

---

## 14.2. Локальная сборка и публикация (Makefile)

```bash
export GITLAB_TOKEN=glpat-...          # api + write_package_registry + write_repository
export GITLAB_PROJECT_ID=12345         # Settings → General → Project ID
export GITLAB_HOST=gitlab.example.com  # если не gitlab.com

# одной командой: AAR + XCFramework → .tgz → Package Registry + Release
make release VERSION=0.1.0

# по шагам:
make build-aar
make build-xcframework
make pack-npm VERSION=0.1.0
make release-only VERSION=0.1.0
```

После изменений shared/notify (iOS delegate, categories и т.п.) перед релизом снова выполните **`make pack-npm`**, чтобы в tarball попал свежий XCFramework/AAR.

В Release попадут:
- `transline-geoworker-VERSION.tgz` (основной npm-пакет)
- `geoworker-shared.aar`, `geoworker-core.aar`
- `SharedLocationTracker.xcframework.zip`

Скрипт: [`scripts/publish-gitlab-release.sh`](../scripts/publish-gitlab-release.sh).

---

## 14.3. Как выпустить через CI

```bash
# 1. При необходимости обновите version в package.json
# 2. Закоммитьте
git tag v0.1.0
git push origin v0.1.0
```

GitLab CI соберёт пакет и создаст Release со ссылкой на Generic Package.

URL пакета:

```text
https://gitlab.example.com/api/v4/projects/<PROJECT_ID>/packages/generic/geoworker/0.1.0/transline-geoworker-0.1.0.tgz
```

`PROJECT_ID` — Settings → General → Project ID (или `$CI_PROJECT_ID` в CI).  
Хост — ваш GitLab (`gitlab.com` или self-hosted).

---

## 14.4. Как ставить в стороннем приложении

```bash
npm i https://gitlab.example.com/api/v4/projects/PROJECT_ID/packages/generic/geoworker/0.1.0/transline-geoworker-0.1.0.tgz
```

или в `package.json`:

```json
{
  "dependencies": {
    "@transline/geoworker": "https://gitlab.example.com/api/v4/projects/PROJECT_ID/packages/generic/geoworker/0.1.0/transline-geoworker-0.1.0.tgz"
  }
}
```

### iOS

```ruby
# ios/Podfile
pod 'TranslineGeoWorker', :path => '../node_modules/@transline/geoworker/ios'
```

```bash
cd ios && pod install
```

Info.plist: location usage strings + `UIBackgroundModes: location`.

### Android

Autolinking через `react-native.config.js` пакета (`GeoWorkerPackage` → `LocationTrackerModule` + `NotifyAppModule` + `SystemBarsModule`).  
Manifest merge: geo permissions + FGS + BootReceiver + `NotifyActionReceiver` + alarm permissions.

Если autolinking не подхватил:

```kotlin
add(GeoWorkerPackage())
```

### JS

```ts
import {
  startLocationService,
  useGeoWorkerEvents,
  useSystemBarStyle,
  showNotify,
  handleRemoteNotify,
  NotifyRouter,
  requestNotifyPermission,
} from '@transline/geoworker';
```

Notify: bootstrap и Host go-live — [docs/15-notify-manager.md](15-notify-manager.md) §15.9, пример [`app/connect/templates/js/notify-bootstrap.example.tsx`](../app/connect/templates/js/notify-bootstrap.example.tsx).

---

## 14.5. Private проект

Generic Package из private repo требует токен.

```bash
# Скачать вручную
curl --header "PRIVATE-TOKEN: $GITLAB_TOKEN" -L \
  "$URL" -o transline-geoworker.tgz
npm i ./transline-geoworker.tgz
```

Или в URL (осторожно с утечкой в логах):

```text
https://gitlab-ci-token:${GITLAB_TOKEN}@gitlab.example.com/api/v4/projects/PROJECT_ID/packages/generic/geoworker/0.1.0/transline-geoworker-0.1.0.tgz
```

Токен: Project/Group Access Token или Personal Access Token с `read_api` / `read_package_registry`.

---

## 14.6. Обновление версии у хоста

Сменить URL на новый version (`0.2.0`) → `yarn` / `npm i` → `pod install`.
