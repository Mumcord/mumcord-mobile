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

/// The longest server text a sheet shows, in UTF-16 units as on Android; the rest is cut off.
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
        // Cut by size and not by letters: one letter carries any number of combining marks. A
        // scalar is kept or dropped as a whole, so the text stays valid.
        var units = 0
        let cut = String(String.UnicodeScalarView(visible)).trimmed.unicodeScalars.prefix { scalar in
            units += UTF16.width(scalar)
            return units <= maxServerText
        }
        return .refused(serverText: String(Substring(cut)))
    case .notFound: return .gone
    case .notConnected: return .disconnected
    default: return .failed
    }
}
