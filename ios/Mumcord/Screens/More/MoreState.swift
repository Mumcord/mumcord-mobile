// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge

/// What the More tab shows: its list, or one of the screens it leads to.
enum MorePage: Equatable {
    case main
    case activity
    case settings
    case editProfile
    case themes
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
    /// False on the server list's More screen: the microphone level only moves while the voice
    /// runs, so its row is left out there.
    let connected: Bool
    let senderOnly: Bool
    let sheet: SettingsSheet?
}

struct MoreUiState: Equatable {
    let page: MorePage
    /// False for the server list's More screen, which shows only what works without a server:
    /// no profile, activity, blocked people or Disconnect.
    let connected: Bool
    let serverName: String
    /// Entries by others since the log was last looked at.
    let activityBadge: Int
    /// The log of the connection, newest first.
    let activity: [ActivityEntry]
    let filter: ActivityFilter
    /// Nil while the settings are closed or not read yet.
    let settings: SettingsUiState?
    /// The user's own profile for the card at the top; nil until it was read.
    var profile: OwnProfile?
    /// Nil while the editor is closed.
    var profileEdit: ProfileEditUiState?
    /// The name of the app theme for the Themes row; nil until the themes were read.
    var themeName: String?
    /// Nil while the themes screen is closed or the themes have not been read.
    var themes: ThemesUiState?
}

/// What the profile and theme screens add to the More tab; kept apart from `MoreLocal`.
struct MoreExtras: Equatable {
    var profile: OwnProfile?
    var profileEdit: ProfileEdit?
    var themes = ThemesLocal()
}

/// The More tab as a value; with `fromServerList` and no server shown, the server list's More
/// screen. Nil while neither is shown.
func moreUiState(
    session: SessionState,
    local: MoreLocal,
    serverName: String,
    output: OutputState,
    senderOnly: Bool,
    extras: MoreExtras = MoreExtras(),
    fromServerList: Bool = false
) -> MoreUiState? {
    let connected = session.showsServer
    guard connected || fromServerList else { return nil }
    var settings: SettingsUiState?
    if local.page == .settings, let mobile = session.mobileSettings, let app = session.appSettings {
        settings = SettingsUiState(
            mobile: mobile,
            app: app,
            talkMode: session.talkMode,
            noiseSuppression: local.noiseSuppression,
            // Which outputs the phone has is only known while a voice session runs, so
            // "Default audio output" waits for one.
            hasEarpiece: connected && output.available.contains { $0.kind == .earpiece },
            connected: connected,
            senderOnly: senderOnly,
            sheet: local.sheet
        )
    }
    return MoreUiState(
        page: local.page,
        connected: connected,
        serverName: serverName,
        activityBadge: local.page == .activity ? 0 : unseenActivity(session.activity, seenId: local.seenActivityId),
        activity: session.activity,
        filter: local.filter,
        settings: settings,
        // The profile lives on the server.
        profile: connected ? extras.profile : nil,
        profileEdit: connected && local.page == .editProfile
            ? extras.profile.flatMap { own in extras.profileEdit.map { profileEditUiState(own: own, edit: $0) } }
            : nil,
        themeName: appThemeName(session),
        themes: local.page == .themes ? themesUiState(session: session, local: extras.themes, serverName: serverName) : nil
    )
}
