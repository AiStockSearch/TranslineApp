# План 08 — Сборка iOS

## 8.1. Требования

- Xcode (актуальный для RN-хоста)
- CocoaPods
- `xcode-select` / симулятор для тестов

## 8.2. XCFramework (главный артефакт)

```bash
make build-xcframework
# или
./gradlew :app:shared:assembleSharedLocationTrackerXCFramework
```

Результат:

```
app/shared/build/XCFrameworks/release/SharedLocationTracker.xcframework
```

Имя фреймворка: **`SharedLocationTracker`**  
Bundle id option: `org.transline.geoworker.shared`

## 8.3. Тесты iOS (Kotlin/Native)

```bash
make test-ios
# или
./gradlew :app:shared:iosSimulatorArm64Test
```

## 8.4. Sample iosApp

Откройте `app/iosApp/iosApp.xcodeproj` в Xcode и Run.  
RN-мост в sample условно компилируется (`#if canImport(React)`).

Для **продакшен RN** используйте файлы моста + XCFramework через Pod или Embed.

## 8.5. CocoaPods (рекомендуемый путь для RN)

Podspec: [`app/connect/TranslineGeoWorker.podspec`](../app/connect/TranslineGeoWorker.podspec)

```ruby
# ios/Podfile
pod 'TranslineGeoWorker', :path => '../path/to/TranslineGeoWorker/app/connect'
```

```bash
cd ios && pod install
```

Pod подтянет:

- `SharedLocationTracker.xcframework` (нужен **уже собранный**)
- `LocationTrackerModule.swift` / `.m`
- helpers network/notifications

**Порядок:** сначала `make build-xcframework`, потом `pod install`.

## 8.6. Ручное Embed

1. Drag XCFramework → Target → Frameworks, Libraries…  
2. Embed & Sign  
3. Добавить Swift/ObjC мост в Target Membership  
4. Info.plist keys + Background Modes → Location  

## 8.7. Info.plist (обязательно)

- `NSLocationWhenInUseUsageDescription`
- `NSLocationAlwaysAndWhenInUseUsageDescription`
- `NSLocationAlwaysUsageDescription`
- `UIBackgroundModes` = `location`

Патч: `app/connect/patches/ios/02-Info.plist.patch`

## 8.8. Типичные ошибки

| Симптом | Что проверить |
|---------|----------------|
| `No such module SharedLocationTracker` | XCFramework не собран / не в Embed |
| Module not found React | Мост в non-RN target — ок для sample; для RN нужен RN pods |
| Background updates killed | Нет Always permission / нет UIBackgroundModes |
| Swift interop Int? | Используйте `KotlinInt` / актуальный экспорт KMP |
