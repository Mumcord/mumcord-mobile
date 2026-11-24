// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge

// Names come from the server and its users: they are only ever drawn as plain characters.

extension StreamUi {
    var title: String {
        key.kind == .screen ? Strings.streamTitleScreen(name) : Strings.streamTitleCamera(name)
    }

    var icon: MumcordIconData {
        key.kind == .screen ? MumcordIcons.monitor : MumcordIcons.camera
    }

    /// The line a stream shows where it has no picture, or nil where it plays.
    var status: String? {
        switch tile {
        case .tapToWatch: Strings.streamTapToWatch
        case .connecting: Strings.streamConnecting
        case .paused: Strings.streamPaused
        case .openElsewhere: Strings.streamOpenElsewhere
        case .unreachable(let reason): reason.text(name)
        case .playing: nil
        }
    }

    /// The status of an opened stream: its tile's, except that it plays here rather than elsewhere.
    var openStatus: String? {
        switch tile {
        case .openElsewhere, .playing: nil
        default: status
        }
    }

    /// The whole tile in words for VoiceOver: the title, "live", the viewers and the status.
    var tileDescription: String {
        let parts = [viewers.map(Strings.streamViewers), status].compactMap { $0 }
        return parts.reduce(Strings.streamTileDescription(title)) { Strings.streamDescriptionPart($0, $1) }
    }
}

extension StreamUnreachable {
    /// The plain words for why a stream cannot be watched, with the sharer's name where the text
    /// has one. The desktop app's wording where it has the case.
    func text(_ name: String) -> String {
        switch self {
        case .noPath: Strings.streamUnreachableNoPath
        case .peerToPeerOff: Strings.streamUnreachablePeerToPeerOff
        case .directConnectionsDeclined: Strings.streamUnreachableDirectDeclined
        case .relayUnavailable: Strings.streamUnreachableRelayUnavailable
        case .sharerUnsupported: Strings.streamUnreachableSharerUnsupported(name)
        case .noAnswer: Strings.streamUnreachableNoAnswer(name)
        case .full: Strings.streamUnreachableFull(name)
        case .needsUpdate: Strings.streamUnreachableNeedsUpdate(name)
        case .joinFailed: Strings.streamUnreachableJoinFailed(name)
        }
    }
}
