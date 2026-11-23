// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import Network

/// The network the phone uses for the app's traffic.
///
/// - `id` tells one network from the next (Wi-Fi, then mobile data); nil while there is none.
/// - `usable`: the system says the network can carry traffic now.
/// - `metered`: traffic costs the user money or data volume. Taken from what the system says
///   about the path, not from its kind: a Wi-Fi hotspot of another phone is metered too.
///   Without a network nothing is loaded, so that counts as metered as well.
struct NetworkStatus: Equatable, Sendable {
    let id: String?
    let usable: Bool
    let metered: Bool

    static let noNetwork = NetworkStatus(id: nil, usable: false, metered: true)
}

/// The status of a network path as the system describes it. The system marks a path as
/// expensive (mobile data, a personal hotspot) or constrained (Low Data Mode); both count as
/// metered. `interface` is the system's name of the interface the traffic leaves by, which
/// changes between Wi-Fi and mobile data.
func networkStatus(satisfied: Bool, interface: String?, expensive: Bool, constrained: Bool) -> NetworkStatus {
    guard satisfied, let interface else { return .noNetwork }
    return NetworkStatus(id: interface, usable: true, metered: expensive || constrained)
}

/// Follows the network the phone uses.
protocol NetworkMonitor: AnyObject, Sendable {
    var status: NetworkStatus { get }

    /// The current status and every later one.
    func statuses() -> AsyncStream<NetworkStatus>
}

/// The network as the system reports it (`NWPathMonitor`). The system calls back only when the
/// path changes; nothing here runs on a timer.
final class SystemNetworkMonitor: NetworkMonitor, @unchecked Sendable {
    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var current = NetworkStatus.noNetwork
    private var observers: [UUID: AsyncStream<NetworkStatus>.Continuation] = [:]

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let interface = path.availableInterfaces.first { path.usesInterfaceType($0.type) }
            self?.publish(networkStatus(
                satisfied: path.status == .satisfied,
                interface: interface.map { "\($0.type)-\($0.name)" },
                expensive: path.isExpensive,
                constrained: path.isConstrained
            ))
        }
        monitor.start(queue: DispatchQueue(label: "app.mumcord.mobile.network"))
    }

    deinit {
        monitor.cancel()
    }

    var status: NetworkStatus {
        lock.withLock { current }
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

    private func publish(_ next: NetworkStatus) {
        lock.withLock {
            guard next != current else { return }
            current = next
            for observer in observers.values { observer.yield(next) }
        }
    }
}
