// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

/// The colours the app is drawn in: both versions of the shown theme, read from the engine
/// whenever the themes or the shown server change, so that following the phone's dark mode
/// needs no engine call. The design system's colours are made once, when the value is.
struct AppTheme: Equatable {
    let mode: ThemeMode
    let dark: ThemeTokens
    let light: ThemeTokens
    private let darkColors: MumcordColors
    private let lightColors: MumcordColors

    init(mode: ThemeMode, dark: ThemeTokens, light: ThemeTokens) {
        self.mode = mode
        self.dark = dark
        self.light = light
        darkColors = dark.toColors()
        lightColors = light.toColors()
    }

    func tokens(systemDark: Bool) -> ThemeTokens {
        themeVariant(mode, systemDark: systemDark) == .dark ? dark : light
    }

    /// The design system's theme for the version the mode and the phone pick.
    func theme(systemDark: Bool) -> MumcordTheme {
        let dark = themeVariant(mode, systemDark: systemDark) == .dark
        let tokens = dark ? self.dark : light
        return MumcordTheme(colors: dark ? darkColors : lightColors, isDark: tokens.isDark)
    }

    static func == (lhs: AppTheme, rhs: AppTheme) -> Bool {
        lhs.mode == rhs.mode && lhs.dark == rhs.dark && lhs.light == rhs.light
    }
}

/// The theme a screen is shown in (Spec 02 FR-2.17): the one the user picked for the shown
/// server, otherwise the app theme. A server's theme that no longer exists is left to the
/// engine, which then answers with the app theme.
func shownThemeId(_ settings: ThemeSettings, shownServerId: Int64?) -> String {
    shownServerId.flatMap { settings.serverThemes[$0] } ?? settings.appThemeId
}

/// Which version of a theme is drawn: System follows the phone's dark mode.
func themeVariant(_ mode: ThemeMode, systemDark: Bool) -> ThemeVariant {
    switch mode {
    case .dark: .dark
    case .light: .light
    case .system: systemDark ? .dark : .light
    }
}

extension ThemeTokens {
    /// Whether the colours are dark; it picks the opacities and shadows.
    var isDark: Bool { base == .dark }

    /// The design system's colours for these tokens. The engine gives the colours a theme
    /// decides; the rest (avatar colours, the backdrop, media and live colours) comes from the
    /// app's own dark or light set that matches the theme's brightness, as on the desktop. A
    /// value that is not `#rrggbb`, which the engine never sends, keeps the colour of that set.
    func toColors() -> MumcordColors {
        let base = isDark ? MumcordColors.dark : MumcordColors.light
        var colors = base
        colors.bgServers = hexColor(bgServers) ?? base.bgServers
        colors.bgSidebar = hexColor(bgSidebar) ?? base.bgSidebar
        colors.bgChat = hexColor(bgChat) ?? base.bgChat
        colors.bgElevated = hexColor(bgElevated) ?? base.bgElevated
        colors.bgHover = hexColor(bgHover) ?? base.bgHover
        colors.bgSelected = hexColor(bgSelected) ?? base.bgSelected
        colors.text = hexColor(text) ?? base.text
        colors.textSecondary = hexColor(textSecondary) ?? base.textSecondary
        colors.textMuted = hexColor(textMuted) ?? base.textMuted
        colors.accent = hexColor(accent) ?? base.accent
        colors.onAccent = hexColor(onAccent) ?? base.onAccent
        colors.accentText = hexColor(accentText) ?? base.accentText
        colors.success = hexColor(success) ?? base.success
        colors.warning = hexColor(warning) ?? base.warning
        colors.danger = hexColor(danger) ?? base.danger
        colors.localMuted = hexColor(localMuted) ?? base.localMuted
        colors.talking = hexColor(talking) ?? base.talking
        colors.accentStop1 = hexColor(accentStop1) ?? base.accentStop1
        colors.accentStop2 = hexColor(accentStop2) ?? base.accentStop2
        colors.accentStop3 = hexColor(accentStop3) ?? base.accentStop3
        colors.primaryBg = hexColor(primaryBg) ?? base.primaryBg
        colors.primaryText = hexColor(primaryText) ?? base.primaryText
        colors.border = hexColor(border) ?? base.border
        colors.borderStrong = hexColor(borderStrong) ?? base.borderStrong
        colors.focus = hexColor(focus) ?? base.focus
        return colors
    }
}

private let hexLength = 7

/// A `#rrggbb` colour as the engine writes it, made like the design tokens' own colours; nil
/// for anything else.
func hexColor(_ value: String) -> Color? {
    let digits = value.dropFirst()
    guard value.utf8.count == hexLength, value.hasPrefix("#"), digits.allSatisfy(\.isHexDigit),
          let rgb = UInt32(digits, radix: 16)
    else {
        return nil
    }
    return Color(rgba: rgb << 8 | 0xff)
}
