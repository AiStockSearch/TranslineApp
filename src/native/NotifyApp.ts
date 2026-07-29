/**
 * JS facade for KMP Notify Manager (`NotifyApp`).
 *
 * Business logic lives in KMP; this file is a thin bridge only.
 * Navigation: use NotifyRouter (register deepLink patterns → host navigate).
 *
 * New Arch: resolve via TurboModule Spec (NativeNotifyApp) first — classic
 * `NativeModules.NotifyApp` is often undefined under bridgeless even when linked.
 */
import { useEffect, useRef } from "react";
import {
  NativeEventEmitter,
  NativeModules,
  type EmitterSubscription,
  type NativeModule,
} from "react-native";

import NativeLocationTracking from "./NativeLocationTracking";
import NativeNotifyAppSpec from "./NativeNotifyApp";

export const NOTIFY_APP_EVENT = "onNotifyAppEvent" as const;

/** FCM/APNs `data.tl_notify` value — ownership marker for coexistence with Firebase. */
export const NOTIFY_OWNER_MARKER_KEY = "tl_notify" as const;
export const NOTIFY_OWNER_MARKER_VALUE = "1" as const;

/** Recommended FCM/APNs `data.type` for Notify Manager. */
export const NOTIFY_REMOTE_TYPE = "notify_app" as const;

export type NotifyActionId = "read" | "open" | "close" | "snooze";

export type NotifyAction = {
  id: NotifyActionId;
  title: string;
  /** Per-button deep link (wins over payload.deepLink). */
  deepLink?: string;
  /** Optional logical route name for host / NotifyRouter. */
  route?: string;
  /** Optional params for route / navigation. */
  params?: Record<string, string>;
};

export type NotifyPayload = {
  id: string;
  title: string;
  body: string;
  imageUrl?: string;
  deepLink?: string;
  channelId?: string;
  actions?: NotifyAction[];
  data?: Record<string, string>;
  snoozeMinutes?: number;
  /** Route hub key for shade aggregation (e.g. app://sbc). */
  groupKey?: string;
  /** Entity id within groupKey (e.g. "42"). */
  entityId?: string;
};

export type NotifyEventType =
  | "SHOWN"
  | "CANCELLED"
  | "ACTION_READ"
  | "ACTION_OPEN"
  | "ACTION_CLOSE"
  | "ACTION_SNOOZE";

export type NotifyEventPayload = {
  type: NotifyEventType | string;
  id?: string;
  title?: string;
  actionId?: string;
  actionTitle?: string;
  deepLink?: string;
  route?: string;
  params?: Record<string, string>;
  paramsJson?: string;
};

type NativeNotifyAppModule = NativeModule & {
  show(json: string): Promise<boolean>;
  handleRemote(data: Record<string, string>): Promise<boolean>;
  cancel(id: string): Promise<boolean>;
  snooze(id: string, minutes: number): Promise<boolean>;
  addListener?(eventName: string): void;
  removeListeners?(count: number): void;
};

function getNative(): NativeNotifyAppModule | null {
  return (
    (NativeNotifyAppSpec as NativeNotifyAppModule | null) ??
    (NativeModules.NotifyApp as NativeNotifyAppModule | null) ??
    null
  );
}

export function isNotifyAppLinked(): boolean {
  return !!getNative();
}

function normalizeEvent(raw: NotifyEventPayload): NotifyEventPayload {
  if (raw.params) return raw;
  if (raw.paramsJson) {
    try {
      const parsed = JSON.parse(raw.paramsJson) as Record<string, string>;
      return { ...raw, params: parsed };
    } catch {
      return raw;
    }
  }
  return raw;
}

/** Show local / custom notification from JS. */
export async function showNotify(payload: NotifyPayload): Promise<boolean> {
  const native = getNative();
  if (!native) {
    console.warn("[NotifyApp] module not linked");
    return false;
  }
  return native.show(JSON.stringify(payload));
}

/** Display notification from FCM/APNs data map (host forwards push data). */
export async function handleRemoteNotify(
  data: Record<string, string>,
): Promise<boolean> {
  const native = getNative();
  if (!native) {
    console.warn("[NotifyApp] module not linked");
    return false;
  }
  return native.handleRemote(data);
}

/**
 * Whether host FCM/APNs `data` should be passed to [handleRemoteNotify].
 * Geo (`type` starts with `geo_`) and unmarked pushes stay with the host / Firebase.
 */
export function shouldHandleRemoteNotify(
  data: Record<string, string> | undefined | null,
): boolean {
  if (!data) return false;
  const type = data.type ?? "";
  if (type.startsWith("geo_")) return false;
  if (type === NOTIFY_REMOTE_TYPE) return true;
  if (data[NOTIFY_OWNER_MARKER_KEY] === NOTIFY_OWNER_MARKER_VALUE) return true;
  return false;
}

export async function cancelNotify(id: string): Promise<boolean> {
  const native = getNative();
  if (!native) return false;
  return native.cancel(id);
}

export async function snoozeNotify(
  id: string,
  minutes?: number,
): Promise<boolean> {
  const native = getNative();
  if (!native) return false;
  return native.snooze(id, minutes && minutes > 0 ? minutes : 0);
}

/**
 * Android 13+: requests POST_NOTIFICATIONS via LocationTracking.requestForegroundServicePermission.
 * iOS / older Android: returns true (local auth is requested on first show when needed).
 * Call before the first showNotify on Android 13+.
 */
export async function requestNotifyPermission(): Promise<boolean> {
  const geo =
    NativeLocationTracking ??
    (NativeModules.LocationTracking as
      | { requestForegroundServicePermission?: () => Promise<boolean> }
      | undefined);
  if (typeof geo?.requestForegroundServicePermission === "function") {
    try {
      return await geo.requestForegroundServicePermission();
    } catch (e) {
      console.warn("[NotifyApp] requestNotifyPermission failed", e);
      return false;
    }
  }
  return true;
}

function createEmitter(): NativeEventEmitter | null {
  const mod = getNative();
  if (!mod) {
    console.warn("[NotifyApp] not linked — events unavailable");
    return null;
  }
  return new NativeEventEmitter(mod);
}

export function subscribeToNotifyEvents(
  listener: (event: NotifyEventPayload) => void,
): () => void {
  const emitter = createEmitter();
  if (!emitter) return () => undefined;

  const subscription: EmitterSubscription = emitter.addListener(
    NOTIFY_APP_EVENT,
    (raw: NotifyEventPayload) => listener(normalizeEvent(raw)),
  );
  return () => subscription.remove();
}

export type NotifyEventHandlers = {
  onEvent?: (event: NotifyEventPayload) => void;
  onShown?: (event: NotifyEventPayload) => void;
  onCancelled?: (event: NotifyEventPayload) => void;
  onActionRead?: (event: NotifyEventPayload) => void;
  onActionOpen?: (event: NotifyEventPayload) => void;
  onActionClose?: (event: NotifyEventPayload) => void;
  onActionSnooze?: (event: NotifyEventPayload) => void;
};

export function useNotifyAppEvents(handlers: NotifyEventHandlers): void {
  const ref = useRef(handlers);
  ref.current = handlers;

  useEffect(() => {
    return subscribeToNotifyEvents((event) => {
      const h = ref.current;
      h.onEvent?.(event);
      switch (event.type) {
        case "SHOWN":
          h.onShown?.(event);
          break;
        case "CANCELLED":
          h.onCancelled?.(event);
          break;
        case "ACTION_READ":
          h.onActionRead?.(event);
          break;
        case "ACTION_OPEN":
          h.onActionOpen?.(event);
          break;
        case "ACTION_CLOSE":
          h.onActionClose?.(event);
          break;
        case "ACTION_SNOOZE":
          h.onActionSnooze?.(event);
          break;
        default:
          break;
      }
    });
  }, []);
}
