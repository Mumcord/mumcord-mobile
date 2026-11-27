// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge

/// The sheets of the themes screen.
enum ThemesSheet: Equatable {
    /// "Paste theme code": the pasted text (untrusted), what the engine read in it, and whether
    /// it is being applied.
    case paste(text: String, read: ThemeCodeRead?, applying: Bool)
    /// What can be done with one theme: share it, and delete it when it is the user's own.
    case actions(themeId: String)
    case confirmDelete(themeId: String)
}

struct ThemesLocal: Equatable {
    var sheet: ThemesSheet?
    /// A share message waiting for the system's share sheet; handed over once.
    var shareText: String?
}

/// The themes screen (mockup `19-themes`, FR-5.81).
struct ThemesUiState: Equatable {
    let mode: ThemeMode
    let appThemeId: String
    let builtIn: [Theme]
    let custom: [Theme]
    /// The shown server's own theme, which covers the app theme while connected to it.
    let serverTheme: Theme?
    let serverName: String
    let sheet: ThemesSheet?

    func find(_ themeId: String) -> Theme? {
        (builtIn + custom).first { $0.id == themeId }
    }
}

/// The themes screen as a value; nil until the themes have been read.
func themesUiState(session: SessionState, local: ThemesLocal, serverName: String) -> ThemesUiState? {
    guard let settings = session.themes else { return nil }
    let all = session.builtInThemes + settings.customThemes
    let serverThemeId = session.showsServer ? session.activeServerId.flatMap { settings.serverThemes[$0] } : nil
    return ThemesUiState(
        mode: settings.mode,
        appThemeId: settings.appThemeId,
        builtIn: session.builtInThemes,
        custom: settings.customThemes,
        serverTheme: serverThemeId.flatMap { id in all.first { $0.id == id } },
        serverName: serverName,
        sheet: local.sheet
    )
}

/// The name of the app theme, for the Themes row of the More tab; nil while not known.
func appThemeName(_ session: SessionState) -> String? {
    guard let settings = session.themes else { return nil }
    return (session.builtInThemes + settings.customThemes).first { $0.id == settings.appThemeId }?.name
}
