import { AppState, NativeModules, Platform } from "react-native";
import { useCallback, useEffect, useMemo, useRef } from "react";
import NativeLocationTracking from "./NativeLocationTracking";
import {
  GEO_WORKER_EVENT,
  subscribeToEvents,
  subscribeToGeoWorkerHandlers,
  subscribeToEventsPreferTurbo,
  useGeoWorkerEvents,
  type GeoEventType,
  type GeoEventPayload,
  type GeoEventHandlers,
} from "./GeoWorkerEvents";

/**
 * TurboModule Spec если New Arch, иначе classic NativeModules.LocationTracking.
 */
const LocationTracking =
  NativeLocationTracking ?? NativeModules.LocationTracking ?? null;

if (!LocationTracking) {
  console.warn(
    "[LocationTracker] LocationTracking is not linked. " +
      "Register GeoWorkerPackage / iOS LocationTrackerModule.",
  );
}

const ACTIVE_APP_LOCATION_LOG_INTERVAL_MS = 10_000;
const ACTIVE_APP_LOCATION_SEND_INTERVAL_MS = 60_000;
const DEFAULT_COORDINATES_BASIC_AUTH =
  "Basic dHJhbnNsaW5lX3VzZXI6VHJhbjMkU2wxMkBuZUA="; // deprecated: inject via authHeader / WithAuth

const ACTIVE_APP_LOCATION_LOG_OPTIONS = {
  enableHighAccuracy: true,
  timeout: 8000,
  maximumAge: 0,
};

export type LocationPermissionStatus = "1" | "2" | "3";

export type { GeoEventType, GeoEventPayload, GeoEventHandlers };

export {
  GEO_WORKER_EVENT,
  subscribeToEvents,
  subscribeToGeoWorkerHandlers,
  subscribeToEventsPreferTurbo,
  useGeoWorkerEvents,
};

export interface LocationCoordinates {
  latitude: number;
  longitude: number;
  speedMps?: number;
  timestamp: number;
}

export interface TrackingScheduleState {
  isTrackingActive: boolean;
  lastSentTimestamp: number | null;
  nextScheduledTimestamp: number | null;
}

/** Нормализация нативных map (null → -1 на iOS/Android) в nullable timestamps. */
export const normalizeScheduleState = (raw: {
  isTrackingActive?: boolean;
  lastSentTimestamp?: number | null;
  nextScheduledTimestamp?: number | null;
}): TrackingScheduleState => {
  const toNullable = (value: number | null | undefined): number | null => {
    if (value == null || value < 0) return null;
    return value;
  };
  return {
    isTrackingActive: Boolean(raw.isTrackingActive),
    lastSentTimestamp: toNullable(raw.lastSentTimestamp),
    nextScheduledTimestamp: toNullable(raw.nextScheduledTimestamp),
  };
};

type CoordinatesPayload = {
  latitude: number;
  longitude: number;
  /** GpsService OpenAPI field name. */
  speed_mps: number;
};

/** Нормализация host → `…/api/coordinates` (как в KMP normalizeCoordinatesEndpoint). */
export const getCoordinatesEndpoint = (host?: string | null): string | null => {
  const normalizedHost = host?.replace(/\/api\/?$/, "").replace(/\/$/, "");
  return normalizedHost ? `${normalizedHost}/api/coordinates` : null;
};

export const getDriverUuid = (user?: Record<string, unknown> | null): string | null => {
  if (!user) return null;
  const value =
    user.user_uuid ?? user.driver_uuid ?? user.uuid ?? user.id ?? null;
  return value == null ? null : String(value);
};

const getResponseObject = (responseText: string) => {
  if (!responseText) return null;
  try {
    return JSON.parse(responseText);
  } catch {
    return responseText;
  }
};

// --- Публичный API из ТЗ ---

export const startLocationService = async (
  apiEndpoint: string,
  driverUuid: string,
  orderNumber = "",
  updateIntervalMinutes?: number,
): Promise<void> => {
  await LocationTracking!.startLocationService(
    apiEndpoint,
    driverUuid,
    orderNumber,
    updateIntervalMinutes ?? null,
  );
};

export const saveLocationConfiguration = async (
  apiEndpoint: string,
  driverUuid: string,
  orderNumber = "",
  updateIntervalMinutes?: number,
): Promise<boolean> => {
  return await LocationTracking!.saveLocationConfiguration(
    apiEndpoint,
    driverUuid,
    orderNumber,
    updateIntervalMinutes ?? null,
  );
};

/** Передача Basic Auth из JS (рекомендация ТЗ §6). */
export const saveLocationConfigurationWithAuth = async (
  apiEndpoint: string,
  driverUuid: string,
  orderNumber = "",
  updateIntervalMinutes?: number,
  username?: string,
  password?: string,
): Promise<boolean> => {
  return await LocationTracking!.saveLocationConfigurationWithAuth(
    apiEndpoint,
    driverUuid,
    orderNumber,
    updateIntervalMinutes ?? null,
    username ?? null,
    password ?? null,
  );
};

/** GpsService / JWT: `Authorization: Bearer <accessToken>`. */
export const saveLocationConfigurationWithBearer = async (
  apiEndpoint: string,
  driverUuid: string,
  orderNumber = "",
  updateIntervalMinutes?: number,
  accessToken?: string,
): Promise<boolean> => {
  return await LocationTracking!.saveLocationConfigurationWithBearer(
    apiEndpoint,
    driverUuid,
    orderNumber,
    updateIntervalMinutes ?? null,
    accessToken ?? null,
  );
};

export const stopLocationService = async (): Promise<void> => {
  await LocationTracking!.stopLocationService();
};

export const requestLocationPermission = (): Promise<boolean> => {
  return LocationTracking!.requestLocationPermission();
};

export const requestForegroundServicePermission = (): Promise<boolean> => {
  return LocationTracking!.requestForegroundServicePermission();
};

export const hasRequiredPermissions = (): Promise<LocationPermissionStatus> => {
  return LocationTracking!.hasRequiredPermissions() as Promise<LocationPermissionStatus>;
};

export const getLocationPermissionStatus = (): Promise<LocationPermissionStatus> => {
  return LocationTracking!.getLocationPermissionStatus() as Promise<LocationPermissionStatus>;
};

export const isLocationServiceRunning = (): Promise<boolean> => {
  return LocationTracking!.isLocationServiceRunning();
};

export const isRegistrationLocked = (): Promise<boolean> => {
  return LocationTracking!.isRegistrationLocked();
};

// --- Рейс / утилиты (KMP merge) ---

export const getCurrentLocation = (): Promise<LocationCoordinates> => {
  return LocationTracking!.getCurrentLocation() as Promise<LocationCoordinates>;
};

export const openGpsSettings = (): Promise<boolean> => {
  return LocationTracking!.openGpsSettings();
};

export const initializeAndSyncOnAppStart = async (): Promise<TrackingScheduleState> => {
  const raw = await LocationTracking!.initializeAndSyncOnAppStart();
  return normalizeScheduleState(raw as TrackingScheduleState);
};

export const getScheduleState = async (): Promise<TrackingScheduleState> => {
  const raw = await LocationTracking!.getScheduleState();
  return normalizeScheduleState(raw as TrackingScheduleState);
};

export const checkAndSyncTracking = async (): Promise<TrackingScheduleState> => {
  const raw = await LocationTracking!.checkAndSyncTracking();
  return normalizeScheduleState(raw as TrackingScheduleState);
};

export const startTrip = (loadingTimeEpochMs: number): Promise<void> => {
  return LocationTracking!.startTrip(loadingTimeEpochMs);
};

export const completeTripAfterModeration = (): Promise<void> => {
  return LocationTracking!.completeTripAfterModeration();
};

// --- Secure config / HTTP probe (PKG-01, D-08) — no public getSecrets/load ---

export type SecureConfigInput = {
  access: string;
  refresh: string;
  endpointUrl?: string | null;
  /** Custom headers object; serialized to JSON for the native bridge. */
  headers?: Record<string, string> | null;
};

export type HttpProbeInput = {
  url: string;
  method: "GET" | "PATCH" | "PUT" | "POST" | string;
  body?: string | null;
};

export type HttpProbeSummary = {
  ok: boolean;
  method: string;
  url: string;
  status?: number;
  message: string;
};

/**
 * Persist D-01 secure blob (access + refresh + optional endpoint + headers).
 * Emits KEYCHAIN_SAVED / KEYCHAIN_ERROR — never echoes tokens.
 */
export const saveSecureConfig = async (
  input: SecureConfigInput,
): Promise<boolean> => {
  const headersJson =
    input.headers && Object.keys(input.headers).length > 0
      ? JSON.stringify(input.headers)
      : null;
  return await LocationTracking!.saveSecureConfig(
    input.access,
    input.refresh,
    input.endpointUrl ?? null,
    headersJson,
  );
};

/** Thin alias → saveSecureConfig preserving other blob fields on native. */
export const saveTokens = async (
  access: string,
  refresh: string,
): Promise<boolean> => {
  return await LocationTracking!.saveTokens(access, refresh);
};

/** Clears SecureConfigStore only (D-06). */
export const clearSecrets = async (): Promise<boolean> => {
  return await LocationTracking!.clearSecrets();
};

/**
 * KMP HTTP probe. Returns summary without body; events are source of truth.
 */
export const httpProbe = async (
  input: HttpProbeInput,
): Promise<HttpProbeSummary> => {
  const raw = await LocationTracking!.httpProbe(
    input.url,
    input.method,
    input.body ?? null,
  );
  const summary = raw as HttpProbeSummary;
  return {
    ok: Boolean(summary.ok),
    method: String(summary.method ?? input.method),
    url: String(summary.url ?? input.url),
    status: summary.status,
    message: String(summary.message ?? ""),
  };
};

export type NotifyI18nBundlePayload = {
  locale: string;
  strings: Record<string, string>;
};

export type TripNotifyPointPayload = {
  type: string;
  address?: string;
  dateEpochMs?: number | null;
  lat?: number | null;
  lon?: number | null;
};

export type TripNotifySessionPayload = {
  orderId: string;
  driverUuid?: string;
  locale?: string;
  loadingTimeEpochMs?: number;
  firstTrackingEpochMs?: number;
  intervalMinutes?: number;
  points?: TripNotifyPointPayload[];
  notifyKeys?: Record<string, string>;
};

/** Replace native notify i18n dictionary for the active app locale (any keys). */
export const setNotifyI18nBundle = async (
  payload: NotifyI18nBundlePayload,
): Promise<void> => {
  await LocationTracking!.setNotifyI18nBundle(
    payload.locale,
    JSON.stringify(payload.strings ?? {}),
  );
};

export const getNotifyI18nBundle = async (): Promise<{
  locale: string;
  strings: Record<string, string>;
  updatedAtEpochMs?: number;
} | null> => {
  const raw = await LocationTracking!.getNotifyI18nBundle();
  if (raw == null) return null;
  const obj = raw as {
    locale?: string;
    strings?: Record<string, string>;
    updatedAtEpochMs?: number;
  };
  return {
    locale: String(obj.locale ?? ""),
    strings: obj.strings ?? {},
    updatedAtEpochMs: obj.updatedAtEpochMs,
  };
};

export const clearNotifyI18nBundle = async (): Promise<void> => {
  await LocationTracking!.clearNotifyI18nBundle();
};

export const saveTripNotifySession = async (
  session: TripNotifySessionPayload,
): Promise<void> => {
  await LocationTracking!.saveTripNotifySession(JSON.stringify(session));
};

export const getTripNotifySession =
  async (): Promise<TripNotifySessionPayload | null> => {
    const raw = await LocationTracking!.getTripNotifySession();
    if (raw == null) return null;
    return raw as TripNotifySessionPayload;
  };

export const clearTripNotifySession = async (): Promise<void> => {
  await LocationTracking!.clearTripNotifySession();
};

/** @deprecated используйте именованные экспорты; оставлен для совместимости */
export const LocationTrackerService = {
  getCurrentLocation,
  openGpsSettings,
  checkPermissions: (): Promise<"GRANTED" | "DENIED" | "NOT_DETERMINED"> =>
    LocationTracking!.requestLocationPermissions() as Promise<
      "GRANTED" | "DENIED" | "NOT_DETERMINED"
    >,
  initializeAndSyncOnAppStart,
  getScheduleState,
  checkAndSyncTracking,
  startTrip,
  completeTripAfterModeration,
  subscribeToEvents,
  startLocationService,
  saveLocationConfiguration,
  saveLocationConfigurationWithAuth,
  saveLocationConfigurationWithBearer,
  stopLocationService,
  isLocationServiceRunning,
  isRegistrationLocked,
  hasRequiredPermissions,
  getLocationPermissionStatus,
  requestLocationPermission,
  saveSecureConfig,
  saveTokens,
  clearSecrets,
  httpProbe,
  setNotifyI18nBundle,
  getNotifyI18nBundle,
  clearNotifyI18nBundle,
  saveTripNotifySession,
  getTripNotifySession,
  clearTripNotifySession,
};

export type StartLocationServiceDeps = {
  apiHost: string | null | undefined;
  driverUuid: string | null | undefined;
  defaultIntervalMinutes?: number;
};

/**
 * Хук запуска без жёсткой зависимости от jotai/token-manager хост-приложения.
 * Передайте host/uuid из своего стора.
 */
export const useStartLocationService = (deps: StartLocationServiceDeps) => {
  const { apiHost, driverUuid, defaultIntervalMinutes } = deps;

  const getHostWithoutApi = useCallback(
    () => getCoordinatesEndpoint(apiHost),
    [apiHost],
  );

  const saveConfiguration = useCallback(
    async (orderNumber = "", updateIntervalMinutes?: number) => {
      const hostWithoutApi = getHostWithoutApi();
      if (!hostWithoutApi || !driverUuid) return;

      const intervalToUse = updateIntervalMinutes ?? defaultIntervalMinutes;
      return await saveLocationConfiguration(
        hostWithoutApi,
        driverUuid,
        orderNumber,
        intervalToUse,
      );
    },
    [driverUuid, defaultIntervalMinutes, getHostWithoutApi],
  );

  const start = useCallback(
    async (orderNumber = "", updateIntervalMinutes?: number) => {
      if (!driverUuid) return;
      const hostWithoutApi = getHostWithoutApi();
      if (!hostWithoutApi) return;

      const intervalToUse = updateIntervalMinutes ?? defaultIntervalMinutes;
      return await startLocationService(
        hostWithoutApi,
        driverUuid,
        orderNumber,
        intervalToUse,
      );
    },
    [driverUuid, defaultIntervalMinutes, getHostWithoutApi],
  );

  const checkIsRunning = useCallback(async () => isLocationServiceRunning(), []);

  return { start, checkIsRunning, saveConfiguration };
};

export type ForegroundLoggerDeps = {
  apiHost: string | null | undefined;
  driverUuid: string | null | undefined;
  authHeader?: string;
  /**
   * Опционально: модуль `@react-native-community/geolocation`.
   * Если не передан — хук no-op (нативный continuous tracking остаётся основным).
   */
  Geolocation?: {
    getCurrentPosition: (
      success: (position: {
        coords: { latitude: number; longitude: number; speed: number | null };
      }) => void,
      error: (error: { code: number; message: string }) => void,
      options?: object,
    ) => void;
  };
};

/**
 * Foreground poller из ТЗ: опрос каждые 10 с, отправка каждые 60 с при AppState === active.
 */
export const useForegroundLocationLogger = (deps: ForegroundLoggerDeps) => {
  const {
    apiHost,
    driverUuid,
    authHeader = DEFAULT_COORDINATES_BASIC_AUTH,
    Geolocation,
  } = deps;

  const coordinatesEndpoint = useMemo(
    () => getCoordinatesEndpoint(apiHost),
    [apiHost],
  );
  const intervalIdRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const lastSendAttemptAtRef = useRef(0);
  const isPollingRef = useRef(false);
  const isSendingRef = useRef(false);
  const isStartingRef = useRef(false);

  const sendCoordinates = useCallback(
    async (coordinates: CoordinatesPayload) => {
      if (!coordinatesEndpoint || !driverUuid || isSendingRef.current) {
        return;
      }

      isSendingRef.current = true;
      const requestBody = { ...coordinates, driver_uuid: driverUuid };

      try {
        const response = await fetch(coordinatesEndpoint, {
          method: "POST",
          headers: {
            Authorization: authHeader,
            "Content-Type": "application/json",
          },
          body: JSON.stringify(requestBody),
        });
        const responseText = await response.text();
        console.log("response:", getResponseObject(responseText));
      } catch (error) {
        console.log("response:", { error: String(error) });
      } finally {
        isSendingRef.current = false;
      }
    },
    [coordinatesEndpoint, driverUuid, authHeader],
  );

  const pollLocation = useCallback(() => {
    if (!Geolocation || isPollingRef.current || AppState.currentState !== "active") {
      return;
    }

    isPollingRef.current = true;

    Geolocation.getCurrentPosition(
      (position) => {
        const { latitude, longitude, speed } = position.coords;
        const coordinates: CoordinatesPayload = {
          latitude,
          longitude,
          speed_mps: Math.max(speed ?? 0, 0),
        };
        const currentTime = Date.now();

        isPollingRef.current = false;
        console.log(`local logging - coordinates: ${JSON.stringify(coordinates)}`);

        if (
          coordinatesEndpoint &&
          driverUuid &&
          currentTime - lastSendAttemptAtRef.current >= ACTIVE_APP_LOCATION_SEND_INTERVAL_MS
        ) {
          lastSendAttemptAtRef.current = currentTime;
          void sendCoordinates(coordinates);
        }
      },
      (error) => {
        isPollingRef.current = false;
        console.log("[LocationTracker] Active app coordinate logger error", {
          code: error.code,
          message: error.message,
        });
      },
      ACTIVE_APP_LOCATION_LOG_OPTIONS,
    );
  }, [Geolocation, coordinatesEndpoint, driverUuid, sendCoordinates]);

  const stopPolling = useCallback(() => {
    if (intervalIdRef.current === null) return;
    clearInterval(intervalIdRef.current);
    intervalIdRef.current = null;
  }, []);

  const startPolling = useCallback(async () => {
    if (!Geolocation || intervalIdRef.current !== null || isStartingRef.current) {
      return;
    }

    isStartingRef.current = true;

    try {
      const permissionStatus = await getLocationPermissionStatus();

      if (
        permissionStatus === "2" ||
        AppState.currentState !== "active" ||
        intervalIdRef.current !== null
      ) {
        if (permissionStatus === "2") {
          console.log(
            "[LocationTracker] Active app coordinate logger permission denied",
          );
        }
        return;
      }

      pollLocation();
      intervalIdRef.current = setInterval(
        pollLocation,
        ACTIVE_APP_LOCATION_LOG_INTERVAL_MS,
      );

      console.log("[LocationTracker] Active app coordinate logger started", {
        intervalMs: ACTIVE_APP_LOCATION_LOG_INTERVAL_MS,
        permissionStatus,
        platform: Platform.OS,
      });
    } catch (error) {
      console.log("[LocationTracker] Active app coordinate logger unavailable", error);
    } finally {
      isStartingRef.current = false;
    }
  }, [Geolocation, pollLocation]);

  useEffect(() => {
    if (!Geolocation) return undefined;

    if (AppState.currentState === "active") {
      void startPolling();
    }

    const subscription = AppState.addEventListener("change", (nextAppState) => {
      if (nextAppState === "active") {
        void startPolling();
        return;
      }
      stopPolling();
    });

    return () => {
      subscription.remove();
      stopPolling();
    };
  }, [Geolocation, startPolling, stopPolling]);
};
