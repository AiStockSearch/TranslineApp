import Foundation
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

@objc(NotifyAppModule)
public class NotifyAppModule: RCTEventEmitter {

    public static var shared: NotifyAppModule?

    private lazy var manager: NotifyManager = {
        // Top-level KMP `createPlatformNotifier` is ObjC `IosPlatformNotifierKt.createPlatformNotifier()`.
        // Prefer concrete notifier — bare `createPlatformNotifier()` is not in Swift scope.
        let mgr = NotifyManager(notifier: IosPlatformNotifier())
        NotifyManagerHolder.shared.instance = mgr
        mgr.addEventListener(listener: SwiftNotifyEventListener(module: self))
        return mgr
    }()

    public override init() {
        super.init()
        NotifyAppModule.shared = self
        _ = manager
    }

    public override class func requiresMainQueueSetup() -> Bool { true }

    public override func supportedEvents() -> [String]! {
        return ["onNotifyAppEvent"]
    }

    @objc func show(_ json: String,
                    resolver: @escaping RCTPromiseResolveBlock,
                    rejecter: @escaping RCTPromiseRejectBlock) {
        guard let payload = Self.parsePayload(json: json) else {
            resolver(false)
            return
        }
        resolver(manager.show(payload: payload))
    }

    @objc func handleRemote(_ data: NSDictionary,
                            resolver: @escaping RCTPromiseResolveBlock,
                            rejecter: @escaping RCTPromiseRejectBlock) {
        var map = [String: String]()
        for (key, value) in data {
            guard let k = key as? String else { continue }
            map[k] = "\(value)"
        }
        resolver(manager.handleRemote(data: map))
    }

    @objc func cancel(_ id: String,
                      resolver: @escaping RCTPromiseResolveBlock,
                      rejecter: @escaping RCTPromiseRejectBlock) {
        resolver(manager.cancel(id: id))
    }

    @objc func snooze(_ id: String,
                      minutes: NSNumber,
                      resolver: @escaping RCTPromiseResolveBlock,
                      rejecter: @escaping RCTPromiseRejectBlock) {
        let mins = minutes.intValue
        let kotlinMins: KotlinInt? = mins > 0 ? KotlinInt(value: Int32(mins)) : nil
        resolver(manager.snooze(id: id, minutes: kotlinMins))
    }

    func sendNotifyEvent(type: String, payload: [String: Any] = [:]) {
        var body = payload
        body["type"] = type
        sendEvent(withName: "onNotifyAppEvent", body: body)
    }

    /// Show product coords/lifecycle shade from LocationTracker (background-safe).
    @objc public static func showProductNotify(title: String, body: String, deepLink: String) {
        let id = "geo_coords_\(Int(Date().timeIntervalSince1970 * 1000))"
        let jsonObj: [String: Any] = [
            "id": id,
            "title": title,
            "body": body,
            "deepLink": deepLink,
            "actions": [
                ["id": "open", "title": "Open", "deepLink": deepLink],
            ],
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: jsonObj),
              let json = String(data: data, encoding: .utf8),
              let payload = parsePayload(json: json) else {
            return
        }
        if let shared = NotifyAppModule.shared {
            _ = shared.manager.show(payload: payload)
            return
        }
        let mgr = NotifyManagerHolder.shared.getOrCreate()
        _ = mgr.show(payload: payload)
    }

    static func parsePayload(json: String) -> NotifyPayload? {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        guard let id = (obj["id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              let title = (obj["title"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !id.isEmpty, !title.isEmpty else {
            return nil
        }
        let body = obj["body"] as? String ?? ""
        let imageUrl = (obj["imageUrl"] as? String) ?? (obj["image_url"] as? String)
        let deepLink = (obj["deepLink"] as? String) ?? (obj["deep_link"] as? String)
        let channelId = obj["channelId"] as? String
        let snoozeMinutes: KotlinInt?
        if let n = obj["snoozeMinutes"] as? Int {
            snoozeMinutes = KotlinInt(value: Int32(n))
        } else if let n = obj["snoozeMinutes"] as? NSNumber {
            snoozeMinutes = KotlinInt(value: n.int32Value)
        } else {
            snoozeMinutes = nil
        }

        var actions = [NotifyAction]()
        if let arr = obj["actions"] as? [[String: Any]] {
            for a in arr {
                guard let idRaw = a["id"] as? String,
                      let actionId = NotifyActionId.companion.fromWire(raw: idRaw) else { continue }
                let actionTitle = (a["title"] as? String)?.nilIfEmpty
                    ?? NotifyRemoteParser.shared.defaultTitle(id: actionId)
                let actionDeepLink = (a["deepLink"] as? String) ?? (a["deep_link"] as? String)
                let actionRoute = a["route"] as? String
                var actionParams = [String: String]()
                if let p = a["params"] as? [String: Any] {
                    for (k, v) in p { actionParams[k] = "\(v)" }
                }
                actions.append(
                    NotifyAction(
                        id: actionId,
                        title: actionTitle,
                        deepLink: actionDeepLink,
                        route: actionRoute,
                        params: actionParams
                    )
                )
            }
        } else if let csv = obj["actions"] as? String {
            actions = NotifyRemoteParser.shared.parseActions(raw: csv)
        }

        var extra = [String: String]()
        if let d = obj["data"] as? [String: Any] {
            for (k, v) in d {
                extra[k] = "\(v)"
            }
        }

        let groupKey = (obj["groupKey"] as? String) ?? (obj["group_key"] as? String)
        let entityId = (obj["entityId"] as? String) ?? (obj["entity_id"] as? String)

        return NotifyPayload(
            id: id,
            title: title,
            body: body,
            imageUrl: imageUrl,
            deepLink: deepLink,
            channelId: channelId,
            actions: actions,
            data: extra,
            snoozeMinutes: snoozeMinutes,
            groupKey: groupKey,
            entityId: entityId
        )
    }
}

private class SwiftNotifyEventListener: NSObject, NotifyEventListener {
    private weak var module: NotifyAppModule?

    init(module: NotifyAppModule) {
        self.module = module
    }

    func onShown(payload: NotifyPayload) {
        var body: [String: Any] = ["id": payload.id, "title": payload.title]
        if let link = payload.deepLink { body["deepLink"] = link }
        module?.sendNotifyEvent(type: NotifyEventType.shared.SHOWN, payload: body)
    }

    func onCancelled(id: String) {
        module?.sendNotifyEvent(type: NotifyEventType.shared.CANCELLED, payload: ["id": id])
    }

    func onAction(actionId: NotifyActionId, payload: NotifyPayload) {
        var body: [String: Any] = [
            "id": payload.id,
            "actionId": actionId.name.lowercased(),
        ]
        if let link = NotifyActionNav.shared.deepLink(actionId: actionId, payload: payload) {
            body["deepLink"] = link
        }
        if let route = NotifyActionNav.shared.route(actionId: actionId, payload: payload) {
            body["route"] = route
        }
        let params = NotifyActionNav.shared.params(actionId: actionId, payload: payload)
        if !params.isEmpty {
            body["params"] = params
            body["paramsJson"] = NotifyRemoteParser.shared.paramsToJson(params: params)
        }
        module?.sendNotifyEvent(
            type: NotifyEventType.shared.forAction(id: actionId),
            payload: body
        )
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
