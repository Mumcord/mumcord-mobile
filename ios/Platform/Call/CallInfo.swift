// SPDX-License-Identifier: AGPL-3.0-only

/// The voice session as the phone system shows it. `serverName` and `channelName` come from
/// the server and are untrusted text; they are only ever shown as plain text.
struct CallInfo: Equatable {
    var serverName: String
    /// The channel the user is in, or nil while the server has not said.
    var channelName: String?
    /// Muted or deafened: what the system's mute button shows.
    var muted: Bool
    var deafened: Bool

    /// The name of the call: "Server · Channel".
    var title: String {
        guard let channelName, !channelName.isEmpty else { return serverName }
        return "\(serverName) · \(channelName)"
    }
}
