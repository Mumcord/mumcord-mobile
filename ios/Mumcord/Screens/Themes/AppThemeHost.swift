// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import Observation
import SwiftUI

/// The colours of the shown theme in both versions (FR-5.81): the shown server's own theme
/// while connected to one that has it, otherwise the app theme. Nil until the themes were read;
/// the app then draws in its own Mumcord colours.
@MainActor
@Observable
final class AppThemeController {
    private(set) var shown: AppTheme?

    @ObservationIgnored private let store: SessionStore
    @ObservationIgnored private var last: Shown?

    /// What the shown colours follow. Any change of the themes reads the colours again: a theme
    /// of the same id may have been replaced. That is rare and cheap.
    private struct Shown: Equatable {
        let settings: ThemeSettings
        let serverId: Int64?
    }

    init(store: SessionStore) {
        self.store = store
    }

    /// Takes over a new state of the session. The model calls it with every state before the
    /// screens get it, and the colours are read at once, so the first picture of a server with
    /// a theme of its own, and the first screen after the start, are already drawn in them.
    func follow(_ state: SessionState) {
        let key = state.themes.map { Shown(settings: $0, serverId: state.showsServer ? state.activeServerId : nil) }
        if key == last { return }
        last = key
        // Without colours (the store logs why) the app draws in its own Mumcord colours.
        guard let key, let tokens = store.themeTokensNow(shownThemeId(key.settings, shownServerId: key.serverId)) else {
            shown = nil
            return
        }
        let next = AppTheme(mode: key.settings.mode, dark: tokens.dark, light: tokens.light)
        if next != shown { shown = next }
    }
}

/// Draws the app in the shown theme: in the version the appearance mode and the phone's dark
/// mode pick, or in the app's own colours while the engine has not answered yet. `phone` is the
/// phone's appearance; a debug launch can force it.
///
/// The status bar, the keyboard and the system's own sheets follow the brightness of the drawn
/// colours in every mode, as on Android: the window's root view controller is set to it. The
/// phone's own setting is read from the window itself, which that setting does not reach, so
/// with System the app keeps following the phone, also right after Dark or Light.
struct AppThemeHost: ViewModifier {
    let theme: AppTheme?
    let phone: MumcordThemeMode
    /// The phone's dark mode as the window reports it; nil until the window is there.
    @State private var windowDark: Bool?

    func body(content: Content) -> some View {
        let phoneDark: Bool? = switch phone {
        case .system: windowDark
        case .dark: true
        case .light: false
        }
        // Until the window has answered, nothing is overridden, so the environment's scheme
        // is still the phone's own.
        content
            .modifier(DrawnTheme(theme: theme, phoneDark: phoneDark))
            .background {
                WindowStyle(dark: phoneDark.map { drawnTheme(theme, phoneDark: $0).isDark }) { dark in
                    if windowDark != dark { windowDark = dark }
                }
            }
    }
}

/// The colours drawn for the phone's dark mode: the theme's version, or the app's own colours.
private func drawnTheme(_ theme: AppTheme?, phoneDark: Bool) -> MumcordTheme {
    theme?.theme(systemDark: phoneDark) ?? (phoneDark ? .dark : .light)
}

/// Puts the drawn theme into the environment; before the phone's setting is known, the one the
/// environment reports.
private struct DrawnTheme: ViewModifier {
    let theme: AppTheme?
    let phoneDark: Bool?
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        content.environment(\.mumcordTheme, drawnTheme(theme, phoneDark: phoneDark ?? (scheme == .dark)))
    }
}

/// Sets the app to the drawn brightness (nil: leaves it) and reports the phone's own dark mode,
/// at once and whenever it changes, from the window.
private struct WindowStyle: UIViewRepresentable {
    let dark: Bool?
    let onPhoneDark: (Bool) -> Void

    func makeUIView(context: Context) -> WindowStyleView {
        WindowStyleView()
    }

    func updateUIView(_ view: WindowStyleView, context: Context) {
        view.onPhoneDark = onPhoneDark
        view.dark = dark
    }
}

final class WindowStyleView: UIView {
    var dark: Bool? {
        didSet { apply() }
    }
    var onPhoneDark: (Bool) -> Void = { _ in }
    private weak var observed: UIWindow?
    private var registration: (any UITraitChangeRegistration)?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        guard let window, window !== observed else {
            apply()
            return
        }
        if let registration { observed?.unregisterForTraitChanges(registration) }
        observed = window
        registration = window.registerForTraitChanges([UITraitUserInterfaceStyle.self]) { [weak self] (window: UIWindow, _) in
            self?.report(window)
        }
        apply()
        // Not while SwiftUI is placing this view: the answer changes what it draws.
        DispatchQueue.main.async { [weak self] in self?.report(window) }
    }

    private func report(_ window: UIWindow) {
        onPhoneDark(window.traitCollection.userInterfaceStyle == .dark)
    }

    /// The window's root view controller takes the style, not the window: the window keeps the
    /// phone's own, and what it presents (sheets, the keyboard, the status bar) follows the root.
    private func apply() {
        guard let root = window?.rootViewController, let dark else { return }
        let style: UIUserInterfaceStyle = dark ? .dark : .light
        if root.overrideUserInterfaceStyle != style { root.overrideUserInterfaceStyle = style }
    }
}
