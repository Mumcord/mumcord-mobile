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
    @ObservationIgnored private var reading: Task<Void, Never>?

    /// What the shown colours follow. Any change of the themes reads the colours again: a theme
    /// of the same id may have been replaced. That is rare and cheap.
    private struct Shown: Equatable {
        let settings: ThemeSettings
        let serverId: Int64?
    }

    init(store: SessionStore) {
        self.store = store
        let states = store.states()
        Task { [weak self] in
            var last: Shown?
            for await state in states {
                guard let self else { return }
                let key = state.themes.map { Shown(settings: $0, serverId: state.showsServer ? state.activeServerId : nil) }
                if key == last { continue }
                last = key
                self.read(key)
            }
        }
    }

    private func read(_ key: Shown?) {
        reading?.cancel()
        guard let key else {
            shown = nil
            return
        }
        reading = Task { [store] in
            let tokens = await store.themeTokens(shownThemeId(key.settings, shownServerId: key.serverId))
            if Task.isCancelled { return }
            switch tokens {
            case .ok(let tokens): shown = AppTheme(mode: key.settings.mode, dark: tokens.dark, light: tokens.light)
            case .failed: shown = nil
            }
        }
    }
}

/// Draws the app in the shown theme: in the version the appearance mode and the phone's dark
/// mode pick, or in the app's own colours while the engine has not answered yet. `phone` is the
/// phone's appearance; a debug launch can force it, as a test changes the phone's setting.
///
/// The status bar and the system's own controls follow the drawn colours when the mode is Dark
/// or Light. With System they are left to the phone, so the app keeps hearing when the phone's
/// dark mode changes.
struct AppThemeHost: ViewModifier {
    let theme: AppTheme?
    let phone: MumcordThemeMode
    @Environment(\.colorScheme) private var systemScheme

    func body(content: Content) -> some View {
        let phoneDark = MumcordTheme.resolve(phone, system: systemScheme).isDark
        let drawn = theme?.theme(systemDark: phoneDark) ?? MumcordTheme.resolve(phone, system: systemScheme)
        content
            .environment(\.mumcordTheme, drawn)
            .preferredColorScheme(preferredScheme(drawn))
    }

    private func preferredScheme(_ drawn: MumcordTheme) -> ColorScheme? {
        switch theme?.mode {
        case .dark, .light: drawn.isDark ? .dark : .light
        case .system, nil:
            switch phone {
            case .system: nil
            case .dark: .dark
            case .light: .light
            }
        }
    }
}
