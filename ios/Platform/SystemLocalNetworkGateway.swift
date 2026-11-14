// SPDX-License-Identifier: AGPL-3.0-only

import Darwin
import Foundation
import Network

/// The device's own networks, name lookups and what iOS tells about local network access.
///
/// iOS has no way to ask for local network access or to read whether it was given. The system
/// asks by itself when the app first reaches for the local network. Only afterwards a connection
/// of the system's own network framework can tell that access was refused, which `accessRefused`
/// uses.
final class SystemLocalNetworkGateway: LocalNetworkGateway, @unchecked Sendable {
    private static let explainedKey = "local_network.explained"
    private static let resolveTimeout: TimeInterval = 5
    private static let probeTimeout: TimeInterval = 2
    private let defaults: UserDefaults
    private let log: @Sendable (String) -> Void

    init(defaults: UserDefaults = .standard, log: @escaping @Sendable (String) -> Void) {
        self.defaults = defaults
        self.log = log
    }

    var explained: Bool {
        get { defaults.bool(forKey: Self.explainedKey) }
        set { defaults.set(newValue, forKey: Self.explainedKey) }
    }

    /// The networks of the interfaces that reach neighbours directly: up, able to broadcast
    /// (Wi-Fi, Ethernet), not the loopback and not a tunnel. This is what iOS counts as local
    /// network; mobile data and VPN interfaces are not.
    func onLinkPrefixes() -> [IpPrefix] {
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0 else {
            // Without the list nothing counts as local; an unreachable private address still
            // gets the hint in its error.
            log("reading the network interfaces failed (\(errno))")
            return []
        }
        defer { freeifaddrs(first) }
        var prefixes: [IpPrefix] = []
        var next = first
        while let entry = next {
            defer { next = entry.pointee.ifa_next }
            let flags = Int32(entry.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_BROADCAST != 0,
                  flags & IFF_LOOPBACK == 0, flags & IFF_POINTOPOINT == 0,
                  let address = entry.pointee.ifa_addr.flatMap(IpAddress.init(socketAddress:)),
                  let mask = entry.pointee.ifa_netmask.flatMap(IpAddress.init(socketAddress:))
            else { continue }
            let length = mask.bytes.reduce(0) { $0 + $1.nonzeroBitCount }
            prefixes.append(IpPrefix(address: address, length: length))
        }
        return prefixes
    }

    /// Looks the name up on a background thread. The lookup cannot be interrupted, so after
    /// `resolveTimeout` the connect goes on without its answer instead of hanging.
    func resolve(_ host: String) async -> [IpAddress] {
        await firstAnswer(timeout: Self.resolveTimeout, fallback: []) { answer in
            DispatchQueue.global(qos: .userInitiated).async {
                var hints = addrinfo()
                hints.ai_socktype = SOCK_STREAM
                var result: UnsafeMutablePointer<addrinfo>?
                // The connect itself reports a name that does not resolve.
                guard getaddrinfo(host, nil, &hints, &result) == 0 else {
                    answer([])
                    return
                }
                defer { freeaddrinfo(result) }
                var addresses: [IpAddress] = []
                var next = result
                while let entry = next {
                    if let address = entry.pointee.ai_addr.flatMap(IpAddress.init(socketAddress:)) {
                        addresses.append(address)
                    }
                    next = entry.pointee.ai_next
                }
                answer(addresses)
            }
            return {}
        }
    }

    /// Tries a connection of the system's network framework, which is the only thing that
    /// reports a refused local network access. It runs after the engine's own connection has
    /// failed, never in its place, and is given up after `probeTimeout`.
    func accessRefused(host: String, port: UInt16) async -> Bool {
        guard let endpointPort = NWEndpoint.Port(rawValue: port) else { return false }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: endpointPort, using: .tcp)
        return await firstAnswer(timeout: Self.probeTimeout, fallback: false) { answer in
            connection.stateUpdateHandler = { state in
                switch state {
                case .waiting:
                    if connection.currentPath?.unsatisfiedReason == .localNetworkDenied { answer(true) }
                case .ready, .failed, .cancelled:
                    answer(false)
                default:
                    break
                }
            }
            connection.start(queue: DispatchQueue.global(qos: .utility))
            return {
                connection.stateUpdateHandler = nil
                connection.cancel()
            }
        }
    }
}

/// Runs `start` and returns the first value it reports through its callback, or `fallback` when
/// none arrives in time. `start` returns what to do once the answer is in or the time is over.
private func firstAnswer<Value: Sendable>(
    timeout: TimeInterval,
    fallback: Value,
    _ start: (@escaping @Sendable (Value) -> Void) -> (@Sendable () -> Void)
) async -> Value {
    let once = Once<Value>()
    let value = await withCheckedContinuation { (continuation: CheckedContinuation<Value, Never>) in
        once.continuation = continuation
        once.afterwards(start { once.resume($0) })
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + timeout) {
            once.resume(fallback)
        }
    }
    return value
}

/// Resumes a continuation exactly once, whichever of several callers comes first.
private final class Once<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    private var storedFinish: (@Sendable () -> Void)?
    var continuation: CheckedContinuation<Value, Never>?

    /// What to do after the answer; runs at once when the answer is already in.
    func afterwards(_ finish: @escaping @Sendable () -> Void) {
        lock.lock()
        let late = done
        if !late { storedFinish = finish }
        lock.unlock()
        if late { finish() }
    }

    func resume(_ value: Value) {
        lock.lock()
        if done {
            lock.unlock()
            return
        }
        done = true
        let finish = storedFinish
        storedFinish = nil
        lock.unlock()
        continuation?.resume(returning: value)
        finish?()
    }
}

extension IpAddress {
    /// The address inside a socket address of the system, or nil for other address families.
    init?(socketAddress: UnsafeMutablePointer<sockaddr>) {
        switch Int32(socketAddress.pointee.sa_family) {
        case AF_INET:
            let address = socketAddress.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
            self.init(bytes: withUnsafeBytes(of: address) { Array($0) })
        case AF_INET6:
            let address = socketAddress.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { $0.pointee.sin6_addr }
            self.init(bytes: withUnsafeBytes(of: address) { Array($0) })
        default:
            return nil
        }
    }
}
