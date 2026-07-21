# iOS — интеграция

## 1. XCFramework

```bash
cd TranslineGeoWorker && make build-xcframework
```

Артефакт:

`app/shared/build/XCFrameworks/release/SharedLocationTracker.xcframework`

После изменений в `app/shared` (в т.ч. notify) **пересоберите** XCFramework перед установкой в хост.

### Вариант A — CocoaPods

В `Podfile` (см. `patches/ios/01-Podfile.patch`):

```ruby
pod 'TranslineGeoWorker', :path => '../../TranslineGeoWorker/app/connect'
```

Затем:

```bash
cd ios && pod install
```

### Вариант B — вручную в Xcode

1. Drag `SharedLocationTracker.xcframework` → Target → Frameworks
2. **Embed & Sign**
3. Добавьте в Target Membership файлы моста (ниже)

## 2. Файлы моста (скопировать)

Из `TranslineGeoWorker/app/iosApp/iosApp/`:

| Файл | Назначение |
|------|------------|
| `LocationTrackerModule.swift` | RN bridge → KMP geo |
| `LocationTrackerModule.m` | `RCT_EXTERN_REMAP_MODULE(LocationTracking, …)` |
| `NotifyAppModule.swift` | RN bridge → KMP Notify Manager |
| `NotifyAppModule.m` | `RCT_EXTERN_REMAP_MODULE(NotifyApp, …)` |
| `IOSNetworkChecker.swift` | NetworkChecker |
| `IOSNotificationHelper.swift` | geo локальные нотификации |

**Важно:** в Xcode у каждого `.swift` / `.m` включите **Target Membership** вашего RN-таргета. Без этого `NativeModules.NotifyApp` будет `undefined`.

Убедитесь, что Swift компилируется в RN-таргете (Bridging Header при необходимости).

Для удалённых пушей: Push Notifications capability + обработка APNs/`data` → JS `handleRemoteNotify` (см. docs/15).

## 3. Info.plist

Патч: `patches/ios/02-Info.plist.patch`.

Ключи:

- `NSLocationWhenInUseUsageDescription`
- `NSLocationAlwaysAndWhenInUseUsageDescription`
- `NSLocationAlwaysUsageDescription`
- `UIBackgroundModes` → `location` (и при push — `remote-notification` по политике хоста)

Xcode → Signing & Capabilities → **Background Modes** → Location updates.

## 4. AppDelegate

Обычно достаточно наличия `.m` модулей в таргете (autolink RN modules).  
См. комментарии в `patches/ios/03-AppDelegate.mm.patch`.

## 5. Проверка

```bash
cd ios && pod install
npx react-native run-ios
```

```js
import { NativeModules } from 'react-native';
console.log(!!NativeModules.LocationTracking);
console.log(!!NativeModules.NotifyApp); // true, если Notify в Target Membership
```

JS bootstrap: [`../js/notify-bootstrap.example.tsx`](../js/notify-bootstrap.example.tsx).
