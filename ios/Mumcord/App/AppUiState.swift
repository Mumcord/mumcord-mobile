// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge

/// The full-screen destinations. Which one shows follows from the state, see `AppUiState.screen`.
enum AppScreen: Equatable {
    case talkModeChoice
    case serverList
    case serverForm
    case channels
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
    case link(LinkConfirmState)
    case invalidLink
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
    /// The connected screens, or nil while no server is shown.
    var connected: ConnectedUiState?

    var screen: AppScreen {
        if !setupDone { return .talkModeChoice }
        if connected != nil { return .channels }
        if form != nil { return .serverForm }
        return .serverList
    }

    var serverRows: [ServerRow] { Mumcord.serverRows(session: session, pings: pings) }

    /// The name of the server of the current connection, attempt or failure.
    var activeServerName: String? {
        session.servers.first { $0.id == session.activeServerId }?.name
    }

    /// The error to show on the server list, with the server it belongs to.
    var connectFailure: ConnectFailure? {
        guard case .failed(let error) = session.phase else { return nil }
        return ConnectFailure(error: error, serverName: activeServerName)
    }
}

/// A failed connection or command as the server list shows it.
struct ConnectFailure: Equatable {
    let error: ConnectionError
    let serverName: String?
}
