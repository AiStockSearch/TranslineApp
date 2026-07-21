/**
 * Пример: слушаем ВСЕ события геоворкера в JS-потоке.
 */
import React from "react";
import {
  startLocationService,
  useGeoWorkerEvents,
  useSystemBarStyle,
  GEO_WORKER_EVENT,
} from "../../../src/native";

export function GeoWorkerWithEvents({
  apiHost,
  driverUuid,
}: {
  apiHost: string;
  driverUuid: string;
}) {
  useSystemBarStyle();

  // Единый хук: все типы onGeoWorkerEvent
  useGeoWorkerEvents({
    onEvent: (e) => {
      console.log(`[${GEO_WORKER_EVENT}]`, e.type, e);
    },
    onLocationSent: (e) => {
      console.log("coords sent", e.latitude, e.longitude, e.timestamp);
    },
    onLocationFailed: (e) => {
      console.warn("send failed / queued", e.message);
    },
    onLocationServicesDisabled: () => {
      console.warn("GPS disabled");
    },
    onPermissionDenied: () => {
      console.warn("permission denied");
    },
  });

  React.useEffect(() => {
    void startLocationService(apiHost, driverUuid, "", 1);
  }, [apiHost, driverUuid]);

  return null;
}
