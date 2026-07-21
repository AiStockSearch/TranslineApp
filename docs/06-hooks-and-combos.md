# План 06 — Хуки и комбинации

## 6.1. `useStartLocationService(deps)`

**Файл:** `src/native/LocationTracker.ts`

**Зачем:** удобный старт/сейв конфига без жёсткой привязки к jotai/token-manager.  
Хост передаёт уже известные `apiHost` и `driverUuid`.

### Зависимости (`StartLocationServiceDeps`)

```ts
{
  apiHost: string | null | undefined;       // сырой host, можно с /api
  driverUuid: string | null | undefined;
  defaultIntervalMinutes?: number;          // fallback interval
}
```

### Возвращает

| Поле | Что делает |
|------|------------|
| `start(orderNumber?, updateIntervalMinutes?)` | `startLocationService` с нормализованным host |
| `saveConfiguration(orderNumber?, interval?)` | Только save, без GPS |
| `checkIsRunning()` | `isLocationServiceRunning()` |

### Когда вызывать

- После логина, когда известны host и uuid водителя  
- При назначении заказа (`orderNumber`)  
- **Не** вызывать `start` без разрешений Always (см. комбинации ниже)

---

## 6.2. `useForegroundLocationLogger(deps)`

**Зачем:** резервный/дополнительный трекинг **пока приложение в фокусе** (ТЗ §3.2):

- опрос GPS каждые **10 с**
- отправка на сервер не чаще **60 с**
- при уходе в background — **стоп** JS-поллинга (натив продолжает)

### Зависимости (`ForegroundLoggerDeps`)

```ts
{
  apiHost: string | null | undefined;
  driverUuid: string | null | undefined;
  authHeader?: string;           // default = Basic из ТЗ
  Geolocation?: { getCurrentPosition(...) };  // модуль community/geolocation
}
```

**Важно:** без `Geolocation` хук — **no-op** (нативный continuous остаётся основным).

### Внутренние флаги

| Ref | Зачем |
|-----|--------|
| `isPollingRef` | Не запускать параллельный getCurrentPosition |
| `isSendingRef` | Не слать параллельный fetch |
| `lastSendAttemptAtRef` | Throttle 60 с |
| `isStartingRef` | Защита от двойного startPolling |

### Когда использовать

- Как **дополнение** к native continuous на iOS (пока UI активен)  
- Для отладки координат в логах (`local logging - coordinates: …`)  
- **Не** как единственный источник в фоне (JS убивается OS)

---

## 6.3. `useSystemBarStyle()`

**Зачем:** на Android API &lt; 32 автоматически вызвать `setSystemBarsStyle(false)` при mount.  
На iOS — пусто.

Обычно вешают **один раз** в корневом App / root navigator.

---

## 6.4. Матрица комбинаций

Легенда: ✅ рекомендуется · ⚪ опционально · ❌ не смешивать без нужды

### Комбинация A — «Как старое приложение» (continuous)

```
requestLocationPermission
  → hasRequiredPermissions === "1"
  → useStartLocationService(...).start(order, interval)
  → useForegroundLocationLogger({ Geolocation, ... })  // пока active
  → useSystemBarStyle()
```

| Кусок | Роль |
|-------|------|
| Native continuous | Фон + throttle interval |
| JS foreground | Активный режим 10s/60s |
| SystemBars | UI Android &lt; 32 |

✅ Основной прод-сценарий водителя.

---

### Комбинация B — «Только рейс» (schedule 30 мин)

```
saveLocationConfiguration / WithAuth
  → startTrip(loadingTimeEpochMs)
  → initializeAndSyncOnAppStart()  // при каждом cold start
  → subscribeToEvents(...)
  // опционально: checkAndSyncTracking по таймеру/push
```

| Кусок | Роль |
|-------|------|
| `startTrip` | Расписание |
| FGS (Android) | Живучесть процесса |
| `completeTripAfterModeration` | Финал |

⚪ Foreground logger не обязателен.  
✅ Если бизнес-логика «точка раз в 30 мин вокруг погрузки».

---

### Комбинация C — Continuous + рейс вместе

```
startLocationService  // continuous + interval minutes
startTrip(loadingTime) // параллельно schedule state
```

| Эффект | Пояснение |
|--------|-----------|
| Один `tracking_active` | Оба режима делят флаг |
| Два пути отправки | `onLocationUpdate` (interval) **и** `executePending…` (30 мин) |
| Риск | Чаще HTTP, если interval маленький |

⚪ Допустимо, если продукт хочет и частые точки, и «офигрузочное» расписание.  
Контролируйте `updateIntervalMinutes` (≥ 1).

---

### Комбинация D — Только конфиг заранее + старт по FCM

```
saveLocationConfigurationWithAuth(...)
// позже из push:
LocationServiceController.startLocationService(context)  // Android native
// или JS:
startLocationService(...)
```

✅ Для «включили рейс с бэка».

---

### Комбинация E — Минимальный debug

```
getCurrentLocation()
openGpsSettings()
getScheduleState()
```

Без start/stop — только диагностика.

---

### Комбинация F — Auth из JS (без хардкода)

```
saveLocationConfigurationWithAuth(host, uuid, order, interval, username, password)
startLocationService(...)  // подхватит уже записанный authHeader
```

или передать auth один раз через save, затем start с теми же endpoint/uuid.

✅ Рекомендация ТЗ §6.

---

## 6.5. Антипаттерны

| Не делать | Почему |
|-----------|--------|
| Только `useForegroundLocationLogger` без native start | В фоне координаты пропадут |
| `start` при status `"2"` / `"3"` | Reject / бесполезный старт |
| `completeTripAfterModeration` во время нужного continuous | `clear()` сотрёт endpoint/uuid |
| Два экземпляра `useForegroundLocationLogger` | Двойной poll/send |
| Забыть FGS permissions на Android 13+ | Нотификация FGS может не показаться |

---

## 6.6. Пример корневого бутстрапа (комбинация A)

```tsx
function DriverGeoRoot({ apiHost, user }) {
  useSystemBarStyle();

  const driverUuid = getDriverUuid(user);
  const { start, checkIsRunning } = useStartLocationService({
    apiHost,
    driverUuid,
    defaultIntervalMinutes: 1,
  });

  useForegroundLocationLogger({
    apiHost,
    driverUuid,
    Geolocation, // from @react-native-community/geolocation
  });

  useEffect(() => {
    const off = subscribeToEvents((e) => console.log(e));
    (async () => {
      await requestLocationPermission();
      if ((await hasRequiredPermissions()) === '1') {
        await start('ORD-1');
      }
      await initializeAndSyncOnAppStart();
    })();
    return () => { off(); void stopLocationService(); };
  }, []);

  return null;
}
```
