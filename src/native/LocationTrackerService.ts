/**
 * @deprecated Импортируйте из `./LocationTracker` — этот файл оставлен как re-export.
 */
export {
  LocationTrackerService,
  getCurrentLocation,
  openGpsSettings,
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
} from "./LocationTracker";

export type {
  GeoEventPayload,
  GeoEventType,
  LocationCoordinates,
  LocationPermissionStatus,
  TrackingScheduleState,
  SecureConfigInput,
  HttpProbeInput,
  HttpProbeSummary,
} from "./LocationTracker";
