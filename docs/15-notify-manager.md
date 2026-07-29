# План 15 — KMP Notify Manager

Дополнительный подмодуль TranslineGeoWorker: кастомные уведомления (title, body, image, ≤3 кнопки) из **JS** или **backend push**. Логика — KMP; React Native — только тонкий мост.

Гео-нотификации (`GeoNotificationHelper` / FGS) **не заменяются**.

---

## 15.1. Архитектура

```
JS showNotify / handleRemoteNotify
        │
        ▼
NativeModules.NotifyApp  (Android NotifyAppModule / iOS NotifyAppModule)
        │
        ▼
NotifyManager (commonMain)  →  PlatformNotifier (androidMain / iosMain)
        │                              │
        │                              ├─ Android: NotificationCompat + BigPicture + AlarmManager snooze
        │                              └─ iOS: UNUserNotificationCenter + attachment + time-interval snooze
        ▼
onNotifyAppEvent  →  JS subscribeToNotifyEvents
```

Firebase/FCM **не** вшит в KMP: хост принимает push и вызывает `handleRemote(data)`.

---

## 15.2. KMP API

Пакет: `org.transline.geoworker.notify`

| API | Назначение |
|-----|------------|
| `NotifyManager.show(payload)` | Показать из JS/натива |
| `NotifyManager.handleRemote(data)` | Распарсить FCM/APNs `data` → show |
| `NotifyManager.cancel(id)` | Снять из шторки |
| `NotifyManager.snooze(id, minutes?)` | Отложить (default 15 мин) |

### Payload

```kotlin
NotifyPayload(
  id = "sbc-1",
  title = "Документ",
  body = "Готов к подписанию",
  imageUrl = "https://cdn.example.com/a.jpg",
  deepLink = "app://sbc/1",
  channelId = "notify_app_channel", // optional
  actions = listOf(
    NotifyAction(NotifyActionId.READ, "Прочитать"),
    NotifyAction(NotifyActionId.OPEN, "Перейти"),
    NotifyAction(NotifyActionId.CLOSE, "Закрыть"),
  ), // max 3
  data = mapOf("sbcId" to "1"),
  snoozeMinutes = 15,
)
```

Кнопки: `read` | `open` | `close` | `snooze` — не больше **трёх** на одну нотификацию.

### Remote `data` keys

| Key | Обязательно |
|-----|-------------|
| `id`, `title` | да (для `handleRemote`) |
| `type` = `notify_app` **или** `tl_notify` = `1` | да для **JS-роутера** (иначе push не уйдёт в Notify) |
| `groupKey` / `group_key`, `entityId` / `entity_id` | нет — для агрегации в шторке (или выводятся из `deepLink`) |
| `body` | нет (пустая строка) |
| `imageUrl` / `image_url` | нет |
| `deepLink` / `deep_link` | нет |
| `channelId` | нет |
| `snoozeMinutes` | нет |
| `actions` | JSON `[{id,title}]` или `read,open,close` |
| остальные | в `payload.data` (`type` / `tl_notify` reserved, не попадают в data) |

Рекомендуется **data-only** push (без FCM `notification` payload), иначе система + `handleRemote` могут дать дубль в шторке.

---

## 15.3. JS API (`src/native/NotifyApp.ts`)

```ts
import {
  showNotify,
  handleRemoteNotify,
  shouldHandleRemoteNotify,
  cancelNotify,
  snoozeNotify,
  requestNotifyPermission,
  subscribeToNotifyEvents,
  useNotifyAppEvents,
  isNotifyAppLinked,
} from './native';

await showNotify({
  id: 'sbc-1',
  title: 'Документ',
  body: 'Готов к подписанию',
  imageUrl: 'https://cdn.example.com/a.jpg',
  deepLink: 'app://sbc/1', // fallback, если у кнопки нет своего
  actions: [
    {
      id: 'read',
      title: 'Прочитать',
      deepLink: 'app://sbc/1/read',
      route: 'SbcReader',
      params: { id: '1', mode: 'preview' },
    },
    { id: 'open', title: 'Перейти', deepLink: 'app://sbc/1' },
    { id: 'close', title: 'Закрыть' },
  ],
});

// Android 13+ перед первым show:
await requestNotifyPermission();

// FCM: только помеченные Notify-пуши (см. §15.6 / §15.10)
if (shouldHandleRemoteNotify(remoteMessage.data)) {
  await handleRemoteNotify(remoteMessage.data);
}

subscribeToNotifyEvents((e) => {
  // SHOWN | CANCELLED | ACTION_READ | ACTION_OPEN | ACTION_CLOSE | ACTION_SNOOZE
  // e.deepLink — уже resolved: action.deepLink ?? payload.deepLink
  console.log(e.type, e.id, e.deepLink, e.route, e.params);
});
```

Native module name: **`NotifyApp`**. Event: **`onNotifyAppEvent`**.

---

## 15.3a. Per-action deepLink + NotifyRouter

Notify Manager **не** вызывает React Navigation. Навигация — через JS **`NotifyRouter`**.

### Payload на кнопку

| Поле action | Смысл |
|-------------|--------|
| `deepLink` | URI для роутера (`app://sbc/:id`) — приоритетнее payload.deepLink |
| `route` | Имя экрана / логический маршрут (опционально) |
| `params` | Параметры для navigate |

В событии `ACTION_*`: `deepLink` = action.deepLink ?? payload.deepLink; плюс `route`, `params`, `actionId`.

### NotifyRouter (хост)

```ts
import { NotifyRouter, showNotify } from './native';
import { navigationRef } from './navigation';

// один раз при старте App
NotifyRouter.register('app://sbc/:id', ({ params }) => {
  navigationRef.navigate('SbcDocument', { id: params.id });
});
NotifyRouter.register('app://sbc/:id/read', ({ params }) => {
  navigationRef.navigate('SbcReader', { id: params.id, mode: params.mode });
});
NotifyRouter.attach(); // слушает ACTION_OPEN + ACTION_READ

// отписка при unmount корня:
// const off = NotifyRouter.attach(); … off();
```

`NotifyRouter` не зависит от конкретной библиотеки навигации — только вызывает ваш handler.

---

## 15.4. Android интеграция

1. Модуль уже в `GeoWorkerPackage` (`NotifyAppModule`).
2. Manifest (sample уже обновлён):

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />

<receiver
    android:name="org.transline.geoworker.notify.NotifyActionReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="org.transline.geoworker.notify.ACTION_BUTTON" />
        <action android:name="org.transline.geoworker.notify.ACTION_OPEN" />
        <action android:name="org.transline.geoworker.notify.ACTION_SNOOZE_FIRE" />
    </intent-filter>
</receiver>
```

3. Перед первым show: `NotifyAndroidContext.init(context)` (делает `NotifyAppModule`).
4. JS: `await requestNotifyPermission()` на Android 13+ (см. bootstrap).
5. Готовый JS: [`app/connect/templates/js/notify-bootstrap.example.tsx`](../app/connect/templates/js/notify-bootstrap.example.tsx).

---

## 15.5. iOS интеграция

1. Файлы моста: `NotifyAppModule.swift`, `NotifyAppModule.m` (remap `NotifyApp`).
2. Добавить в Target Membership рядом с `LocationTrackerModule`.
3. Capability / Info: уведомления (как обычно для push); картинка грузится во временный файл для attachment.
4. Сосуществование с Firebase: Notify ставит `UNUserNotificationCenter.delegate` с **forwarding** на предыдущий делегат и **merge** categories (см. §15.10).

---

## 15.6. FCM wiring (хост)

Не вызывайте `handleRemoteNotify` для всех пушей — только для контракта Notify.

```ts
import {
  handleRemoteNotify,
  shouldHandleRemoteNotify,
} from '@transline/geoworker';
// или onHostPushMessage из notify-bootstrap.example.tsx

messaging().onMessage(async (msg) => {
  const data = msg.data as Record<string, string> | undefined;
  if (!data) return;

  if ((data.type ?? '').startsWith('geo_')) {
    // LocationServiceController / startLocationService
    return;
  }

  if (shouldHandleRemoteNotify(data)) {
    // type === 'notify_app' || tl_notify === '1'
    await handleRemoteNotify(data);
    return;
  }

  // Firebase / внутренний натив хоста — ваш существующий путь
});
```

Бэкенд для Notify: `data.type = "notify_app"` (или `tl_notify=1`) + `id`, `title`, …; **data-only**.

---

## 15.7. Отличие от geo

| | Notify Manager | Geo |
|--|----------------|-----|
| Канал | `notify_app_channel` | `geo_worker_channel` / FGS |
| API | show / handleRemote / cancel / snooze | LocationTracking |
| Картинка / кнопки | да | нет |
| Назначение | SBC / кастомные пуши | координаты sent/offline + FGS |

---

## 15.8. Файлы

| Путь | Роль |
|------|------|
| `app/shared/.../notify/*` | KMP модели, manager, parser, platform actuals |
| `app/androidApp/.../NotifyAppModule.kt` | RN bridge Android |
| `app/iosApp/.../NotifyAppModule.*` | RN bridge iOS |
| `src/native/NotifyApp.ts` | JS facade |
| `src/native/NotifyRouter.ts` | deepLink register / attach → host navigate |
| `app/connect/templates/js/notify-bootstrap.example.tsx` | готовый bootstrap + FCM helper |

---

## 15.9. Host go-live

Минимальный путь, чтобы Notify **заработал в хост RN**. Подробные файлы: [`app/connect/templates/android/INTEGRATION.md`](../app/connect/templates/android/INTEGRATION.md), [`ios/INTEGRATION.md`](../app/connect/templates/ios/INTEGRATION.md), [`js/notify-bootstrap.example.tsx`](../app/connect/templates/js/notify-bootstrap.example.tsx).

**Через npm (`@transline/geoworker` / `make pack-npm`):** geo + Notify уже в tarball — bridge (`NotifyAppModule`), AAR/XCFramework с `org.transline.geoworker.notify`, AndroidManifest merge (`NotifyActionReceiver`), podspec. Host: `npm i` → autolink → JS bootstrap ниже. Ручное копирование `.kt`/`.swift` не нужно.

### Android

- [ ] (npm) autolink `GeoWorkerPackage` **или** (ручная связка) скопирован `NotifyAppModule.kt` + `NotifyAppModule` в package
- [ ] Manifest: `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM` (в npm-пакете уже в library manifest)
- [ ] Manifest: `NotifyActionReceiver` (в npm-пакете уже)
- [ ] `NativeModules.NotifyApp` === объект
- [ ] До первого show: `await requestNotifyPermission()` (Android 13+)

### iOS

- [ ] (npm) pod с XCFramework после `pack-npm` **или** (ручная) Target Membership для `NotifyAppModule.*`
- [ ] `NativeModules.NotifyApp` === объект
- [ ] (опц.) Push capability, если пуши с бэка

### JS

- [ ] Импорт `showNotify` / `handleRemoteNotify` / `NotifyRouter` / `requestNotifyPermission`
- [ ] `NotifyRouter.register('app://…', …)` → ваш `navigationRef.navigate`
- [ ] `NotifyRouter.attach()` (или компонент из `notify-bootstrap.example.tsx`)

### Backend / FCM

- [ ] В handler: `geo_*` → geo; `type=notify_app` **или** `tl_notify=1` → `handleRemoteNotify` / `shouldHandleRemoteNotify`; иначе Firebase/host
- [ ] В `data` Notify: обязательны `id`, `title` + маркер; желательно `body`, `actions`, `deepLink` / `imageUrl`
- [ ] Data-only push для Notify (без дубля `notification` payload)

### Smoke

- [ ] `showNotify({…})` → нотификация в шторке
- [ ] Tap «Перейти» → `ACTION_OPEN` → открылся экран через NotifyRouter
- [ ] FCM/`handleRemoteNotify` (с маркером) → то же
- [ ] Чужой Firebase tap на iOS → не уходит в Notify (forwarding)
- [ ] `cancel` / `snooze` ок

Чеклист репозитория: [`app/connect/CHECKLIST.md`](../app/connect/CHECKLIST.md).

---

## 15.10. Coexistence / Firebase

Notify **не** вшивает Firebase. Конфликты снимаются разделением ответственности.

### Уже разведено

| Слой | Как |
|------|-----|
| Android actions | `NotifyActionReceiver` только `org.transline.geoworker.notify.*` |
| Android channels | `notify_app_channel` ≠ `geo_worker_*` |
| Android notify id | `hash("tl_notify:$id")` — меньше коллизий с чужим `NotificationManager.notify(int)` |
| Geo FGS | отдельный слой; Notify не заменяет |
| JS router | `shouldHandleRemoteNotify` / `onHostPushMessage`: только `notify_app` \| `tl_notify=1` |

### Что делает библиотека (iOS)

| Механизм | Поведение |
|----------|-----------|
| `UNUserNotificationCenter.delegate` | Notify ставит свой, **сохраняет previous** (Firebase/host) и **форвардит** чужие `didReceive` / `willPresent` |
| Ownership | `userInfo.tl_notify=1` (ставится при `show`) или category `notify_app_*` |
| Categories | **merge** с уже зарегистрированными — Firebase categories не затираются |

### Контракт хоста

```text
FCM/APNs data
  ├─ type startsWith geo_     → geo layer
  ├─ type == notify_app
  │    or tl_notify == 1      → handleRemoteNotify
  └─ else                     → Firebase / внутренний натив (не вызывать handleRemote)
```

### Smoke coexistence

1. После `showNotify` tap по **чужой** Firebase-нотификации → обработчик host/Firebase (previous delegate).
2. Tap по Notify → `onNotifyAppEvent` / NotifyRouter.
3. Categories host/Firebase остаются после `showNotify` с actions.

---

## 15.11. Router reports (агрегация по pattern)

`NotifyRouter` считает успешные матчи **по зарегистрированному `pattern`** и число unmatched (событие OPEN/READ без подходящего deepLink/route).

```ts
NotifyRouter.attach(); // trackStats: true по умолчанию

NotifyRouter.subscribeReport((report) => {
  console.log(report);
  // {
  //   byPattern: {
  //     "app://sbc/:id": { total: 3, byType: { ACTION_OPEN: 2, ACTION_READ: 1 } },
  //   },
  //   unmatched: 1,
  // }
});

const snapshot = NotifyRouter.getReport();
NotifyRouter.resetReport();
```

Отключить учёт: `NotifyRouter.attach({ trackStats: false })`.

Это **JS-отчёт** для отладки/аналитики хоста — не сводная нотификация в шторке.

---

## 15.12. Route hub aggregation (шторка)

Несколько пушей с одним **route pattern** схлопываются в **одну** summary-нотификацию (до **15** id).

### Ключи

| | Пример |
|--|--------|
| `deepLink` | `app://sbc/42` → `groupKey=app://sbc`, `entityId=42` |
| или явно | `groupKey: "app://sbc"`, `entityId: "42"` |

### Показ

- Notification id: `grp:{groupKey}`
- Body: `{count}: id1, id2, …`
- Android: `setGroup` + summary + `InboxStyle` (строки = id)
- iOS: тот же `identifier` → обновление content
- Кнопки ≤3; OPEN/READ → deepLink хаба + `params.ids` / `params.count`

### Хост

```ts
NotifyRouter.register('app://sbc', ({ params }) => {
  const ids = (params.ids ?? '').split(',').filter(Boolean);
  navigationRef.navigate('SbcHub', { ids });
});
// одиночные документы без агрегации:
NotifyRouter.register('app://sbc/:id', ({ params }) => {
  navigationRef.navigate('SbcDocument', { id: params.id });
});
```

```ts
await showNotify({
  id: 'evt-1',
  title: 'Документ',
  body: 'Готов',
  deepLink: 'app://sbc/42', // или groupKey + entityId
  actions: [
    { id: 'open', title: 'Список' },
    { id: 'close', title: 'Закрыть' },
  ],
});
```

CLOSE на summary снимает всю группу; cancel entity id убирает один id и обновляет summary.
