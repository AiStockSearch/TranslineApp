# План 12 — Пошаговые сценарии

Практические сценарии для QA и интеграции. API и хуки: [05-js-api.md](./05-js-api.md), [06-hooks-and-combos.md](./06-hooks-and-combos.md). Подключение: [RN-INTEGRATION.md](./RN-INTEGRATION.md).

Условные обозначения:

| Метка | Смысл |
|-------|--------|
| **JS** | Вызов из React Native |
| **Native** | Android/iOS без участия JS |
| **Ожидание** | Что должно произойти / что увидеть в логах |

---

## Сценарий 1 — Водитель выходит на линию (continuous)

**Цель:** фоновый трекинг с периодической отправкой координат на `POST …/api/coordinates`.  
**Комбинация:** A (план 06).

### Предусловия

- Мост слинкован: `NativeModules.LocationTracking` существует.
- Известны `apiHost`, `driverUuid` (после логина).
- (Опц.) `@react-native-community/geolocation` для foreground logger.
- Android 13+: можно запросить `requestForegroundServicePermission()` до старта.

### Шаги

1. **JS:** в корне App — `useSystemBarStyle()` (Android API &lt; 32).
2. **JS:** `subscribeToEvents` / `useGeoWorkerEvents` — слушать `LOCATION_SENT` / `LOCATION_FAILED`.
3. **JS:** `requestLocationPermission()` → диалог Always / Fine (+ background на Android).
4. **JS:** `hasRequiredPermissions() === "1"`.  
   - Если `"2"` / `"3"` — не вызывать `start`; показать UI «нужен доступ Always».
5. **JS:** `useStartLocationService({ apiHost, driverUuid, defaultIntervalMinutes }).start(orderNumber, interval)`  
   или напрямую `startLocationService(apiHost, driverUuid, orderNumber, intervalMinutes)`.
6. **Ожидание Android:** появляется FGS-нотификация («Трекинг…» / аналог); процесс не убивается сразу при уходе в фон.
7. **Ожидание iOS:** background location indicator; обновления через CoreLocation.
8. **Внутри:** GPS continuous → `onLocationUpdate` → throttle по `updateIntervalMinutes` → `POST /api/coordinates`.
9. **Ожидание Metro:** событие `LOCATION_SENT` с `latitude`, `longitude`, `timestamp` (частота ≈ interval, не каждый GPS-тик).
10. **JS:** `useForegroundLocationLogger({ apiHost, driverUuid, Geolocation })` — пока `AppState === active`: лог `local logging - coordinates: …` (~10 с), доп. HTTP ≤ раз в 60 с.

### Стоп смены

```ts
await stopLocationService();
```

**Ожидание:** `isLocationServiceRunning() === false`; Android FGS останавливается; GPS `stopTracking`; `lastSent` сбрасывается.

### Проверка

| Проверка | Как |
|----------|-----|
| Мост | `!!NativeModules.LocationTracking` |
| Права | `hasRequiredPermissions() === "1"` |
| FGS (Android) | нотификация в шторке |
| Отправка | Metro: `LOCATION_SENT` |
| Active-лог | `local logging - coordinates` |

### Типичные сбои

| Симптом | Причина |
|---------|---------|
| Нет SENT, есть FAILED | сеть / auth / 4xx–5xx → offline queue |
| SENT слишком редко | большой `updateIntervalMinutes` |
| SENT «часто» в Metro от logger | это JS foreground, не native throttle |
| Старт без эффекта | права не Always (`"2"`) |

---

## Сценарий 2 — Назначение рейса (schedule)

**Цель:** точки по расписанию вокруг погрузки (окно с −1 ч, шаг 30 мин).  
**Комбинация:** B.

### Предусловия

- Сохранены endpoint и `driverUuid`.
- Известно время погрузки (`loadingAt` → epoch ms).

### Шаги

1. **JS:** `saveLocationConfiguration(host, uuid, order, interval?)`  
   или `saveLocationConfigurationWithAuth(...)` — конфиг без обязательного continuous GPS.
2. **JS:** `startTrip(Date.parse(loadingAt))` (или `loadingTimeEpochMs`).
3. **Внутри `startTrip`:**
   - `tracking_active = true`;
   - `nextScheduled = loadingTime − 1 час` (`ONE_HOUR_MS`);
   - `updateIntervalMinutes = 30` (троттлинг для FGS/resume/FCM).
4. **Окно отправок:** с `nextScheduled` до завершения рейса слоты каждые **30 мин** (`THIRTY_MINUTES_MS`).
5. **Тик расписания** (любой из путей):
   - **JS:** `initializeAndSyncOnAppStart()` / `checkAndSyncTracking()`;
   - **Native Android:** FGS / boot вызывает `executePendingOrScheduledTracking` / sync;
   - при `now >= nextScheduled` → `getCurrentLocation()` → POST → сдвиг `nextScheduled += 30 мин`.
6. **Ожидание:** `LOCATION_SENT` не чаще ~30 мин (если GPS доступен); при null location — `LOCATION_SERVICES_DISABLED`.
7. После модерации на бэке: **JS** `completeTripAfterModeration()`.
   - last location (если есть) → POST;
   - `storage.clear()` + `stopTracking` (+ stop FGS на Android).

### Важно

- `completeTripAfterModeration` **сохраняет** endpoint/uuid/auth (`clearTripState`). Полный wipe — только logout / `storage.clear()`.
- Foreground logger для рейса **не обязателен**.

### Проверка

| Проверка | Как |
|----------|-----|
| Расписание | `getScheduleState()` → `isTrackingActive`, `nextScheduledTimestamp` |
| Ранний тик | `checkAndSyncTracking()` при `now >= next` |
| Финал | после complete — `isTrackingActive === false`, конфиг пустой |

---

## Сценарий 3 — Cold start приложения

**Цель:** после убийства/перезапуска приложения восстановить очередь и слот рейса / continuous.

### Шаги

1. Пользователь открывает приложение (JS снова жив).
2. **JS:** `initializeAndSyncOnAppStart()`:
   - `flushOfflineQueue()` — попытка отправить накопленное;
   - `executePendingOrScheduledTracking(force=false)` — если рейс active и слот просрочен → отправка.
3. **Ожидание:** при успехе flush/слота — `LOCATION_SENT`; при ошибках — `LOCATION_FAILED` (точки могут остаться в queue).
4. Если до kill был **continuous** с `tracking_active`:
   - на Android FGS / BootReceiver могли уже вызвать `resumeLocationServiceIfActive` **без JS**;
   - на iOS continuous зависит от background location и того, был ли сервис запущен до kill — при cold start надёжнее снова проверить `isLocationServiceRunning()` и при необходимости `startLocationService` / resume-путь хоста.
5. Подписка на события снова нужна в JS (listeners с прошлого запуска не живут).

### Рекомендация хосту

На каждом cold start после логина:

```ts
subscribeToEvents(...);
await initializeAndSyncOnAppStart();
// при необходимости: resume continuous, если продукт так задумал
```

---

## Сценарий 4 — Reboot телефона (Android)

**Цель:** трекинг переживает перезагрузку устройства без открытия приложения.

### Предусловия

- До reboot: `tracking_active = true` в SharedPreferences (после `startLocationService` / `startTrip`).
- В Manifest зарегистрированы `BootReceiver` + `LocationForegroundService` + permission `RECEIVE_BOOT_COMPLETED`.
- Права локации (в т.ч. background) выданы.

### Шаги

1. До reboot трекинг активен (continuous и/или trip).
2. Устройство перезагружается.
3. **Native:** `BOOT_COMPLETED` → `BootReceiver`:
   - если tracking inactive → skip;
   - иначе → `LocationForegroundService.start`.
4. **FGS onCreate/start:**
   - `resumeLocationServiceIfActive()` — снова `startTracking` без сброса `lastSent`/конфига;
   - `initializeAndSyncOnAppStart()` — flush queue + schedule tick.
5. **Ожидание:** FGS-нотификация снова видна; POST идут по throttle/слоту **без** Metro/JS.
6. После открытия приложения JS может подписаться на события и вызвать `initializeAndSyncOnAppStart` ещё раз (идемпотентно по смыслу sync).

### iOS

Отдельного BootReceiver нет. Background recovery — через Always + `UIBackgroundModes: location` и политику ОС; не полагайтесь на reboot-сценарий Android 1:1.

### Типичные сбои

| Симптом | Причина |
|---------|---------|
| После reboot тишина | нет BootReceiver / нет permission / `tracking_active=false` |
| FGS не стартует | Android 12+ ограничения, нет notification permission (13+) |

---

## Сценарий 5 — Нет сети

**Цель:** не терять точки при offline; доставить позже.

### Шаги

1. Трекинг active, сеть недоступна (airplane / Wi‑Fi off).
2. Срабатывает отправка (continuous throttle или schedule tick).
3. **Внутри:** `sendOrQueueLocation` → HTTP fail → точка в **offline queue**.
4. **Ожидание:**
   - событие `LOCATION_FAILED` (+ message);
   - Android: offline-нотификация через `GeoNotificationHelper` (если включено);
   - continuous: backoff `lastSent = now - interval + 30_000` → более ранний ретрай, чем полный interval.
5. Сеть появляется.
6. Flush происходит при:
   - `initializeAndSyncOnAppStart()`;
   - следующем успешном пути sync / отправки (repository flush);
   - старте FGS / resume после boot.
7. **Ожидание:** точки уходят на сервер; далее снова `LOCATION_SENT`.

### Проверка

- Сначала FAILED без SENT при offline.
- После сети + sync — SENT (или уменьшение queue; смотреть логи/бэкенд).

---

## Сценарий 6 — Смена auth без перезапуска GPS

**Цель:** обновить Basic/`Authorization` на лету.

### Шаги

1. Continuous (или trip) уже работает.
2. **JS:** `saveLocationConfigurationWithAuth(host, uuid, order, interval, newUser, newPass)`  
   (или эквивалентный save с новым auth header).
3. **Ожидание:** GPS **не** останавливается; `tracking_active` остаётся.
4. Следующие POST используют новый `Authorization`.
5. Если старый auth давал 401 — после смены ожидайте `LOCATION_SENT` вместо FAILED (при живой сети).

### Замечания

- Default Basic из ТЗ лучше сразу переопределять WithAuth (план 11).
- Secure Keychain / `saveSecureConfig` / `httpProbe` — отдельный контур (события `KEYCHAIN_*`, `HTTP_*`, `AUTH_MISSING`); не путать с Basic в `TrackingStorage`.

---

## Сценарий 7 — Диагностика «модуль не линкуется»

**Симптом:** `NativeModules.LocationTracking` — `undefined` / `null`.

### Шаги

1. **JS:**
   ```ts
   console.log(NativeModules.LocationTracking);
   console.log(Platform.OS === 'android' ? NativeModules.SystemBars : 'n/a');
   ```
2. **Android:**
   - в `MainApplication.getPackages()` есть `GeoWorkerPackage()`;
   - Kotlin-мост скопирован, package `org.transline.geoworker`;
   - shared подключён в Gradle (`settings.gradle` + `implementation project(...)`);
   - clean: `./gradlew :app:clean` + reinstall.
3. **iOS:**
   - `LocationTrackerModule.m` в Target Membership;
   - `pod install` / Embed & Sign `SharedLocationTracker.xcframework`;
   - Bridging Header / Swift в таргете;
   - clean Derived Data + rebuild.
4. Имя модуля строго **`LocationTracking`** (remap), не `LocationTrackerModule`.
5. New Architecture / Turbo: классический bridge должен работать в Bridge mode; при сбое см. [13-turbomodule-events.md](./13-turbomodule-events.md).

### Ожидание после фикса

`!!NativeModules.LocationTracking === true`; вызов `isLocationServiceRunning()` не падает.

---

## Сценарий 8 — Только JS foreground (не для прода)

**Цель:** быстро увидеть координаты в Metro без native continuous.

### Шаги

1. **Не** вызывать `startLocationService`.
2. Только `useForegroundLocationLogger({ apiHost, driverUuid, Geolocation })`.
3. App на переднем плане → `local logging - coordinates` ~каждые 10 с; HTTP (если заданы host/uuid) ≤ раз в 60 с.
4. Уход в background / kill → **JS polling останавливается**; точек в фоне нет.

### Когда использовать

- Отладка UI / проверка endpoint / permission soft (`getLocationPermissionStatus`).
- **Не** замена фоновому трекингу водителя.

---

## Сценарий 9 — FCM «начать трекинг»

**Цель:** включить трекинг с бэка push’ем, когда JS может не работать.

### Предусловия

- Ранее из JS (или натива) сохранены endpoint, uuid, auth: `saveLocationConfiguration*`.
- Android: в FCM/notification handler доступен application `Context`.

### Шаги (старт)

1. Приходит push «начать трекинг».
2. **Native Android:** `LocationServiceController.startLocationService(context)`  
   → поднимает FGS → `resumeLocationServiceIfActive` / старт по сохранённому конфигу.
3. **Альтернатива JS** (если приложение в памяти): `startLocationService(...)`.
4. **Ожидание:** FGS + GPS continuous + POST по interval.

### Стоп

```kotlin
LocationServiceController.stopLocationService(context)
```

Через FGS `ACTION_STOP` / `controller.stopLocationService()` — сброс active, stop tracking.

### iOS

Аналог «старт из push» зависит от хоста (Notification Service / фоновые режимы). Типичный путь — открытие приложения или JS `startLocationService` после push. Отдельного `LocationServiceController` как на Android в iOS-мосте нет в том же виде.

---

## Сценарий 10 — Комбинация continuous + trip

**Цель:** частые точки (continuous) и слоты рейса (30 мин) одновременно.  
**Комбинация:** C (план 06).

### Шаги

1. **JS:** `startLocationService(host, uuid, order, updateIntervalMinutes)` — continuous path (`onLocationUpdate`).
2. **JS:** `startTrip(loadingTimeEpochMs)` — выставляет schedule state и **ставит interval = 30** в storage.
3. **Эффект:**
   - один флаг `tracking_active` на оба режима;
   - два пути отправки: throttle continuous **и** `executePendingOrScheduledTracking` (30 мин);
   - после `startTrip` continuous throttle читает **30 мин** из prefs (FGS/resume/FCM тоже).
4. **Риск:** чаще HTTP и расход батареи, если interval маленький *до* `startTrip`, или если оба пути шлют в близкие моменты.
5. Задайте осмысленный `updateIntervalMinutes` **до** trip или примите 30 мин после `startTrip`.
6. `completeTripAfterModeration` очистит storage — continuous тоже остановится; для продолжения линии нужен новый `save*` + `startLocationService`.

### Рекомендация

Используйте C только если продукт явно хочет оба режима. Иначе — A **или** B, не оба.

### Проверка

| Проверка | Как |
|----------|-----|
| Два пути | логи SENT и от `onLocationUpdate`, и от schedule tick |
| Interval после trip | `getScheduleState` / prefs: 30 мин |
| После complete | tracking off, конфиг пуст |

---

## Сценарий 11 — Отказ в правах / отключение GPS

**Цель:** корректное поведение без крэша.

### Шаги

1. Отклонить Always / выдать только WhenInUse.
2. `hasRequiredPermissions()` → `"2"`; `start` не вызывать (или старт бесполезен для фона).
3. Выключить геолокацию в системе.
4. One-shot / schedule tick с null location → `LOCATION_SERVICES_DISABLED`.
5. **JS:** `openGpsSettings()` — пользователь включает GPS; повторить permission + start.

### Ожидание

Нет необработанных exception в мосте; UI хоста показывает понятный статус по кодам `"1"|"2"|"3"`.

---

## Сценарий 12 — Смена заказа / interval на лету

**Цель:** обновить `orderNumber` или интервал без полной переустановки модуля.

### Шаги

1. Трекинг уже active.
2. **JS:** `saveLocationConfiguration(host, uuid, newOrder, newInterval)`  
   или `useStartLocationService(...).saveConfiguration(newOrder, newInterval)`.
3. **Ожидание:** следующие POST используют новый order (если бэкенд читает из конфига/storage на стороне клиента — поле в body сейчас `driver_uuid` + coords; order хранится в storage для хоста/совместимости).
4. Новый `updateIntervalMinutes` влияет на `shouldSendLocation` continuous.
5. Если нужен «сброс таймера как при свежем старте» — `stopLocationService` + `startLocationService` (start очищает `lastSent`).

---

## Матрица «что вызвать»

| Ситуация | Вызовы |
|----------|--------|
| Выход на линию | permission → `startLocationService` + events + (опц.) foreground logger |
| Рейс к погрузке | `save*` → `startTrip` → sync на cold start → `completeTripAfterModeration` |
| Открыли приложение | `initializeAndSyncOnAppStart` |
| Reboot Android | ничего в JS — BootReceiver + FGS |
| Offline | ждать; потом sync/start |
| Push «старт» | `LocationServiceController.start…` (Android) или JS start |
| Модуль invisible | сценарий 7 |
| Только отладка координат | сценарий 8 |

---

## Связанные документы

- [RN-INTEGRATION.md](./RN-INTEGRATION.md) — подключение и отладка логов  
- [06-hooks-and-combos.md](./06-hooks-and-combos.md) — комбинации A–F  
- [11-events-permissions-network.md](./11-events-permissions-network.md) — события, auth, backoff  
- [app/connect/CHECKLIST.md](../app/connect/CHECKLIST.md) — чеклист интеграции  
