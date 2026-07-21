Отличный подход! Чтобы трекинг был устойчив к **перезагрузкам устройства, разряду батареи и выгрузкам из памяти**, вся информация о расписании и статусах отправок должна кэшироваться в энергонезависимое хранилище (локальный файл или Key-Value хранилище, например, `Settings` / `SharedPreferences` / `UserDefaults`).

В KMP-модуле мы добавляем метод получение **состояния отправок** (`TrackingScheduleState`), а также механизмы восстановления и расчета следующего слота.

---

## 1. Структура данных состояния (KMP)

Создадим модель, которая описывает, когда была последняя успешная отправка и когда запланирована следующая:

```kotlin
// shared/src/commonMain/kotlin/models/TrackingScheduleState.kt
package com.example.locationtracker

data class TrackingScheduleState(
    val lastSentTimestamp: Long?,    // Таймштамп последней успешной отправки (null, если еще не было)
    val nextScheduledTimestamp: Long?, // Таймштамп следующей запланированной отправки
    val isTrackingActive: Boolean    // Активен ли рейс/трекинг вообще
)

```

---

## 2. Логика кэширования и расчёта интервалов в KMP

Мы используем 30-минутный интервал (`INTERVAL_MS = 30 * 60 * 1000`).

При восстановлении после разряда батареи:

* Если текущее время **уже больше или равно** `nextScheduledTimestamp` — мы пропустили отправку из-за выключенного телефона. **Нужно незамедлительно отправить текущую геолокацию** и пересчитать следующий таймер.
* Если время еще не пришло — просто перепланируем таймер на оставшееся время.

```kotlin
// shared/src/commonMain/kotlin/LocationTrackerController.kt
package com.example.locationtracker

import kotlinx.datetime.Clock

class LocationTrackerController(
    private val locationProvider: PlatformLocationProvider,
    private val apiService: LocationApiService,
    private val storage: TrackingStorage // Кэш-хранилище (SharedPreferences / UserDefaults)
) {
    companion object {
        const val THIRTY_MINUTES_MS = 30 * 60 * 1000L
        const val ONE_HOUR_MS = 60 * 60 * 1000L
    }

    // --- Метод для React Native / Нативного модуля: Получить текущий график ---
    fun getScheduleState(): TrackingScheduleState {
        val lastSent = storage.getLastSentTimestamp()
        val nextScheduled = storage.getNextScheduledTimestamp()
        val isActive = storage.isTrackingActive()

        return TrackingScheduleState(
            lastSentTimestamp = lastSent,
            nextScheduledTimestamp = nextScheduled,
            isTrackingActive = isActive
        )
    }

    // Вызывается при назначении рейса
    fun startTrip(loadingTimeEpochMs: Long) {
        val firstTrackingTime = loadingTimeEpochMs - ONE_HOUR_MS
        storage.setTrackingActive(true)
        storage.setNextScheduledTimestamp(firstTrackingTime)
    }

    // Главный метод выполнения отправки (вызывается фоновой службой или при восстановлении)
    suspend fun executePendingOrScheduledTracking(): TrackingScheduleState {
        if (!storage.isTrackingActive()) {
            return getScheduleState()
        }

        val now = Clock.System.now().toEpochMilliseconds()
        val nextScheduled = storage.getNextScheduledTimestamp() ?: now

        // Если время прошло (например, телефон был выключен и включился) или настало
        if (now >= nextScheduled) {
            val location = locationProvider.getCurrentLocation()
            if (location != null) {
                val success = apiService.sendLocation(location)
                if (success) {
                    val currentSentTime = now
                    val newNextTime = currentSentTime + THIRTY_MINUTES_MS

                    // Атомарно сохраняем в кэш-файл
                    storage.setLastSentTimestamp(currentSentTime)
                    storage.setNextScheduledTimestamp(newNextTime)
                }
            }
        }

        return getScheduleState()
    }

    // Завершение рейса после модерации
    suspend fun completeTripAfterModeration() {
        val lastLocation = locationProvider.getCurrentLocation()
        if (lastLocation != null) {
            apiService.sendLocation(lastLocation)
        }
        
        // Очищаем кэш и останавливаем
        storage.clear()
        locationProvider.stopTracking()
    }
}

// Интерфейс для локального кэширования
interface TrackingStorage {
    fun getLastSentTimestamp(): Long?
    fun setLastSentTimestamp(time: Long)
    fun getNextScheduledTimestamp(): Long?
    fun setNextScheduledTimestamp(time: Long)
    fun isTrackingActive(): Boolean
    fun setTrackingActive(active: Boolean)
    fun clear()
}

```

---

## 3. Передача метода в React Native через Native Module

Добавим метод `getScheduleState` и обработку запуска при включении батареи.

### Android Native Module (`LocationTrackerModule.kt`)

```kotlin
package com.example.locationtracker

import com.facebook.react.bridge.*

class LocationTrackerModule(reactContext: ReactApplicationContext) : 
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "LocationTracker"

    // Метод, доступный в React Native: узнать когда была предыдущая и когда следующая отправка
    @ReactMethod
    fun getScheduleState(promise: Promise) {
        try {
            val state = kmpController.getScheduleState()
            val map = Arguments.createMap().apply {
                putDouble("lastSentTimestamp", state.lastSentTimestamp?.toDouble() ?: -1.0)
                putDouble("nextScheduledTimestamp", state.nextScheduledTimestamp?.toDouble() ?: -1.0)
                putBoolean("isTrackingActive", state.isTrackingActive)
            }
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("STORAGE_ERROR", e.message)
        }
    }

    // Вызов проверки при старте / разряде/заряде батареи
    @ReactMethod
    fun checkAndSyncTracking(promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            val newState = kmpController.executePendingOrScheduledTracking()
            val map = Arguments.createMap().apply {
                putDouble("lastSentTimestamp", newState.lastSentTimestamp?.toDouble() ?: -1.0)
                putDouble("nextScheduledTimestamp", newState.nextScheduledTimestamp?.toDouble() ?: -1.0)
            }
            promise.resolve(map)
        }
    }
}

```

### iOS Native Module (`LocationTrackerModule.swift`)

```swift
@objc(LocationTrackerModule)
class LocationTrackerModule: NSObject {

  @objc fun getScheduleState(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    if let state = kmpController?.getScheduleState() {
      let result: [String: Any] = [
        "lastSentTimestamp": state.lastSentTimestamp ?? -1,
        "nextScheduledTimestamp": state.nextScheduledTimestamp ?? -1,
        "isTrackingActive": state.isTrackingActive
      ]
      resolve(result)
    } else {
      reject("ERROR", "Controller not initialized", nil)
    }
  }
  
  @objc fun checkAndSyncTracking(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    Task {
      if let newState = try? await kmpController?.executePendingOrScheduledTracking() {
        let result: [String: Any] = [
          "lastSentTimestamp": newState.lastSentTimestamp ?? -1,
          "nextScheduledTimestamp": newState.nextScheduledTimestamp ?? -1
        ]
        resolve(result)
      }
    }
  }
}

```

---

## 4. Обработка включения устройства (Android `BOOT_COMPLETED`)

Чтобы при включении телефона после разрядки нативный модуль сам возобновил работу, регистрируем `BroadcastReceiver` на Android:

```kotlin
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Проверяем кэш: если рейс активен, запускаем сервис и делаем проверку отправок
            val storage = SharedPrefsTrackingStorage(context)
            if (storage.isTrackingActive()) {
                LocationForegroundService.startService(context)
            }
        }
    }
}

```

---

## 5. Использование в React Native (JS/TS)

```typescript
import { NativeModules } from 'react-native';
const { LocationTracker } = NativeModules;

// 1. При загрузке приложения проверяем статус
const checkTrackingInfo = async () => {
  const schedule = await LocationTracker.getScheduleState();
  
  console.log("Предыдущая отправка:", schedule.lastSentTimestamp > 0 
    ? new Date(schedule.lastSentTimestamp).toLocaleString() 
    : "Еще не отправлялась");

  console.log("Следующая отправка:", schedule.nextScheduledTimestamp > 0 
    ? new Date(schedule.nextScheduledTimestamp).toLocaleString() 
    : "Не запланирована");
};

// 2. Вызов синхронизации (например, при выходе приложения из фона или включении сети)
const syncIfMissed = async () => {
  const updatedSchedule = await LocationTracker.checkAndSyncTracking();
  console.log("Синхронизировано. Следующая отправка в:", updatedSchedule.nextScheduledTimestamp);
};

```

### Как это работает суммарно:

1. Вы запускаете рейс: записывается `nextScheduledTimestamp` = Время погрузки − 1 час.
2. Каждые 30 минут нативный сервис берет гео, делает запрос, и в случае успеха KMP обновляет кэш-файл: `lastSentTimestamp` = `now`, `nextScheduledTimestamp` = `now + 30 min`.
3. **Если батарея села и телефон выключился:**
* При включении телефона нативный модуль читает кэш.
* Видит, что `currentTime >= nextScheduledTimestamp`.
* Модуль понимает, что интервал пропущен, снимает текущие координаты, сразу отправляет их на сервер, сохраняет `lastSentTimestamp` = `время отправки` и ставит новый `nextScheduledTimestamp` = `время + 30 мин`.