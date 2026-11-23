// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge

/// What the More tab shows: its list, or one of the screens it leads to.
enum MorePage: Equatable {
    case main
    case activity
    case settings
}

/// The choices of the settings that open a sheet.
enum SettingsSheet: Equatable {
    case talkMode
    case defaultOutput
    case historyAge
}

/// The days after which chat history is deleted, as the desktop app offers them; nil keeps it.
let historyAgeDays: [UInt32?] = [nil, 7, 30, 90, 365]

/// What the user did on the More tab; everything else follows from the engine.
struct MoreLocal: Equatable {
    var page = MorePage.main
    var filter = ActivityFilter.all
    /// The newest activity entry when the log was last looked at; nil before that.
    var seenActivityId: UInt64?
    var sheet: SettingsSheet?
    /// Read from the engine each time the settings open; nil until it answered.
    var noiseSuppression: Bool?
}

/// The settings screen (mockup `16-settings`).
struct SettingsUiState: Equatable {
    let mobile: MobileSettings
    let app: AppSettings
    let talkMode: TalkMode
    /// Nil while it is being read: the row waits for it.
    let noiseSuppression: Bool?
    /// "Earpiece" is only offered on a phone that has one.
    let hasEarpiece: Bool
    let senderOnly: Bool
    let sheet: SettingsSheet?
}

struct MoreUiState: Equatable {
    let page: MorePage
    let serverName: String
    /// Entries by others since the log was last looked at.
    let activityBadge: Int
    /// The log of the connection, newest first.
    let activity: [ActivityEntry]
    let filter: ActivityFilter
    /// Nil while the settings are closed or not read yet.
    let settings: SettingsUiState?
}

/// The More tab as a value, or nil while no server is shown.
func moreUiState(
    session: SessionState,
    local: MoreLocal,
    serverName: String,
    output: OutputState,
    senderOnly: Bool
) -> MoreUiState? {
    guard session.showsServer else { return nil }
    var settings: SettingsUiState?
    if local.page == .settings, let mobile = session.mobileSettings, let app = session.appSettings {
        settings = SettingsUiState(
            mobile: mobile,
            app: app,
            talkMode: session.talkMode,
            noiseSuppression: local.noiseSuppression,
            hasEarpiece: output.available.contains { $0.kind == .earpiece },
            senderOnly: senderOnly,
            sheet: local.sheet
        )
    }
    return MoreUiState(
        page: local.page,
        serverName: serverName,
        activityBadge: local.page == .activity ? 0 : unseenActivity(session.activity, seenId: local.seenActivityId),
        activity: session.activity,
        filter: local.filter,
        settings: settings
    )
}
