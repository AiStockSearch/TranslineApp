# План 09 — Patch-package и патчи интеграции

## 9.1. Два разных смысла «патчей»

| Что | Где | Зачем |
|-----|-----|--------|
| **Патчи хоста RN** | `app/connect/patches/**` | Изменения *вашего* приложения при подключении GeoWorker |
| **patch-package (npm)** | `patches/*.patch` в RN-проекте | Фикс `node_modules` пакетов после install |

Этот план покрывает **оба**.

---

## 9.2. Патчи TranslineGeoWorker (`app/connect/patches`)

Формат: **unified diff** (`--- a/...` / `+++ b/...`), как у patch-package.

### Android

| Файл | Меняет |
|------|--------|
| `01-settings.gradle.patch` | include `:geoworker-shared` / `:geoworker-core` |
| `02-app-build.gradle.patch` | dependencies (shared, location, ktor) |
| `03-MainApplication.kt.patch` | `add(GeoWorkerPackage())` |
| `04-AndroidManifest.xml.patch` | permissions + FGS + BootReceiver + **NotifyActionReceiver** |

### iOS

| Файл | Меняет |
|------|--------|
| `01-Podfile.patch` | `pod 'TranslineGeoWorker'` |
| `02-Info.plist.patch` | location usage + background |
| `03-AppDelegate.mm.patch` | комментарии / hooks cold start |

Индекс: `app/connect/patch-package.index.js`.

### Авто-применение (скрипт)

Из корня **хост RN**:

```bash
# GEOWORKER_ROOT или --root = путь к этому репозиторию
export GEOWORKER_ROOT=/path/to/TranslineGeoWorker

node "$GEOWORKER_ROOT/scripts/apply-geoworker-patches.js" \
  --root "$GEOWORKER_ROOT" \
  --platform all          # android | ios | all
  # --dry-run             # только проверка
```

Из mono-репо TranslineGeoWorker:

```bash
make apply-host-patches HOST=/path/to/YourReactNativeApp
make apply-host-patches HOST=../MyApp PLATFORM=android DRY_RUN=1
```

Скрипт: [`scripts/apply-geoworker-patches.js`](../scripts/apply-geoworker-patches.js).  
Читает [`app/connect/patch-package.index.js`](../app/connect/patch-package.index.js), вызывает `patch -p1 --forward`.  
Уже применённые hunk → SKIP; fail → exit 1 + ссылка на `templates/*/INTEGRATION.md`.

### Как применить вручную

```bash
cd YourReactNativeApp

# пример
patch -p1 < ../TranslineGeoWorker/app/connect/patches/android/03-MainApplication.kt.patch
```

Если hunk не лёг (другая версия RN) — **не форсируйте**: откройте `templates/*/INTEGRATION.md` и внесите изменения руками.

### Что патчи НЕ делают

Они **не копируют** Kotlin/Swift исходники моста и **не собирают** XCFramework.  
При npm-пакете (`make pack-npm`) мост/AAR/XCFramework уже в tarball — патчи правят только **хост** (Manifest, MainApplication, Podfile, …).

---

## 9.3. npm `patch-package` в хост-приложении

Используйте, когда нужно зафиксировать правки в зависимости из `node_modules` (например, временный фикс geolocation).

### Установка

```bash
cd YourReactNativeApp
npm i -D patch-package postinstall-postinstall
```

`package.json`:

```json
{
  "scripts": {
    "postinstall": "patch-package"
  }
}
```

### Создать патч

```bash
# 1. Правите файлы в node_modules/some-package/...
# 2. Генерируете патч:
npx patch-package some-package
# → patches/some-package+x.y.z.patch
```

### Применить

Автоматически на каждом `npm install` / `yarn` через `postinstall`.

### Связь с GeoWorker

Варианты подключения:

1. **npm tarball** `@transline/geoworker` (`make pack-npm` / GitLab Package Registry)  
2. **Копирование** `src/native` + native bridge  
3. **file:** зависимость на монорепо  

Патчи из `app/connect/patches` применяют к **android/ios хоста** скриптом выше (не путать с npm `patch-package` на `node_modules`).

### Рекомендуемый скрипт хоста

```json
{
  "scripts": {
    "geoworker:patch": "node ../TranslineGeoWorker/scripts/apply-geoworker-patches.js --root=../TranslineGeoWorker",
    "postinstall": "patch-package"
  }
}
```

Поправьте относительный путь к TranslineGeoWorker / к `node_modules/@transline/geoworker` при необходимости.

---

## 9.4. Чеклист «патч прошёл»

- [ ] `NativeModules.LocationTracking` не `undefined`  
- [ ] Android: в logcat нет missing service / permission для FGS  
- [ ] iOS: Always dialog / Background Mode location  
- [ ] JS `startLocationService` не кидает `PERMISSION_DENIED` при выданных правах  

---

## 9.5. Откат

```bash
# git в хосте
git checkout -- android/app/src/main/AndroidManifest.xml
git checkout -- ios/Podfile
# …
```

Для npm patch-package: удалите файл из `patches/` и переустановите пакет.
