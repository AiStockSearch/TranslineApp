import Network
import Foundation
import SharedLocationTracker

@objc(IOSNetworkChecker)
public class IOSNetworkChecker: NSObject, NetworkChecker {
    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    /// Optimistic until first pathUpdateHandler — avoids init-time main-thread stall
    /// (WR-02) while keeping flush ungated before the first callback (REL-01).
    private var _isConnected = true

    public override init() {
        super.init()
        let queue = DispatchQueue(label: "NetworkMonitor")
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.lock.lock()
            self._isConnected = (path.status == .satisfied)
            self.lock.unlock()
        }
        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }

    @objc
    public func isNetworkAvailable() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return _isConnected
    }
}
