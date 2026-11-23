// SPDX-License-Identifier: AGPL-3.0-only

/// Why a moderation command (move, server mute, kick, ban) did not happen, as the open sheet
/// says it.
enum ModerationFailure: Error, Equatable {
    /// The server refused; `serverText` is its own words (untrusted, may be empty).
    case refused(serverText: String)
    /// The person left the server meanwhile.
    case gone
    case disconnected
    case failed
}

/// The longest server text a sheet shows; the rest is cut off.
private let maxServerText = 200

/// What a failed moderation command says. `refusal` is the server's own text of a
/// `permissionDenied`: untrusted and never logged.
func moderationFailure(_ error: ConnectionError, refusal: String? = nil) -> ModerationFailure {
    switch error {
    case .permissionDenied:
        // Server text is shown as plain characters; control and format characters could hide
        // or reorder it.
        let visible = (refusal ?? "").unicodeScalars.filter { scalar in
            scalar.properties.generalCategory != .control && scalar.properties.generalCategory != .format
        }
        return .refused(serverText: String(String(String.UnicodeScalarView(visible)).trimmed.prefix(maxServerText)))
    case .notFound: return .gone
    case .notConnected: return .disconnected
    default: return .failed
    }
}
