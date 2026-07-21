import Network
import Foundation

@objc(IOSNetworkChecker)
public class IOSNetworkChecker: NSObject {
    private let monitor = NWPathMonitor()
    private var isConnected = false

    public override init() {
        super.init()
        monitor.pathUpdateHandler = { path in
            self.isConnected = (path.status == .satisfied)
        }
        let queue = DispatchQueue(label: "NetworkMonitor")
        monitor.start(queue: queue)
    }

    @objc
    public func isNetworkAvailable() -> Bool {
        return isConnected
    }
}
