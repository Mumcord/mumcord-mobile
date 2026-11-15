// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import Foundation

/// A network that tests set by hand, in place of the phone's.
final class FakeNetworkMonitor: NetworkMonitor, @unchecked Sendable {
    private let lock = NSLock()
    private var current: NetworkStatus
    private var observers: [UUID: AsyncStream<NetworkStatus>.Continuation] = [:]

    init(_ status: NetworkStatus) {
        current = status
    }

    var status: NetworkStatus {
        get { lock.withLock { current } }
        set {
            lock.withLock {
                current = newValue
                for observer in observers.values { observer.yield(newValue) }
            }
        }
    }

    func statuses() -> AsyncStream<NetworkStatus> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.withLock {
                observers[id] = continuation
                continuation.yield(current)
            }
            continuation.onTermination = { [weak self] _ in
                self?.lock.withLock { self?.observers[id] = nil }
            }
        }
    }
}

/// Wi-Fi as tests use it: usable and not metered.
let fakeWifi = NetworkStatus(id: "wifi", usable: true, metered: false)
/// Mobile data as tests use it: usable and metered.
let fakeMobileData = NetworkStatus(id: "cellular", usable: true, metered: true)
#endif
