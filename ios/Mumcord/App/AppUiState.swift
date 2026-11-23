// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge

/// The full-screen destinations. Which one shows follows from the state, see `AppUiState.screen`.
enum AppScreen: Equatable {
    /// First start, before the identities were read: nothing to show yet.
    case starting
    case identitySetup
    case talkModeChoice
    case serverList
    case serverForm
    case channels
    case identities
    case scan
}

/// Where to connect: a saved server, a password for this attempt only, a channel from a link.
struct ConnectTarget: Equatable {
    var serverId: Int64
    var password: String?
    var channelPath: [String] = []
}

/// A question in a bottom sheet over the current screen. At most one at a time.
enum Overlay: Equatable {
    /// One server at a time: connecting to `target` ends the connection to `currentName`.
    case confirmSwitch(target: ConnectTarget, currentName: String, targetName: String)
    case confirmRemove(server: SavedServer)
    /// After a wrong or missing server password: type it and try again.
    case enterPassword(target: ConnectTarget, serverName: String, password: String)
    /// Why iOS will ask for local network access when the connection to `target` starts.
    case localNetwork(target: ConnectTarget, serverName: String)
    case link(LinkConfirmState)
    case invalidLink
    /// The last run was closed in the middle of a session with this server (FR-5.37).
    /// `alone`: the app disconnected because the user was alone in a channel for too long
    /// (FR-5.42), not because the system closed it.
    case sessionEnded(serverId: Int64, serverName: String, alone: Bool = false)
}

struct AppUiState: Equatable {
    var setupDone: Bool
    /// The card picked on the talk mode choice; nothing is picked for the user.
    var talkModeChoice: TalkMode?
    var setupSaving = false
    var setupError: ConnectionError?
    var session = SessionState()
    var pings: [Int64: PingState] = [:]
    /// The server list shows Edit and Remove on every row instead of Connect.
    var editing = false
    /// The open add or edit form, or nil.
    var form: ServerFormState?
    var overlay: Overlay?
    /// The server whose address is being looked at before its connection starts.
    var checkingServerId: Int64?
    /// The server on a local or private address that is being connected to: if it cannot be
    /// reached, missing local network access may be the reason.
    var localNetworkSuspectServerId: Int64?
    /// The server that was not reached because the system refuses local network access.
    var localNetworkDeniedServerId: Int64?
    /// The connected screens, or nil while no server is shown.
    var connected: ConnectedUiState?
    /// The identity screens and the scan of a transfer.
    var identities = IdentitiesUiState()

    var screen: AppScreen {
        if identities.scan != nil { return .scan }
        // First start (FR-5.1): an identity first, then the talk mode.
        if !setupDone {
            if !identities.known && !session.phase.isFailed { return .starting }
            return identities.rows.isEmpty ? .identitySetup : .talkModeChoice
        }
        if identities.open { return .identities }
        if connected != nil { return .channels }
        if form != nil { return .serverForm }
        return .serverList
    }

    var serverRows: [ServerRow] {
        Mumcord.serverRows(session: session, pings: pings, checkingServerId: checkingServerId)
    }

    /// The name of the server of the current connection, attempt or failure.
    var activeServerName: String? {
        activeServerName(session)
    }

    /// The same for a session that is not part of this value.
    func activeServerName(_ session: SessionState) -> String? {
        session.servers.first { $0.id == session.activeServerId }?.name
    }

    /// The error to show on the server list, with the server it belongs to.
    var connectFailure: ConnectFailure? {
        guard case .failed(let error) = session.phase else { return nil }
        var hint: LocalNetworkHint?
        if error == .serverUnreachable, let serverId = session.activeServerId {
            if serverId == localNetworkDeniedServerId {
                hint = .denied
            } else if serverId == localNetworkSuspectServerId {
                hint = .suspected
            }
        }
        return ConnectFailure(error: error, serverName: activeServerName, localNetwork: hint)
    }
}

/// A failed connection or command as the server list shows it.
private extension ConnectionPhase {
    var isFailed: Bool {
        if case .failed = self { return true }
        return false
    }
}

struct ConnectFailure: Equatable {
    let error: ConnectionError
    let serverName: String?
    /// Set when missing local network access is, or may be, the reason.
    var localNetwork: LocalNetworkHint?
}
