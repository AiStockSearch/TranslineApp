import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

const { LocationTracker } = NativeModules;
const geoEventEmitter = new NativeEventEmitter(LocationTracker);

export type GeoEventType = 
  | 'LOCATION_SENT' 
  | 'LOCATION_FAILED' 
  | 'LOCATION_SERVICES_DISABLED' 
  | 'PERMISSION_DENIED';

export interface GeoEventPayload {
  type: GeoEventType;
  latitude?: number;
  longitude?: number;
  timestamp?: number;
  message?: string;
}

export interface LocationCoordinates {
  latitude: number;
  longitude: number;
  timestamp: number;
}

export interface TrackingScheduleState {
  isTrackingActive: boolean;
  lastSentTimestamp: number | null;
  nextScheduledTimestamp: number | null;
}

export const LocationTrackerService = {
  /**
   * Запросить текущие геокоординаты водителя прямо сейчас
   */
  getCurrentLocation: async (): Promise<LocationCoordinates> => {
    return await LocationTracker.getCurrentLocation();
  },

  /**
   * Открыть системные настройки устройства/приложения для включения геолокации
   */
  openGpsSettings: async (): Promise<boolean> => {
    return await LocationTracker.openGpsSettings();
  },

  /**
   * Проверить статус разрешений
   */
  checkPermissions: async (): Promise<'GRANTED' | 'DENIED' | 'NOT_DETERMINED'> => {
    return await LocationTracker.requestLocationPermissions();
  },

  /**
   * Инициализация при старте приложения:
   * - Проверяет офлайн-очередь и отправляет накопленные данные
   * - Проверяет пропущенные интервалы
   * - Возвращает текущее состояние расписания
   */
  initializeAndSyncOnAppStart: async (): Promise<TrackingScheduleState> => {
    return await LocationTracker.initializeAndSyncOnAppStart();
  },

  /**
   * Получить текущее состояние расписания трекинга
   */
  getScheduleState: async (): Promise<TrackingScheduleState> => {
    return await LocationTracker.getScheduleState();
  },

  /**
   * Принудительно выполнить проверку и отправку (ручная синхронизация)
   */
  checkAndSyncTracking: async (): Promise<TrackingScheduleState> => {
    return await LocationTracker.checkAndSyncTracking();
  },

  /**
   * Подписка на сквозные события от нативного модуля (Android/iOS)
   */
  subscribeToEvents: (listener: (event: GeoEventPayload) => void) => {
    const subscription = geoEventEmitter.addListener('onGeoWorkerEvent', listener);
    return () => subscription.remove();
  }
};
