# План 01 — Обзор модуля

## 1.1. Назначение

**TranslineGeoWorker** обеспечивает непрерывный (или по расписанию рейса) сбор геолокации водителя и отправку на бэкенд:

- координаты: `latitude`, `longitude`
- скорость: `speedMps` (≥ 0)
- идентификатор: `driver_uuid`
- HTTP: `POST {host}/api/coordinates`
- Auth: Basic (по умолчанию из ТЗ, можно переопределить из JS)

Работает:

| Режим | Где крутится | Когда |
|-------|----------------|------|
| Continuous native | iOS CLLocation / Android Fused + FGS | `startLocationService` |
| Trip schedule | KMP `startTrip` + 30 мин слоты | Назначение рейса |
| Foreground JS | `useForegroundLocationLogger` | AppState = active (резерв / лог) |
| Offline queue | SharedPreferences / UserDefaults | Нет сети или non-200 |

## 1.2. Зачем два режима (merge)

Исторически в приложении был **непрерывный** трекинг (старый `LocationService`).  
В KMP добавили модель **рейса** (первая точка за час до погрузки, далее каждые 30 мин) и офлайн-очередь.

Текущая реализация **объединяет оба**:

- continuous API совместим со старым `LocationTracker.ts`
- trip API остаётся для логики рейса/модерации

Их можно использовать по отдельности или вместе (см. [План 06](./06-hooks-and-combos.md) и [План 12](./12-scenarios.md)).

## 1.3. Что модуль НЕ делает

- Не заменяет UI навигации / карты
- Не реализует полный TurboModule New Architecture codegen (классический NativeModule / RCTEventEmitter)
- Не содержит jotai/token-manager хоста — хуки принимают deps снаружи
- `server/` в репо — отдельный Ktor-проект, не бэкенд coordinates API продукта
- **Notify Manager** не заменяет geo-нотификации FGS / `GeoNotificationHelper` (см. [План 15](./15-notify-manager.md))

## 1.3a. Дополнительно: Notify Manager

В том же KMP-артефакте (`app/shared`) есть пакет `org.transline.geoworker.notify`:

- `show` / `handleRemote` / `cancel` / `snooze`
- картинка, deepLink, до 3 action-кнопок
- RN-мост `NativeModules.NotifyApp` + JS `src/native/NotifyApp.ts`

Подробно: [15-notify-manager.md](./15-notify-manager.md).

## 1.4. Ключевые артефакты для потребителя

| Артефакт | Путь / команда | Кто потребляет |
|----------|----------------|----------------|
| KMP XCFramework | `make build-xcframework` | iOS RN / native |
| KMP Android library | Gradle `:app:shared` | Android RN |
| JS facade | `src/native/` | React Native JS |
| Connect patches | `app/connect/patches/` | Правки RN-хоста |

## 1.5. Термины

| Термин | Значение |
|--------|----------|
| **updateIntervalMinutes** | Минимальный интервал между HTTP-отправками в continuous-режиме |
| **lastSent** | Timestamp последней попытки/успеха (для throttle и backoff) |
| **isRequestInProgress** | In-memory lock: не слать параллельные POST |
| **backoff** | При ошибке: `lastSent = now - interval + 30s` → ретрай раньше полного интервала |
| **FGS** | Android Foreground Service с типом `location` |
| **tracking_active** | Флаг в storage: сервис «должен» работать (в т.ч. после reboot) |
