/**
 * JS-слой Event Emitter: подписка на все события LocationTracking.
 *
 * Натив шлёт одно событие `onGeoWorkerEvent` с полем `type`.
 * Здесь — единая точка прослушивания + хук с колбэками по типам.
 */
import { useEffect, useRef } from "react";
import {
  NativeEventEmitter,
  NativeModules,
  type EmitterSubscription,
  type NativeModule,
} from "react-native";
import NativeLocationTracking from "./NativeLocationTracking";

/** Имя канала DeviceEventEmitter / RCTEventEmitter */
export const GEO_WORKER_EVENT = "onGeoWorkerEvent" as const;

export type GeoEventType =
  | "LOCATION_SENT"
  | "LOCATION_FAILED"
  | "LOCATION_SERVICES_DISABLED"
  | "PERMISSION_DENIED"
  | "HTTP_OK"
  | "HTTP_FAILED"
  | "KEYCHAIN_SAVED"
  | "KEYCHAIN_CLEARED"
  | "KEYCHAIN_ERROR"
  | "AUTH_MISSING";

/** Event payload — type/method/url/status/message only; never response body or tokens. */
export interface GeoEventPayload {
  type: GeoEventType | string;
  latitude?: number;
  longitude?: number;
  timestamp?: number;
  message?: string;
  method?: string;
  url?: string;
  status?: number;
}

export type GeoEventHandlers = {
  /** Все события подряд */
  onEvent?: (event: GeoEventPayload) => void;
  onLocationSent?: (event: GeoEventPayload) => void;
  onLocationFailed?: (event: GeoEventPayload) => void;
  onLocationServicesDisabled?: (event: GeoEventPayload) => void;
  onPermissionDenied?: (event: GeoEventPayload) => void;
  onHttpOk?: (event: GeoEventPayload) => void;
  onHttpFailed?: (event: GeoEventPayload) => void;
  onKeychainSaved?: (event: GeoEventPayload) => void;
  onKeychainCleared?: (event: GeoEventPayload) => void;
  onKeychainError?: (event: GeoEventPayload) => void;
  onAuthMissing?: (event: GeoEventPayload) => void;
};

function resolveNativeModule(): NativeModule | null {
  const turbo = NativeLocationTracking as unknown as NativeModule | null;
  if (turbo) return turbo;
  return (NativeModules.LocationTracking as NativeModule) ?? null;
}

function createEmitter(): NativeEventEmitter | null {
  const mod = resolveNativeModule();
  if (!mod) {
    console.warn(
      "[GeoWorkerEvents] LocationTracking is not linked — events unavailable",
    );
    return null;
  }
  return new NativeEventEmitter(mod);
}

/**
 * Подписка на поток `onGeoWorkerEvent` (все типы).
 * @returns unsubscribe
 */
export function subscribeToEvents(
  listener: (event: GeoEventPayload) => void,
): () => void {
  const emitter = createEmitter();
  if (!emitter) {
    return () => undefined;
  }

  const subscription: EmitterSubscription = emitter.addListener(
    GEO_WORKER_EVENT,
    (raw: GeoEventPayload) => {
      listener(raw);
    },
  );

  return () => subscription.remove();
}

/**
 * Подписка с разнесением по типам событий.
 */
export function subscribeToGeoWorkerHandlers(
  handlers: GeoEventHandlers,
): () => void {
  return subscribeToEvents((event) => {
    handlers.onEvent?.(event);
    switch (event.type) {
      case "LOCATION_SENT":
        handlers.onLocationSent?.(event);
        break;
      case "LOCATION_FAILED":
        handlers.onLocationFailed?.(event);
        break;
      case "LOCATION_SERVICES_DISABLED":
        handlers.onLocationServicesDisabled?.(event);
        break;
      case "PERMISSION_DENIED":
        handlers.onPermissionDenied?.(event);
        break;
      case "HTTP_OK":
        handlers.onHttpOk?.(event);
        break;
      case "HTTP_FAILED":
        handlers.onHttpFailed?.(event);
        break;
      case "KEYCHAIN_SAVED":
        handlers.onKeychainSaved?.(event);
        break;
      case "KEYCHAIN_CLEARED":
        handlers.onKeychainCleared?.(event);
        break;
      case "KEYCHAIN_ERROR":
        handlers.onKeychainError?.(event);
        break;
      case "AUTH_MISSING":
        handlers.onAuthMissing?.(event);
        break;
      default:
        break;
    }
  });
}

/**
 * Хук: слушает все geo-события в JS-потоке, пока компонент смонтирован.
 *
 * @example
 * useGeoWorkerEvents({
 *   onLocationSent: (e) => console.log(e.latitude, e.longitude),
 *   onLocationFailed: (e) => console.warn(e.message),
 *   onEvent: (e) => analytics.track(e.type),
 * });
 */
export function useGeoWorkerEvents(handlers: GeoEventHandlers): void {
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;

  useEffect(() => {
    return subscribeToGeoWorkerHandlers({
      onEvent: (e) => handlersRef.current.onEvent?.(e),
      onLocationSent: (e) => handlersRef.current.onLocationSent?.(e),
      onLocationFailed: (e) => handlersRef.current.onLocationFailed?.(e),
      onLocationServicesDisabled: (e) =>
        handlersRef.current.onLocationServicesDisabled?.(e),
      onPermissionDenied: (e) => handlersRef.current.onPermissionDenied?.(e),
      onHttpOk: (e) => handlersRef.current.onHttpOk?.(e),
      onHttpFailed: (e) => handlersRef.current.onHttpFailed?.(e),
      onKeychainSaved: (e) => handlersRef.current.onKeychainSaved?.(e),
      onKeychainCleared: (e) => handlersRef.current.onKeychainCleared?.(e),
      onKeychainError: (e) => handlersRef.current.onKeychainError?.(e),
      onAuthMissing: (e) => handlersRef.current.onAuthMissing?.(e),
    });
  }, []);
}

/**
 * New Arch: подписка через codegen EventEmitter `onGeoWorkerEvent`, если доступен.
 * Fallback — NativeEventEmitter.
 */
export function subscribeToEventsPreferTurbo(
  listener: (event: GeoEventPayload) => void,
): () => void {
  const turbo = NativeLocationTracking as {
    onGeoWorkerEvent?: (cb: (e: GeoEventPayload) => void) => { remove: () => void };
  } | null;

  if (turbo?.onGeoWorkerEvent) {
    const sub = turbo.onGeoWorkerEvent(listener);
    return () => sub.remove();
  }

  return subscribeToEvents(listener);
}
