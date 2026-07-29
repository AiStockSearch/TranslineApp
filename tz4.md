Ниже представлено сформированное **Техническое задание (ТЗ)**, полностью опирающееся на текущую реализацию кода. Оно предназначено для переиспользования, воссоздания или интеграции данного модуля в другое приложение.

---

# Техническое задание: Модуль фонового геомониторинга и настройки UI

## 1. Назначение системы

Модуль предназначен для непрерывного сбора геолокационных данных водителя (координаты и скорость), их периодической отправки на бэкенд-сервер по протоколу HTTP REST API в фоновом и активном режимах работы приложения, а также для вспомогательного управления системными панелями на платформе Android.

---

## 2. Архитектура и состав компонентов

Система делится на 4 основных слоя:

1. **Нативный модуль iOS (`LocationService`, `LocationServiceController`)** — автономный сервис сборки и отправки координат.


2. **Мост React Native (`LocationTrackingModule`)** — NativeModule на Objective-C/Swift для управления нативным сервисом из JavaScript.


3. **Слой JavaScript/TypeScript (`LocationTracker.ts`)** — бизнес-логика, React-хуки, управление состоянием (Jotai) и резервный foreground-трекинг.


4. **Утилитный модуль UI (`SystemBars.ts`)** — управление стилем системных панелей для Android.



---

## 3. Функциональные требования

### 3.1. Фоновый трекинг (iOS Native)

* **Точность и конфигурация GPS**:
* Точность: `kCLLocationAccuracyBest`.


* Фильтр изменения дистанции: `1 метр` (`distanceFilter = 1`).


* Фоновый режим: разрешен (`allowsBackgroundLocationUpdates = true`).


* Автоматическая пауза обновлений: отключена (`pausesLocationUpdatesAutomatically = false`).


* Индикатор фоновой геолокации: включен на iOS 11+ (`showsBackgroundLocationIndicator = true`).




* **Контроль отправки (Троттлинг)**:
* Модуль проверяет интервал `updateIntervalMinutes`.


* Если с момента последней успешной отправки прошло меньше заданного времени, текущее обновление пропускается.


* Отправка блокируется, если предыдущий HTTP-запрос еще не завершен (`isRequestInProgress = true`).




* **Обработка ошибок сети**:
* Если бэкенд возвращает статус, отличный от `200 OK`, время последней отправки (`lastRequestTime`) искусственно сдвигается назад (`-updateInterval + 30 сек`), чтобы повторить попытку отправки раньше обычного интервала.





### 3.2. Трекинг в активном режиме (React Native Foreground Logger)

* При запуске приложения в фокусе (`AppState === "active"`) запускается опрос координат через `@react-native-community/geolocation`:


* **Интервал снятия координат**: каждые `10 000 мс` (10 сек).


* **Интервал отправки на сервер**: каждые `60 000 мс` (60 сек).


* **Параметры сбора**: `enableHighAccuracy: true`, `timeout: 8000`, `maximumAge: 0`.




* При уходе приложения в фоновый режим данный JS-поллинг останавливается, уступая место нативному iOS-сервису.



### 3.3. Управление разрешениями

Система должна запрашивать и верифицировать права на геолокацию:

* Запрос прав уровня **"Always Allow"** (`requestAlwaysAuthorization`).


* **Коды статусов разрешений**, возвращаемые в JS:


| Код | Значение | Условие на iOS |
| --- | --- | --- |
| `"1"` | **Разрешено** | `authorizedAlways` (или `authorizedWhenInUse` для некоторых базовых проверок)

 |
| `"2"` | **Отклонено** | `denied` / `restricted`<br> |
| `"3"` | **Не определено / Отключено** | `notDetermined` или геослужбы устройства выключены

 |



### 3.4. Управление панелями Android (SystemBars)

* Автоматическая установка стиля системных панелей при запуске, если версия ОС Android ниже 32 (Android 12L).


* Игнорируется на iOS.



---

## 4. Протокол сетевого взаимодействия (API)

Все запросы отправляются методом **`POST`** на эндпоинт `<apiEndpoint>/api/coordinates`.

### 4.1. Авторизация

* **Заголовок**: `Authorization: Basic dHJhbnNsaW5lX3VzZXI6VHJhbjMkU2wxMkBuZUA=`

* **Декодированные данные**: `transline_user:Tran3$Sl12@ne@`

* **Content-Type**: `application/json`


### 4.2. Формат тела запроса (JSON Payload)

```json
{
  "latitude": 55.755826,
  "longitude": 37.617299,
  "speedMps": 12.5,
  "driver_uuid": "c3017a12-8419-4171-807d-5a8a18df7907"
}

```

* `speedMps`: Скорость движения в м/с (если значение отрицательное, округляется до `0`).


* `driver_uuid`: Идентификатор водителя, извлекаемый из объекта пользователя в JS (`user_uuid` || `driver_uuid` || `uuid` || `id`).



---

## 5. Публичный JS/TS API (для использования в приложении)

Модуль предоставляет следующие экспортные методы и хуки:

```typescript
// Запуск и сохранение конфигурации
startLocationService(apiEndpoint, driverUuid, orderNumber?, updateIntervalMinutes?): Promise<void>
saveLocationConfiguration(apiEndpoint, driverUuid, orderNumber?, updateIntervalMinutes?): Promise<boolean>
stopLocationService(): Promise<void>

// Проверка прав и статуса
requestLocationPermission(): Promise<boolean>
hasRequiredPermissions(): Promise<LocationPermissionStatus>
getLocationPermissionStatus(): Promise<LocationPermissionStatus>
isLocationServiceRunning(): Promise<boolean>

// React Hooks
useStartLocationService(): { start, checkIsRunning, saveConfiguration }
useForegroundLocationLogger(): void

```

---

## 6. Требования к окружению и интеграции

При перенесении модуля в новый проект необходимо предусмотреть:

1. **Зависимости (npm/yarn)**:
* `@react-native-community/geolocation`

* `jotai` (для чтения глобальных атомов состояния `apiHostAtom`, `locationTrackerIntervalAtom`)


* `lodash`



2. **Конфигурация iOS (`Info.plist`)**:
* `NSLocationWhenInUseUsageDescription`
* `NSLocationAlwaysAndWhenInUseUsageDescription`
* `NSLocationAlwaysUsageDescription`
* `UIBackgroundModes`: добавление значения `location`.


3. **Безопасность (Рекомендуемое улучшение)**:
* Вынести хардкод учётных данных `Basic Auth` (`transline_user:Tran3$Sl12@ne@`) в динамические переменные окружения или передавать их из JS через конфигурацию при авторизации.