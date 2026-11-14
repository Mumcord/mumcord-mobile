// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// The server of a running voice session, written down so that a later start knows of it.
struct SessionMarker: Equatable {
    let serverId: Int64
    let serverName: String
}

/// A session that ended because the app was closed while it ran (FR-5.37).
struct SessionEndedNotice: Equatable {
    let serverId: Int64
    let serverName: String
}

/// Remembers on disk that a session runs. The mark is removed when the session ends in an
/// orderly way and when the app is told that it is being closed, which is what happens when
/// the user removes the running app from the app switcher. A mark found at the start of the
/// app is therefore a session that was cut off without a word.
///
/// iOS does not say why the last run ended. A run that the system ended to free memory, a
/// crash, and a phone that restarted or ran out of battery all leave the mark behind and
/// cannot be told apart.
final class SessionMarkerStore: @unchecked Sendable {
    private static let serverIdKey = "voice_session.server_id"
    private static let serverNameKey = "voice_session.server_name"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func mark(_ marker: SessionMarker) {
        defaults.set(NSNumber(value: marker.serverId), forKey: Self.serverIdKey)
        defaults.set(marker.serverName, forKey: Self.serverNameKey)
    }

    func clear() {
        defaults.removeObject(forKey: Self.serverIdKey)
        defaults.removeObject(forKey: Self.serverNameKey)
    }

    func read() -> SessionMarker? {
        guard let serverId = defaults.object(forKey: Self.serverIdKey) as? NSNumber else { return nil }
        return SessionMarker(
            serverId: serverId.int64Value,
            serverName: defaults.string(forKey: Self.serverNameKey) ?? ""
        )
    }

    /// The cut-off session of the last run, or nil when there was none. The mark is removed, so
    /// the notice is given once.
    func takeNotice() -> SessionEndedNotice? {
        let marker = read()
        clear()
        return marker.map { SessionEndedNotice(serverId: $0.serverId, serverName: $0.serverName) }
    }
}
