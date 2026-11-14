// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// The standard Mumble port; an address is shown without it.
let defaultPort: UInt16 = 64738

/// What a server answered to the last ping. A server that was not asked yet has no state.
enum PingState: Equatable {
    case online(peopleOnline: Int, latencyMs: Int)
    case offline

    init(_ ping: ServerPing?) {
        if let ping {
            self = .online(peopleOnline: Int(ping.peopleOnline), latencyMs: Int(ping.latencyMs))
        } else {
            self = .offline
        }
    }
}

/// One row of the server list, ready to draw.
struct ServerRow: Equatable, Identifiable {
    let id: Int64
    let name: String
    let initials: String
    let address: String
    let ping: PingState?
    /// True from the tap on "Connect" until the connection stands or has ended.
    let connecting: Bool
    /// The first server of the list gets the brand tile and the filled button, as in the mockup.
    let highlighted: Bool
}

func serverRows(session: SessionState, pings: [Int64: PingState]) -> [ServerRow] {
    session.servers.enumerated().map { index, server in
        ServerRow(
            id: server.id,
            name: server.name,
            initials: initials(server.name),
            address: displayAddress(server.address, port: server.port),
            ping: pings[server.id],
            connecting: session.isBusy && session.activeServerId == server.id,
            highlighted: index == 0
        )
    }
}

/// Up to two initials for a server tile: the first letters of the first two words (as on desktop).
func initials(_ name: String) -> String {
    let letters = name.split(whereSeparator: \.isWhitespace)
        .prefix(2)
        .compactMap(\.first)
        .map { String($0) }
        .joined()
        .uppercased()
    return letters.isEmpty ? "?" : letters
}

/// Host name or IP address, with the port only when it is not the standard one.
func displayAddress(_ address: String, port: UInt16) -> String {
    port == defaultPort ? address : "\(address):\(port)"
}
