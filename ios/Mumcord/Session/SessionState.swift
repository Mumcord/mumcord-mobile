// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge

/// Everything the screens need to know about the one connection, as an immutable value.
struct SessionState: Equatable {
    var phase: ConnectionPhase = .idle
    var certificatePrompt: CertificatePromptState?
    var server: ServerModel?
    /// The server list in its saved order; empty until `serversLoaded`.
    var servers: [SavedServer] = []
    var serversLoaded = false
    /// The saved server of the current connection or attempt. It stays set after a failure, so
    /// the error can name the server and "Try again" knows where to connect.
    var activeServerId: Int64?
    /// The channel whose password the server asks for, until it is typed or given up.
    var channelPassword: ChannelPasswordPrompt?
    /// The latest thing the server refused, until it is put away.
    var denied: DeniedAction?
    /// Things the app cannot show, newest last; see `withHint`.
    var hints: [UnsupportedHint] = []

    /// True once the server's channels are known and the connection is still alive.
    var showsServer: Bool {
        guard server != nil else { return false }
        switch phase {
        case .connected, .reconnecting: return true
        default: return false
        }
    }

    /// True while a connection exists or is being made, so no new one may be started.
    var isBusy: Bool {
        switch phase {
        case .idle, .failed: false
        default: true
        }
    }
}

enum ConnectionPhase: Equatable {
    case idle
    case connecting
    case connected
    case reconnecting(Reconnecting)
    case failed(ConnectionError)
}

struct Reconnecting: Equatable {
    let attempt: Int
    let maxAttempts: Int
}

/// The certificate question waiting for an answer. `previousFingerprint` is set when a server
/// that was trusted before now presents a different certificate.
struct CertificatePromptState: Equatable {
    let fingerprint: String
    let previousFingerprint: String?

    var isChanged: Bool { previousFingerprint != nil }
}

/// The server's channels and people as the engine reported them, plus the tree derived from
/// them. The tree is built when the value is created, which happens off the main thread.
struct ServerModel: Equatable {
    let selfSession: UInt32
    let channels: [UInt32: Channel]
    let people: [UInt32: Person]
    let root: ChannelNode?

    init(selfSession: UInt32, channels: [UInt32: Channel], people: [UInt32: Person]) {
        self.selfSession = selfSession
        self.channels = channels
        self.people = people
        root = buildChannelTree(channels: channels.values, people: people.values)
    }

    /// The channel the user is in, or nil while the server has not said.
    var selfChannelId: UInt32? { people[selfSession]?.channelId }

    func with(channels: [UInt32: Channel]) -> ServerModel {
        ServerModel(selfSession: selfSession, channels: channels, people: people)
    }

    func with(people: [UInt32: Person]) -> ServerModel {
        ServerModel(selfSession: selfSession, channels: channels, people: people)
    }
}

/// `wrong` is true after the server refused the typed password, so the sheet can say so.
struct ChannelPasswordPrompt: Equatable {
    let channelId: UInt32
    let wrong: Bool
}

/// A refusal by the server. `detail` is the server's own text and therefore untrusted.
struct DeniedAction: Equatable {
    let channelId: UInt32?
    let detail: String
}

enum UnsupportedKind: Equatable {
    case fileTransfer
    case watchTogether
    case pluginContent
}

/// Something a person did that this app cannot show (FR-5.14). `personName` is untrusted text.
/// `id` tells two hints of the same kind by the same person apart.
struct UnsupportedHint: Equatable, Identifiable {
    let id: Int64
    let personName: String
    let kind: UnsupportedKind
}

/// How many hints stay visible; older ones make room so the list stays calm.
let maxHints = 3

extension SessionState {
    func withHint(_ hint: UnsupportedHint) -> SessionState {
        var next = self
        next.hints = Array((hints + [hint]).suffix(maxHints))
        return next
    }
}
