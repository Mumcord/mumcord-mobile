// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import Observation

/// What the user does on the themes screen. Every choice is saved at once and comes back as the
/// engine's theme event, which redraws the app; a failure is shown like a refusal.
@MainActor
@Observable
final class ThemesController {
    private(set) var local = ThemesLocal()

    @ObservationIgnored private let store: SessionStore
    @ObservationIgnored private var reading: Task<Void, Never>?

    init(store: SessionStore) {
        self.store = store
    }

    func setMode(_ mode: ThemeMode) {
        save { store in await store.setThemeMode(mode) }
    }

    func apply(_ themeId: String) {
        save { store in await store.setAppTheme(themeId) }
    }

    func openActions(_ themeId: String) {
        local.sheet = .actions(themeId: themeId)
    }

    func openPaste() {
        local.sheet = .paste(text: "", read: nil, applying: false)
    }

    func closeSheet() {
        reading?.cancel()
        local.sheet = nil
    }

    /// Hands the theme's share message to the share sheet (see `shared`).
    func share(_ themeId: String) {
        closeSheet()
        Task {
            switch await store.themeShare(themeId) {
            case .ok(let share): local.shareText = share.message
            case .failed: store.reportFailure()
            }
        }
    }

    /// The share sheet has the message.
    func shared() {
        local.shareText = nil
    }

    func requestDelete(_ themeId: String) {
        local.sheet = .confirmDelete(themeId: themeId)
    }

    func confirmDelete(_ themeId: String) {
        closeSheet()
        save { store in await store.deleteCustomTheme(themeId) }
    }

    /// The pasted text changed: the engine reads it again for the preview.
    func changePasteText(_ text: String) {
        guard case .paste(_, _, let applying) = local.sheet else { return }
        local.sheet = .paste(text: text, read: nil, applying: applying)
        reading?.cancel()
        if text.isBlank { return }
        reading = Task {
            let read: ThemeCodeRead
            switch await store.readThemeCode(text) {
            case .ok(let value): read = value
            case .failed: read = .invalid
            }
            // Only for the text that is still in the field.
            guard !Task.isCancelled, case .paste(let shown, _, let applying) = local.sheet, shown == text else { return }
            local.sheet = .paste(text: text, read: read, applying: applying)
        }
    }

    /// "Apply theme": adds the pasted theme to the user's own themes (once) and makes it the app
    /// theme, as the desktop's theme card does.
    func applyPasted() {
        guard case .paste(let text, let read, let applying) = local.sheet, case .found = read, !applying else { return }
        local.sheet = .paste(text: text, read: read, applying: true)
        Task {
            let applied: Outcome<Void>
            switch await store.importThemeCode(text) {
            case .ok(let theme): applied = await store.setAppTheme(theme.id)
            case .failed(let error): applied = .failed(error)
            }
            switch applied {
            case .ok:
                closeSheet()
            case .failed:
                if case .paste(let text, let read, _) = local.sheet {
                    local.sheet = .paste(text: text, read: read, applying: false)
                }
                store.reportFailure()
            }
        }
    }

    /// The screen was left or the connection ended.
    func reset() {
        reading?.cancel()
        if local != ThemesLocal() { local = ThemesLocal() }
    }

    private func save(_ command: @escaping @Sendable (SessionStore) async -> Outcome<Void>) {
        Task { [store] in
            if case .failed = await command(store) { store.reportFailure() }
        }
    }
}
