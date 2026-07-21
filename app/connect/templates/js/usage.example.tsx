/**
 * Пример использования в хост RN-приложении.
 * Скопируйте src/native из TranslineGeoWorker или импортируйте path-зависимость.
 *
 * Notify Manager (кастомные пуши + NotifyRouter): см. notify-bootstrap.example.tsx
 * и docs/15-notify-manager.md.
 */
import React, { useEffect } from 'react';
import Geolocation from '@react-native-community/geolocation';
import {
  startLocationService,
  stopLocationService,
  requestLocationPermission,
  hasRequiredPermissions,
  useForegroundLocationLogger,
  useSystemBarStyle,
  subscribeToEvents,
} from '../../../src/native'; // поправьте путь

export function GeoWorkerBootstrap({
  apiHost,
  driverUuid,
}: {
  apiHost: string;
  driverUuid: string;
}) {
  useSystemBarStyle();

  useForegroundLocationLogger({
    apiHost,
    driverUuid,
    Geolocation,
  });

  useEffect(() => {
    const unsubscribe = subscribeToEvents((event) => {
      console.log('[GeoWorker]', event.type, event);
    });
    return unsubscribe;
  }, []);

  useEffect(() => {
    (async () => {
      try {
        await requestLocationPermission();
        const status = await hasRequiredPermissions();
        if (status !== '1') {
          console.warn('Need Always location permission, status=', status);
          return;
        }
        await startLocationService(apiHost, driverUuid, '', 1);
      } catch (e) {
        console.error('GeoWorker start failed', e);
      }
    })();

    return () => {
      void stopLocationService();
    };
  }, [apiHost, driverUuid]);

  return null;
}
