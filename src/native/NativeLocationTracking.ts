/**
 * TurboModule Spec для NativeModules / TurboModuleRegistry `LocationTracking`.
 *
 * Имя файла ДОЛЖНО начинаться с `Native` — иначе codegen его проигнорирует.
 * После подключения пакета в RN-хост с New Architecture:
 *   npx react-native codegen  (или сборка android/ios сгенерирует Spec)
 *
 * События:
 * - New Arch: `onGeoWorkerEvent` (Codegen EventEmitter)
 * - Old Bridge / NativeEventEmitter: канал `"onGeoWorkerEvent"` + addListener/removeListeners
 */
import type { TurboModule } from "react-native";
import { TurboModuleRegistry } from "react-native";
import type { EventEmitter } from "react-native/Libraries/Types/CodegenTypes";
import type { UnsafeObject } from "react-native/Libraries/Types/CodegenTypes";

/** HTTP method allowlist for httpProbe (KMP HttpProbe). */
export type HttpProbeMethod = "GET" | "PATCH" | "PUT" | "POST";

/** Payload единого события геоворкера (поле type обязательно). Никогда body/tokens. */
export type GeoWorkerEvent = {
  type: string;
  latitude?: number;
  longitude?: number;
  timestamp?: number;
  message?: string;
  /** HTTP method for HTTP_OK / HTTP_FAILED (D-04). */
  method?: string;
  /** Redacted request URL for HTTP_* events (D-04). */
  url?: string;
  /** HTTP status when available. */
  status?: number;
};

export interface Spec extends TurboModule {
  // --- Continuous (ТЗ) ---
  saveLocationConfiguration(
    apiEndpoint: string,
    driverUuid: string,
    orderNumber: string,
    updateIntervalMinutes: number | null,
  ): Promise<boolean>;

  saveLocationConfigurationWithAuth(
    apiEndpoint: string,
    driverUuid: string,
    orderNumber: string,
    updateIntervalMinutes: number | null,
    username: string | null,
    password: string | null,
  ): Promise<boolean>;

  /** GpsService / JWT: Authorization Bearer. */
  saveLocationConfigurationWithBearer(
    apiEndpoint: string,
    driverUuid: string,
    orderNumber: string,
    updateIntervalMinutes: number | null,
    accessToken: string | null,
  ): Promise<boolean>;

  startLocationService(
    apiEndpoint: string,
    driverUuid: string,
    orderNumber: string,
    updateIntervalMinutes: number | null,
  ): Promise<boolean>;

  stopLocationService(): Promise<boolean>;

  isLocationServiceRunning(): Promise<boolean>;

  /** Trip registration soft-lock (endpoint/uuid/authHeader/interval). */
  isRegistrationLocked(): Promise<boolean>;

  requestLocationPermission(): Promise<boolean>;

  requestForegroundServicePermission(): Promise<boolean>;

  /** "1" | "2" | "3" */
  hasRequiredPermissions(): Promise<string>;

  /** "1" | "2" | "3" */
  getLocationPermissionStatus(): Promise<string>;

  // --- Утилиты / рейс ---
  getCurrentLocation(): Promise<UnsafeObject>;

  openGpsSettings(): Promise<boolean>;

  /** Legacy: "GRANTED" | "DENIED" | "NOT_DETERMINED" */
  requestLocationPermissions(): Promise<string>;

  initializeAndSyncOnAppStart(): Promise<UnsafeObject>;

  getScheduleState(): Promise<UnsafeObject>;

  checkAndSyncTracking(): Promise<UnsafeObject>;

  startTrip(loadingTimeEpochMs: number): Promise<void>;

  completeTripAfterModeration(): Promise<void>;

  // --- Secure config / HTTP probe (PKG-01, D-08) — no public getSecrets/load ---
  saveSecureConfig(
    access: string,
    refresh: string,
    endpointUrl: string | null,
    headersJson: string | null,
  ): Promise<boolean>;

  saveTokens(access: string, refresh: string): Promise<boolean>;

  clearSecrets(): Promise<boolean>;

  /**
   * Promise summary: ok/method/url/status/message — never response body.
   * Events remain source of truth.
   */
  httpProbe(
    url: string,
    method: HttpProbeMethod | string,
    body: string | null,
  ): Promise<UnsafeObject>;

  /**
   * New Architecture EventEmitter (codegen).
   * Подписка: NativeLocationTracking.onGeoWorkerEvent(cb)
   */
  readonly onGeoWorkerEvent: EventEmitter<GeoWorkerEvent>;

  /**
   * Обязательны для NativeEventEmitter(module) на TurboModule / New Arch.
   * На Old Bridge iOS их даёт RCTEventEmitter; на Android — явные @ReactMethod.
   */
  addListener(eventName: string): void;

  removeListeners(count: number): void;
}

/**
 * TurboModule при New Arch; иначе null → fallback на NativeModules.LocationTracking.
 */
export default TurboModuleRegistry.get<Spec>("LocationTracking");
