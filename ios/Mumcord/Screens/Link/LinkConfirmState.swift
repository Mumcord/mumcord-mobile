// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// The target of an opened `mumble://` link, shown for confirmation. Nothing is saved and
/// nothing connects before the user confirms. A password inside a link is never taken over.
struct LinkConfirmState: Equatable {
    var link: MumbleLink
    /// The entry of the server list with the same address and port; it is used as it is.
    var savedServer: SavedServer?
    /// Typed by the user when neither the link nor a saved entry names one.
    var typedUsername = ""
    var working = false
    var error: ConnectionError?

    /// True when the user has to type the user name.
    var asksForUsername: Bool { savedServer == nil && (link.username ?? "").isBlank }

    /// The saved entry wins over the link: it is what the user set up for this server.
    var username: String {
        if let savedServer { return savedServer.username }
        if let fromLink = link.username, !fromLink.isBlank { return fromLink }
        return typedUsername.trimmed
    }

    var willAddServer: Bool { savedServer == nil }

    var canConnect: Bool { !username.isBlank && !working }

    /// The new entry of the server list, named after its address.
    func toInput() -> ServerInput {
        ServerInput(
            name: "",
            address: link.address,
            port: link.port,
            username: username,
            password: .keep,
            identityId: nil
        )
    }
}

func linkConfirmState(link: MumbleLink, servers: [SavedServer]) -> LinkConfirmState {
    LinkConfirmState(
        link: link,
        savedServer: servers.first {
            $0.address.caseInsensitiveCompare(link.address) == .orderedSame && $0.port == link.port
        }
    )
}
