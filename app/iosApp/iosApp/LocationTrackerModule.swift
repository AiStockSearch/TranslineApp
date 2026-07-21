import Foundation
import CoreLocation
import UIKit

// Placeholder for React Native RCTEventEmitter since React is not linked in this iOS project yet.
// In a real React Native environment, import React
#if canImport(React)
import React
#else
public class RCTEventEmitter: NSObject {
    public func sendEvent(withName name: String, body: Any) {}
    public class func requiresMainQueueSetup() -> Bool { return true }
    public func supportedEvents() -> [String]! { return [] }
}
public typealias RCTPromiseResolveBlock = (Any?) -> Void
public typealias RCTPromiseRejectBlock = (String, String, Error?) -> Void
#endif

@objc(LocationTrackerModule)
public class LocationTrackerModule: RCTEventEmitter {

  public static var shared: LocationTrackerModule?
  private let locationManager = CLLocationManager()

  public override init() {
    super.init()
    LocationTrackerModule.shared = self
  }

  public override class func requiresMainQueueSetup() -> Bool {
    return true
  }

  public override func supportedEvents() -> [String]! {
    return ["onGeoWorkerEvent"]
  }

  public func sendGeoEvent(type: String, payload: [String: Any] = [:]) {
    var body = payload
    body["type"] = type
    sendEvent(withName: "onGeoWorkerEvent", body: body)
  }

  @objc public func getCurrentLocation(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
      // Dummy response for the sake of compilation without IOSLocationProvider
      resolve([
        "latitude": 55.7558,
        "longitude": 37.6173,
        "timestamp": Date().timeIntervalSince1970 * 1000
      ])
  }

  @objc public func openGpsSettings(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    DispatchQueue.main.async {
      if let url = URL(string: UIApplication.openSettingsURLString) {
        if UIApplication.shared.canOpenURL(url) {
          UIApplication.shared.open(url, options: [:], completionHandler: nil)
          resolve(true)
          return
        }
      }
      reject("SETTINGS_ERROR", "Не удалось открыть настройки iOS", nil)
    }
  }

  @objc public func requestLocationPermissions(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    DispatchQueue.main.async {
      self.locationManager.requestAlwaysAuthorization()
      let status = CLLocationManager.authorizationStatus()
      
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
}
