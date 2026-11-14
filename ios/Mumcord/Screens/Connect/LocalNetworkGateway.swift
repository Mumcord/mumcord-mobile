// SPDX-License-Identifier: AGPL-3.0-only

/// What the connect flow needs from the system for the local network. Everything
/// platform-specific stays behind it, so the flow can be tested without a device.
///
/// iOS neither lets an app ask for local network access nor tells whether it has it: the system
/// asks by itself at the first connection to a local address, and a refusal looks like a server
/// that cannot be reached. So the app explains before that first connection, and afterwards
/// only says what it knows.
protocol LocalNetworkGateway: AnyObject, Sendable {
    /// The networks the device is directly connected to right now (Wi-Fi, Ethernet).
    func onLinkPrefixes() -> [IpPrefix]
    /// The addresses of a host name or IP address; empty when the name cannot be resolved.
    func resolve(_ host: String) async -> [IpAddress]
    /// The explanation has been shown and continued, so the system has asked or will not ask.
    var explained: Bool { get set }
    /// Whether the system says that local network access is refused. False also means "not
    /// known". Called only after a connection to the server has failed.
    func accessRefused(host: String, port: UInt16) async -> Bool
}

/// What happens before a connection is started.
enum LocalNetworkStep: Equatable {
    case connect
    /// Explain why access is needed; the system asks when the connection starts.
    case explain
}

/// The step for a connect, and whether a failure to reach the server may be down to missing
/// local network access (`suspect`).
struct LocalNetworkCheck: Equatable {
    let step: LocalNetworkStep
    var suspect = false
}

/// What the error of a failed connection says about the local network.
enum LocalNetworkHint: Equatable {
    /// The system reported that access is refused.
    case denied
    /// The server is on a local or private address and was not reached; access may be missing.
    case suspected
}

extension LocalNetworkGateway {
    /// Decides whether the reason has to be explained before connecting to `host`.
    func check(host: String) async -> LocalNetworkCheck {
        let locality: Locality
        if isLocalName(host) {
            // Resolving such a name already needs the access, so it cannot be looked at first.
            locality = .onLink
        } else {
            // An unresolved name connects as before; the normal error then says what is wrong.
            locality = localityOf(addresses: await resolve(host), onLink: onLinkPrefixes())
        }
        switch locality {
        case .onLink: return LocalNetworkCheck(step: explained ? .connect : .explain, suspect: true)
        case .privateRange: return LocalNetworkCheck(step: .connect, suspect: true)
        case .other: return LocalNetworkCheck(step: .connect)
        }
    }
}
