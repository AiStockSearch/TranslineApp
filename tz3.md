Для того чтобы закрыть весь жизненный цикл модуля — от первой инициализации до отправки локаций из офлайн-очереди и push-уведомлений — необходимо добавить **3 завершающих архитектурных блока**.

---

## 1. Сквозная инициализация при старте приложения

Когда пользователь открывает React Native приложение, нативный модуль должен:

1. Инициализировать KMP-контроллер, БД и фоновые сервисы.
2. Автоматически запустить **проверку и отправку накопленных офлайн-данных** (если интернет появился).
3. Вернуть текущее состояние рейса (активен ли трекинг, когда следующая отправка).

### KMP метод авто-синхронизации (`LocationTrackerController.kt`)

```kotlin
package org.transline.geoworker

class LocationTrackerController(
    private val locationProvider: PlatformLocationProvider,
    private val apiService: LocationApiService,
    private val storage: TrackingStorage,
    private val offlineRepository: LocationRepository
) {
    // Вызывается сразу при старте приложения / инициализации модуля
    suspend fun initializeAndSyncOnAppStart(): TrackingScheduleState {
        // 1. Проверяем и отправляем накопившуюся офлайн-очередь из БД
        offlineRepository.flushOfflineQueue()

        // 2. Проверяем, не пропущен ли 30-минутный интервал (пока телефон был выключен/приложение закрыто)
        executePendingOrScheduledTracking()

        // 3. Возвращаем актуальное состояние в React Native
        return getScheduleState()
    }
}

```

---

## 2. Локальные push-уведомления (Уведомлялки для водителя)

Чтобы водитель видел системные уведомления (*«Геокоординаты успешно отправлены на сервер»* или *«Нет сети. Данные сохранены локально»*), используем **Local Notifications** на Android и iOS.

### Android (`NotificationManager`)

```kotlin
package org.transline.geoworker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

class GeoNotificationHelper(private val context: Context) {

    private val channelId = "geo_worker_channel"

    init {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Трекинг геолокации",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = context.getSystemService(Context::NOTIFICATION_SERVICE.toString()) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showSuccessNotification(lat: Double, lon: Double) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Транслайн Гео")
            .setContentText("Геокоординаты успешно отправлены на сервер ($lat, $lon)")
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context::NOTIFICATION_SERVICE.toString()) as NotificationManager
        manager.notify(1001, notification)
    }
}

```

### iOS (`UNUserNotificationCenter`)

```swift
import UserNotifications

class IOSNotificationHelper {
    
    static func requestAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    static func showSuccessNotification(lat: Double, lon: Double) {
        let content = UNMutableNotificationContent()
        content.title = "Транслайн Гео"
        content.body = "Геокоординаты успешно отправлены на сервер (\(lat), \(lon))"
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: nil // Показывать немедленно
        )

        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }
}

```

---

## 3. Полный жизненный цикл в React Native (От инициализации до отправки)

Вот как выглядит **финальный сценарий работы** на стороне React Native (JS/TS):

```tsx
import React, { useEffect, useState } from 'react';
import { View, Text, Button, Alert } from 'react-native';
import { LocationTrackerService, TrackingScheduleState } from './src/native/LocationTrackerService';

export const AppLocationModule = () => {
  const [scheduleState, setScheduleState] = useState<TrackingScheduleState | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);

  // -------------------------------------------------------------
  // ШАГ 1: ИНИЦИАЛИЗАЦИЯ ПРИ ЗАГРУЗКЕ ПРИЛОЖЕНИЯ
  // -------------------------------------------------------------
  useEffect(() => {
    const initGeoWorker = async () => {
      try {
        setIsLoading(true);

        // 1. Запрашиваем/проверяем разрешения
        await LocationTrackerService.checkPermissions();

        // 2. Инициализируем модуль:
        //    - Выгружает офлайн-очередь на сервер (если появился интернет)
        //    - Проверяет пропущенные таймеры
        //    - Возвращает текущее состояние расписания
        const state = await LocationTrackerService.initializeAndSyncOnAppStart();
        setScheduleState(state);

      } catch (error) {
        console.error('Ошибка инициализации GeoWorker:', error);
      } finally {
        setIsLoading(false);
      }
    };

    // 3. Подписываемся на фоновые события (EventEmitter)
    const unsubscribe = LocationTrackerService.subscribeToEvents((event) => {
      if (event.type === 'LOCATION_SENT') {
        console.log('Гео отправлено в фоне:', event);
        // Обновляем время отправок на UI
        LocationTrackerService.getScheduleState().then(setScheduleState);
      }
      
      if (event.type === 'LOCATION_SERVICES_DISABLED') {
        Alert.alert('Внимание', 'GPS выключен! Включите геолокацию.', [
          { text: 'Настройки', onPress: () => LocationTrackerService.openGpsSettings() }
        ]);
      }
    });

    initGeoWorker();

    return () => unsubscribe();
  }, []);

  // -------------------------------------------------------------
  // ШАГ 2: РУЧНАЯ ОТПРАВКА ПО КНОПКЕ ИЛИ СОБЫТИЮ
  // -------------------------------------------------------------
  const handleForceSendLocation = async () => {
    try {
      setIsLoading(true);
      // Принудительно снимаем гео и отправляем (с сохранением в офлайн-БД если нет сети)
      const updatedSchedule = await LocationTrackerService.checkAndSyncTracking();
      setScheduleState(updatedSchedule);
      Alert.alert('Успех', 'Запрос на отправку геопозиции обработан');
    } catch (error) {
      Alert.alert('Ошибка', 'Не удалось отправить геолокацию');
    } finally {
      setIsLoading(false);
    }
  };

  if (isLoading) {
    return <Text>Загрузка и проверка офлайн-очереди...</Text>;
  }

  return (
    <View style={{ padding: 20 }}>
      <Text style={{ fontSize: 18, fontWeight: 'bold' }}>
        Статус рейса: {scheduleState?.isTrackingActive ? 'АКТИВЕН' : 'НЕ АКТИВЕН'}
      </Text>

      <Text style={{ marginTop: 10 }}>
        Предыдущая отправка:{' '}
        {scheduleState?.lastSentTimestamp 
          ? new Date(scheduleState.lastSentTimestamp).toLocaleString() 
          : 'Ещё не было'}
      </Text>

      <Text style={{ marginTop: 5 }}>
        Следующая отправка:{' '}
        {scheduleState?.nextScheduledTimestamp 
          ? new Date(scheduleState.nextScheduledTimestamp).toLocaleString() 
          : 'Не запланирована'}
      </Text>

      <View style={{ marginTop: 20 }}>
        <Button 
          title="Отправить геолокацию сейчас" 
          onPress={handleForceSendLocation} 
        />
      </View>
    </View>
  );
};

```

---

### Чек-лист готового модуля:

1. **Запуск рейса:** Вызывается метод `startTripTracking(loadingTime)` $\rightarrow$ Записывается время $T - 1\text{ час}$ в локальный кэш.
2. **Фоновый процесс:** Раз в 30 минут / 500 метров запускается снимание GPS.
3. **Проверка сети:**
* Есть сеть $\rightarrow$ Запрос летит на сервер $\rightarrow$ Вызывается push-уведомление + `EventEmitter` в JS.
* Нет сети / ошибка $\rightarrow$ Точка сохраняется в офлайн-очередь (SQLite/Файл).


4. **Запуск приложения / Рестарт устройства:** При открытии приложения или включении смартфона вызывается `initializeAndSyncOnAppStart()`, который моментально проталкивает всю скопившуюся офлайн-очередь на сервер.
5. **Финал:** После модерации фото вызывается `onFinalModerationPassed()`, который отправляет финальную точку и навсегда останавливает сервисы.