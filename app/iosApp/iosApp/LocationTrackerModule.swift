import Foundation
import CoreLocation
import UIKit
import SharedLocationTracker

#if canImport(React)
import React
#else
public class RCTEventEmitter: NSObject {
    public func sendEvent(withName name: String, body: Any) {}
    public class func requiresMainQueueSetup() -> Bool { return true }
    public func supportedEvents() -> [String]! { return [] }
}
public typealias RCTPromiseResolveBlock = (Any?) -> Void
public typealias RCTPromiseRejectBlock = (String?, String?, Error?) -> Void
#endif

class SwiftTrackingListener: NSObject, TrackingListener {
    private let module: LocationTrackerModule

    init(module: LocationTrackerModule) {
        self.module = module
    }

    func onLocationSent(latitude: Double, longitude: Double, timestamp: Int64) {
        IOSNotificationHelper.showSuccessNotification(lat: latitude, lon: longitude)
        // RN bridge rejects raw Int64 — always box as NSNumber.
        module.sendGeoEvent(type: "LOCATION_SENT", payload: [
            "latitude": latitude,
            "longitude": longitude,
            "timestamp": NSNumber(value: timestamp)
        ])
    }

    func onLocationFailed(message: String) {
        IOSNotificationHelper.showOfflineNotification()
        module.sendGeoEvent(type: "LOCATION_FAILED", payload: [
            "message": message
        ])
    }

    func onLocationServicesDisabled() {
        module.sendGeoEvent(type: "LOCATION_SERVICES_DISABLED")
    }

    func onHttpResult(ok: Bool, method: String, url: String, status: KotlinInt?, message: String) {
        var payload: [String: Any] = [
            "method": method,
            "url": url,
            "message": message
        ]
        if let status = status {
            payload["status"] = status.intValue
        }
        module.sendGeoEvent(type: ok ? "HTTP_OK" : "HTTP_FAILED", payload: payload)
    }

    func onSecureConfigEvent(type: String, message: String?) {
        var payload: [String: Any] = [:]
        if let message = message {
            payload["message"] = message
        }
        module.sendGeoEvent(type: type, payload: payload)
    }
}

@objc(LocationTrackerModule)
public class LocationTrackerModule: RCTEventEmitter, CLLocationManagerDelegate {

  public static var shared: LocationTrackerModule?
  private var permissionLocationManager: CLLocationManager?
  private var locationPermissionResolver: RCTPromiseResolveBlock?
  private var locationPermissionRejecter: RCTPromiseRejectBlock?

  private lazy var storage = NSUserDefaultsTrackingStorage()
  private lazy var secureStore = KeychainSecureConfigStore()
  private lazy var networkChecker = IOSNetworkChecker()
  private lazy var locationProvider = IosLocationProvider()

  private lazy var controller: LocationTrackerController = {
      LocationControllerFactory.shared.createController(
          provider: locationProvider,
          storage: storage,
          networkChecker: networkChecker,
          secureStore: secureStore
      )
  }()

  private var swiftTrackingListener: SwiftTrackingListener?

  public override init() {
    super.init()
    LocationTrackerModule.shared = self
    let listener = SwiftTrackingListener(module: self)
    self.swiftTrackingListener = listener
    controller.addListener(listener: listener)
  }

  public override class func requiresMainQueueSetup() -> Bool {
    return true
  }

  public override func supportedEvents() -> [String]! {
    return ["onGeoWorkerEvent"]
  }

  public func sendGeoEvent(type: String, payload: [String: Any] = [:]) {
    var body: [String: Any] = ["type": type]
    for (key, value) in payload {
      // RN bridge cannot serialize raw Int64 / UInt64.
      if let v = value as? Int64 {
        body[key] = NSNumber(value: v)
      } else if let v = value as? UInt64 {
        body[key] = NSNumber(value: v)
      } else {
        body[key] = value
      }
    }
    sendEvent(withName: "onGeoWorkerEvent", body: body)
  }

  private func scheduleStateToMap(_ state: TrackingScheduleState) -> [String: Any] {
    // NSNumber — raw Int64 in resolve()/sendEvent crashes the RN bridge on iOS.
    return [
      "isTrackingActive": state.isTrackingActive,
      "lastSentTimestamp": NSNumber(value: state.lastSentTimestamp?.int64Value ?? -1),
      "nextScheduledTimestamp": NSNumber(value: state.nextScheduledTimestamp?.int64Value ?? -1)
    ]
  }

  /** Defensive JSON object → string map; ignore non-objects (T-01-09). */
  private func parseHeadersJson(_ headersJson: String?) -> [String: String] {
    guard let headersJson = headersJson, !headersJson.isEmpty,
          let data = headersJson.data(using: .utf8),
          let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
      return [:]
    }
    var result: [String: String] = [:]
    for (key, value) in obj {
      if let s = value as? String {
        result[key] = s
      } else if !(value is NSNull) {
        result[key] = String(describing: value)
      }
    }
    return result
  }

  private func currentAuthorizationStatus() -> CLAuthorizationStatus {
    if #available(iOS 14, *) {
      return CLLocationManager().authorizationStatus
    }
    return CLLocationManager.authorizationStatus()
  }

  private func hasAlwaysPermission() -> Bool {
    guard CLLocationManager.locationServicesEnabled() else { return false }
    return currentAuthorizationStatus() == .authorizedAlways
  }

  // MARK: - Continuous tracking API

  @objc public func saveLocationConfiguration(
    _ apiEndpoint: String,
    driverUuid: String,
    orderNumber: String?,
    updateIntervalMinutes: NSNumber?,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    let interval = updateIntervalMinutes?.int32Value
    let kotlinInterval: KotlinInt? = interval.map { KotlinInt(value: $0) }
    let ok = controller.saveLocationConfiguration(
      apiEndpoint: apiEndpoint,
      driverUuid: driverUuid,
      orderNumber: orderNumber ?? "",
      updateIntervalMinutes: kotlinInterval,
      authHeader: nil
    )
    resolve(ok)
  }

  @objc public func saveLocationConfigurationWithAuth(
    _ apiEndpoint: String,
    driverUuid: String,
    orderNumber: String?,
    updateIntervalMinutes: NSNumber?,
    username: String?,
    password: String?,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    var authHeader: String? = nil
    if let username = username, !username.isEmpty,
       let password = password, !password.isEmpty,
       let data = "\(username):\(password)".data(using: .utf8) {
      authHeader = "Basic \(data.base64EncodedString())"
    }
    let interval = updateIntervalMinutes?.int32Value
    let kotlinInterval: KotlinInt? = interval.map { KotlinInt(value: $0) }
    let ok = controller.saveLocationConfiguration(
      apiEndpoint: apiEndpoint,
      driverUuid: driverUuid,
      orderNumber: orderNumber ?? "",
      updateIntervalMinutes: kotlinInterval,
      authHeader: authHeader
    )
    resolve(ok)
  }

  @objc public func saveLocationConfigurationWithBearer(
    _ apiEndpoint: String,
    driverUuid: String,
    orderNumber: String?,
    updateIntervalMinutes: NSNumber?,
    accessToken: String?,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    var authHeader: String? = nil
    if let raw = accessToken?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty {
      if raw.lowercased().hasPrefix("bearer ") {
        let token = raw.dropFirst(7).trimmingCharacters(in: .whitespacesAndNewlines)
        authHeader = token.isEmpty ? nil : "Bearer \(token)"
      } else {
        authHeader = "Bearer \(raw)"
      }
    }
    let interval = updateIntervalMinutes?.int32Value
    let kotlinInterval: KotlinInt? = interval.map { KotlinInt(value: $0) }
    let ok = controller.saveLocationConfiguration(
      apiEndpoint: apiEndpoint,
      driverUuid: driverUuid,
      orderNumber: orderNumber ?? "",
      updateIntervalMinutes: kotlinInterval,
      authHeader: authHeader
    )
    resolve(ok)
  }

  @objc public func startLocationService(
    _ apiEndpoint: String,
    driverUuid: String,
    orderNumber: String?,
    updateIntervalMinutes: NSNumber?,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    if !hasAlwaysPermission() {
      reject("PERMISSION_DENIED", "Required permissions not granted", nil)
      return
    }

    DispatchQueue.main.async {
      let interval = updateIntervalMinutes?.int32Value
      let kotlinInterval: KotlinInt? = interval.map { KotlinInt(value: $0) }
      let ok = self.controller.startLocationService(
        apiEndpoint: apiEndpoint,
        driverUuid: driverUuid,
        orderNumber: orderNumber ?? "",
        updateIntervalMinutes: kotlinInterval,
        authHeader: nil
      )
      resolve(ok)
    }
  }

  @objc public func stopLocationService(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    DispatchQueue.main.async {
      self.controller.stopLocationService()
      resolve(true)
    }
  }

  @objc public func isLocationServiceRunning(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    resolve(controller.isLocationServiceRunning())
  }

  @objc public func isRegistrationLocked(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    resolve(controller.isRegistrationLocked())
  }

  @objc public func requestLocationPermission(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    locationPermissionResolver = resolve
    locationPermissionRejecter = reject

    DispatchQueue.main.async {
      let manager = CLLocationManager()
      manager.delegate = self
      self.permissionLocationManager = manager

      let status = self.currentAuthorizationStatus()
      switch status {
      case .notDetermined, .authorizedWhenInUse:
        manager.requestAlwaysAuthorization()
      case .authorizedAlways:
        resolve(true)
        self.locationPermissionResolver = nil
      case .denied, .restricted:
        reject("PERMISSION_DENIED", "3", nil)
        self.locationPermissionRejecter = nil
      @unknown default:
        break
      }
    }
  }

  @objc public func requestForegroundServicePermission(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    resolve(true)
  }

  @objc public func hasRequiredPermissions(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    if !CLLocationManager.locationServicesEnabled() {
      resolve("3")
      return
    }
    let status = currentAuthorizationStatus()
    if status == .authorizedAlways {
      resolve("1")
    } else if status == .notDetermined {
      resolve("3")
    } else {
      resolve("2")
    }
  }

  @objc public func getLocationPermissionStatus(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    DispatchQueue.main.async {
      if !CLLocationManager.locationServicesEnabled() {
        resolve("3")
        return
      }
      switch self.currentAuthorizationStatus() {
      case .authorizedAlways, .authorizedWhenInUse:
        resolve("1")
      case .denied, .restricted:
        resolve("2")
      case .notDetermined:
        resolve("3")
      @unknown default:
        resolve("3")
      }
    }
  }

  public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    handleAuthorizationChange(manager.authorizationStatus)
  }

  public func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
    handleAuthorizationChange(status)
  }

  private func handleAuthorizationChange(_ status: CLAuthorizationStatus) {
    switch status {
    case .authorizedAlways:
      locationPermissionResolver?(true)
      locationPermissionResolver = nil
      locationPermissionRejecter = nil
    case .denied, .restricted, .authorizedWhenInUse:
      locationPermissionRejecter?("PERMISSION_DENIED", "3", nil)
      locationPermissionRejecter = nil
      locationPermissionResolver = nil
    default:
      break
    }
  }

  // MARK: - Secure config / HTTP probe (PKG-01, D-08) — no public getSecrets/load

  @objc public func saveSecureConfig(
    _ access: String,
    refresh: String,
    endpointUrl: String?,
    headersJson: String?,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    let ok = controller.saveSecureConfig(
      access: access,
      refresh: refresh,
      endpointUrl: endpointUrl,
      customHeaders: parseHeadersJson(headersJson)
    )
    resolve(ok)
  }

  @objc public func saveTokens(
    _ access: String,
    refresh: String,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    resolve(controller.saveTokens(access: access, refresh: refresh))
  }

  /** Clears SecureConfigStore only (D-06) — never TrackingStorage.clear. */
  @objc public func clearSecrets(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    controller.clearSecrets()
    resolve(true)
  }

  /**
   * KMP HTTP probe. Promise returns ok/method/url/status/message — never body.
   * Events remain source of truth.
   */
  @objc public func httpProbe(
    _ url: String,
    method: String,
    body: String?,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    controller.httpProbe(url: url, method: method, body: body) { result, error in
      if let error = error {
        reject("HTTP_PROBE_ERROR", error.localizedDescription, error)
        return
      }
      guard let result = result else {
        reject("HTTP_PROBE_ERROR", "Unknown probe error", nil)
        return
      }
      var map: [String: Any] = [
        "ok": result.ok,
        "method": result.method,
        "url": result.url,
        "message": result.message
      ]
      if let status = result.status {
        map["status"] = status.intValue
      }
      resolve(map)
    }
  }

  // MARK: - Trip / utils

  @objc public func getCurrentLocation(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    locationProvider.getCurrentLocation { location, error in
      if let error = error {
        reject("LOCATION_ERROR", error.localizedDescription, error)
      } else if let location = location {
        resolve([
          "latitude": location.latitude,
          "longitude": location.longitude,
          "speedMps": location.speedMps,
          "timestamp": NSNumber(value: location.timestampMs)
        ])
      } else {
        reject("LOCATION_NULL", "Геопозиция недоступна", nil)
      }
    }
  }

  @objc public func openGpsSettings(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    DispatchQueue.main.async {
      if let url = URL(string: UIApplication.openSettingsURLString),
         UIApplication.shared.canOpenURL(url) {
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
        resolve(true)
        return
      }
      reject("SETTINGS_ERROR", "Не удалось открыть настройки iOS", nil)
    }
  }

  @objc public func requestLocationPermissions(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    DispatchQueue.main.async {
      let status = self.currentAuthorizationStatus()
      switch status {
      case .authorizedAlways, .authorizedWhenInUse:
        resolve("GRANTED")
      case .denied, .restricted:
        resolve("DENIED")
      default:
        resolve("NOT_DETERMINED")
      }
    }
  }

  @objc public func initializeAndSyncOnAppStart(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    controller.initializeAndSyncOnAppStart { state, error in
      if let error = error {
        reject("INIT_ERROR", error.localizedDescription, error)
      } else if let state = state {
        resolve(self.scheduleStateToMap(state))
      } else {
        reject("INIT_ERROR", "Неизвестная ошибка инициализации", nil)
      }
    }
  }

  @objc public func getScheduleState(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    resolve(scheduleStateToMap(controller.getScheduleState()))
  }

  @objc public func checkAndSyncTracking(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    controller.executePendingOrScheduledTracking(force: true) { state, error in
      if let error = error {
        reject("SYNC_ERROR", error.localizedDescription, error)
      } else if let state = state {
        resolve(self.scheduleStateToMap(state))
      } else {
        reject("SYNC_ERROR", "Неизвестная ошибка синхронизации", nil)
      }
    }
  }

  /// Hybrid lite wake (D-27): schedule via KMP startTrip (interval=30) + Always continuous resume + flush/sync.
  /// Primary wake is continuous Always only — no OS background task schedulers (D-28).
  @objc public func startTrip(
    _ loadingTimeEpochMs: Double,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    if !hasAlwaysPermission() {
      reject("PERMISSION_DENIED", "Required permissions not granted", nil)
      return
    }

    DispatchQueue.main.async {
      // WR-01 / REL-02: validate-before-mutate — reject empty config before KMP startTrip
      // mutates tracking_active / schedule (avoids phantom trip schedule).
      guard let endpoint = self.storage.getApiEndpoint(), !endpoint.isEmpty,
            let uuid = self.storage.getDriverUuid(), !uuid.isEmpty else {
        reject("CONFIG_MISSING", "Save endpoint and driver UUID via GEO first", nil)
        return
      }

      self.controller.startTrip(loadingTimeEpochMs: Int64(loadingTimeEpochMs))
      // resume (not full startLocationService) — preserves lastSent; Android FGS uses resume
      let resumed = self.controller.resumeLocationServiceIfActive()
      if !resumed {
        // WR-04: config already validated — distinct code; stop clears startTrip mutate
        self.controller.stopLocationService()
        reject("RESUME_FAILED", "Cannot resume continuous tracking", nil)
        return
      }
      self.controller.initializeAndSyncOnAppStart { _, error in
        if let error = error {
          // CR-01: stop provider + clear tracking_active (flag-only left GPS running)
          self.controller.stopLocationService()
          reject("START_TRIP_ERROR", error.localizedDescription, error)
        } else {
          resolve(nil)
        }
      }
    }
  }

  @objc public func completeTripAfterModeration(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    controller.completeTripAfterModeration { error in
      if let error = error {
        reject("COMPLETE_TRIP_ERROR", error.localizedDescription, error)
      } else {
        resolve(nil)
      }
    }
  }
}
