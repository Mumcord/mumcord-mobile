// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// The values of one theme. Views read it from the environment:
/// `@Environment(\.mumcordTheme) private var theme`, then `theme.colors.text`.
struct MumcordTheme {
    let colors: MumcordColors
    let opacities: MumcordOpacities
    let shadows: MumcordShadows
    let isDark: Bool

    static let dark = MumcordTheme(colors: .dark, isDark: true)
    static let light = MumcordTheme(colors: .light, isDark: false)

    /// The colours of a theme the user picked; `isDark` says whether they are dark, which
    /// picks the opacities and shadows. The app's own colours are the built-in "Mumcord" theme.
    init(colors: MumcordColors, isDark: Bool) {
        self.colors = colors
        opacities = isDark ? .dark : .light
        shadows = isDark ? .dark : .light
        self.isDark = isDark
    }

    /// The theme for a mode; `.system` follows the colour scheme of the device.
    static func resolve(_ mode: MumcordThemeMode, system: ColorScheme) -> MumcordTheme {
        switch mode {
        case .system: system == .dark ? .dark : .light
        case .dark: .dark
        case .light: .light
        }
    }
}

enum MumcordThemeMode: String, CaseIterable {
    case system
    case dark
    case light
}

private struct MumcordThemeKey: EnvironmentKey {
    static let defaultValue = MumcordTheme.dark
}

extension EnvironmentValues {
    var mumcordTheme: MumcordTheme {
        get { self[MumcordThemeKey.self] }
        set { self[MumcordThemeKey.self] = newValue }
    }
}

private struct MumcordThemeModifier: ViewModifier {
    let mode: MumcordThemeMode
    @Environment(\.colorScheme) private var systemScheme

    func body(content: Content) -> some View {
        content
            .environment(\.mumcordTheme, MumcordTheme.resolve(mode, system: systemScheme))
            // Keeps the status bar and system controls in step with a forced theme.
            .preferredColorScheme(preferredScheme)
    }

    private var preferredScheme: ColorScheme? {
        switch mode {
        case .system: nil
        case .dark: .dark
        case .light: .light
        }
    }
}

extension View {
    /// Puts the Mumcord theme into the environment of this view. It follows the system's dark
    /// or light setting unless a mode is given. Text sizes follow Dynamic Type in every mode.
    func mumcordTheme(_ mode: MumcordThemeMode = .system) -> some View {
        modifier(MumcordThemeModifier(mode: mode))
    }
}
