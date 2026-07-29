# План 07 — Сборка Android

## 7.1. Требования

- JDK 17+ (как в проекте)
- Android SDK (compileSdk из `gradle/libs.versions.toml`)
- Из корня репозитория: `./gradlew` или `make …`

## 7.2. Модули Gradle

| Project | Роль |
|---------|------|
| `:core` | Общий KMP |
| `:app:shared` | Библиотека трекера (публикуемый KMP) |
| `:app:androidApp` | Sample + RN bridge + FGS |

## 7.3. Команды

### Unit-тесты shared (host)

```bash
./gradlew :app:shared:testAndroidHostTest
# или
make test-android   # сейчас гоняет :app:androidApp:testDebugUnitTest
```

Рекомендуемая проверка логики continuous:

```bash
./gradlew :app:shared:testAndroidHostTest
```

### Сборка library (для подключения в RN)

```bash
./gradlew :app:shared:assembleRelease
# AAR: app/shared/build/outputs/aar/  (путь зависит от AGP/KMP)
```

Либо **не** публиковать AAR, а в RN-хосте:

```gradle
include ':geoworker-shared'
project(':geoworker-shared').projectDir = file('../TranslineGeoWorker/app/shared')
```

### Sample APK

```bash
make build-apk
# или
./gradlew :app:androidApp:assembleRelease
# → app/androidApp/build/outputs/apk/release/
```

### Debug run sample UI

```bash
./gradlew :app:androidApp:installDebug
```

В `MainActivity`: кнопки continuous start/stop + FGS и рейс.

## 7.4. Что попадает в RN-хост (Android)

1. Зависимость `:app:shared` (KMP)  
2. Kotlin-файлы моста из `app/androidApp/.../geoworker/` (список в `app/connect/FILES_TO_COPY.md`)  
3. Патчи Manifest / MainApplication / build.gradle — `app/connect/patches/android/`

## 7.5. Proguard

```
-keep class org.transline.geoworker.** { *; }
-keep class org.transline.geoworker.tracker.** { *; }
```

## 7.6. Типичные ошибки сборки

| Симптом | Что проверить |
|---------|----------------|
| Unresolved `GeoWorkerPackage` | Не скопирован / не тот package |
| Missing `play-services-location` | deps в `app/build.gradle` хоста |
| FGS crash на старте | permissions + `foregroundServiceType=location` в Manifest |
| Ktor version clash | Выровнять версии client-core/okhttp с shared |
