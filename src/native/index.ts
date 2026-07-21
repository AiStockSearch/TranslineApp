/**
 * JS/TS facade для KMP GeoWorker.
 *
 * Spec (codegen): NativeLocationTracking.ts, NativeSystemBars.ts, NativeNotifyApp.ts
 * Events:       GeoWorkerEvents.ts → useGeoWorkerEvents / subscribeToEvents
 * API + hooks:  LocationTracker.ts
 *
 * @example
 * import {
 *   startLocationService,
 *   useGeoWorkerEvents,
 *   useSystemBarStyle,
 * } from '@transline/geoworker'; // или путь к src/native
 */
export * from "./LocationTracker";
export { setSystemBarsStyle, useSystemBarStyle } from "./SystemBars";
export { default as NativeLocationTracking } from "./NativeLocationTracking";
export { default as NativeSystemBars } from "./NativeSystemBars";
export { default as NativeNotifyApp } from "./NativeNotifyApp";
export type { Spec as LocationTrackingSpec } from "./NativeLocationTracking";
export type { Spec as SystemBarsSpec } from "./NativeSystemBars";
export type { Spec as NotifyAppSpec } from "./NativeNotifyApp";
export {
  showNotify,
  handleRemoteNotify,
  shouldHandleRemoteNotify,
  cancelNotify,
  snoozeNotify,
  requestNotifyPermission,
  subscribeToNotifyEvents,
  useNotifyAppEvents,
  isNotifyAppLinked,
  NOTIFY_APP_EVENT,
  NOTIFY_OWNER_MARKER_KEY,
  NOTIFY_OWNER_MARKER_VALUE,
  NOTIFY_REMOTE_TYPE,
} from "./NotifyApp";
export type {
  NotifyPayload,
  NotifyAction,
  NotifyActionId,
  NotifyEventPayload,
  NotifyEventType,
  NotifyEventHandlers,
} from "./NotifyApp";
export {
  NotifyRouter,
  resolveEventTarget,
} from "./NotifyRouter";
export type {
  NotifyRouteMatch,
  NotifyRouteHandler,
  NotifyRoutePatternStats,
  NotifyRouterReport,
  NotifyRouterAttachOptions,
} from "./NotifyRouter";
