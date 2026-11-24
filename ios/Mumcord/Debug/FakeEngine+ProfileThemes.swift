// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import Foundation
import MumcordBridge

/// The only text besides the mockup's Deep Ocean code that the fake reads as a theme code.
let fakeThemeCode = "mct1.fake"
/// The code of the Deep Ocean share message in the screenshots of the paste sheet.
let mockupOceanCode = "mct1.eyJ2IjoiMC45LjAiLCJuIjoiRGVlcCBPY2VhbiJ9"

/// The user's profile and the themes of the fake engine, behind a lock of their own.
final class FakeProfileThemes: @unchecked Sendable {
    private let lock = NSLock()
    private var _profile = Profile(comment: nil, commentText: "", commentImages: [], avatar: nil)
    private var _ownAvatar: Data?
    private var _saveFailure: BridgeError?
    private var _settings = ThemeSettings(
        mode: .system,
        appThemeId: MockupThemes.mumcordId,
        customThemes: [],
        serverThemes: [:]
    )
    private var nextCustomId = 1

    private func locked<Value>(_ body: () throws -> Value) rethrows -> Value {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    /// The profile the server holds for the user.
    var profile: Profile {
        get { locked { _profile } }
        set { locked { _profile = newValue } }
    }

    /// The bytes the app last set as the avatar; nil after removing it.
    var ownAvatar: Data? { locked { _ownAvatar } }

    /// The next comment change fails with this, once.
    var saveFailure: BridgeError? {
        get { locked { _saveFailure } }
        set { locked { _saveFailure = newValue } }
    }

    var settings: ThemeSettings {
        get { locked { _settings } }
        set { locked { _settings = newValue } }
    }

    /// Adds a theme to the user's own, with the next free id.
    func addCustom(_ make: (String) -> Theme) -> Theme {
        locked {
            let theme = make("custom:\(nextCustomId)")
            nextCustomId += 1
            _settings.customThemes.append(theme)
            return theme
        }
    }

    /// The settings after `change`, or nil when it changed nothing.
    fileprivate func changeSettings(_ change: (inout ThemeSettings) -> Void) -> ThemeSettings? {
        locked {
            var settings = _settings
            change(&settings)
            if settings == _settings { return nil }
            _settings = settings
            return settings
        }
    }

    fileprivate func takeSaveFailure() throws {
        try locked {
            if let failure = _saveFailure {
                _saveFailure = nil
                throw failure
            }
        }
    }

    fileprivate func setAvatar(_ image: Data?) {
        locked {
            _ownAvatar = image
            _profile.avatar = image.map { ImageData(bytes: $0, mime: "image/png", width: 1, height: 1, animated: false) }
        }
    }
}

extension FakeEngine {
    // MARK: Profile

    func personProfile(session: UInt32) async throws -> Profile {
        profileThemes.profile
    }

    func setOwnComment(text: String) async throws {
        try profileThemes.takeSaveFailure()
        profileThemes.profile.commentText = text
        emit(.profileChanged(session: selfSession))
    }

    func setOwnAvatar(image: Data?) async throws {
        profileThemes.setAvatar(image)
        emit(.profileChanged(session: selfSession))
    }

    // MARK: Themes

    func themeSettings() -> ThemeSettings {
        profileThemes.settings
    }

    func builtInThemes() -> [Theme] {
        MockupThemes.builtIn
    }

    /// As the engine: an id that names no theme (any longer) gives the app theme.
    func themeTokens(themeId: String?, variant: ThemeVariant) -> ThemeTokens {
        let settings = profileThemes.settings
        let all = MockupThemes.builtIn + settings.customThemes
        let theme = themeId.flatMap { id in all.first { $0.id == id } }
            ?? all.first { $0.id == settings.appThemeId }
            ?? MockupThemes.builtIn[0]
        switch theme.id {
        case MockupThemes.mumcordId: return variant == .dark ? MockupThemes.mumcordDark : MockupThemes.mumcordLight
        case MockupThemes.oceanId: return variant == .dark ? MockupThemes.oceanDark : MockupThemes.oceanLight
        default: return MockupThemes.derived(theme, variant)
        }
    }

    func setThemeMode(mode: ThemeMode) async throws {
        changeThemes { $0.mode = mode }
    }

    func setAppTheme(themeId: String) async throws {
        _ = try findTheme(themeId)
        changeThemes { $0.appThemeId = themeId }
    }

    func setServerTheme(serverId: Int64, themeId: String?) async throws {
        if let themeId { _ = try findTheme(themeId) }
        changeThemes { $0.serverThemes[serverId] = themeId }
    }

    func deleteCustomTheme(themeId: String) async throws {
        if try findTheme(themeId).builtIn {
            throw BridgeError.InvalidInput(detail: "built-in themes cannot be deleted")
        }
        changeThemes { settings in
            settings.customThemes.removeAll { $0.id == themeId }
            if settings.appThemeId == themeId { settings.appThemeId = MockupThemes.mumcordId }
            settings.serverThemes = settings.serverThemes.filter { $0.value != themeId }
        }
    }

    func themeShare(themeId: String) throws -> ThemeShare {
        let theme = try findTheme(themeId)
        return ThemeShare(code: fakeThemeCode, message: "🎨 Mumcord theme \"\(theme.name)\" – \(fakeThemeCode)")
    }

    /// Reads only `fakeThemeCode` (the theme "Imported") and the mockup's Deep Ocean code.
    func themeCodePreview(text: String) throws -> ThemeCodePreview {
        let theme = try pastedTheme(text)
        let imported = profileThemes.settings.customThemes.first { $0.name == theme.name && $0.dark == theme.dark }
        return ThemeCodePreview(
            name: theme.name,
            dark: theme.dark,
            light: theme.light,
            createdWith: "0.9.0",
            madeWithNewer: false,
            importedId: imported?.id
        )
    }

    func importThemeCode(text: String) async throws -> Theme {
        guard let theme = try? pastedTheme(text) else {
            throw BridgeError.InvalidInput(detail: "this is not a valid theme code")
        }
        if let existing = profileThemes.settings.customThemes.first(where: { $0.name == theme.name && $0.dark == theme.dark }) {
            return existing
        }
        let added = profileThemes.addCustom { id in
            Theme(id: id, name: theme.name, builtIn: false, dark: theme.dark, light: theme.light)
        }
        emit(.themesChanged(settings: profileThemes.settings))
        return added
    }

    private func pastedTheme(_ text: String) throws -> Theme {
        if text.contains(fakeThemeCode) {
            var theme = MockupThemes.fromTheDesktop(id: "")
            theme.name = "Imported"
            return theme
        }
        if text.contains(mockupOceanCode) { return MockupThemes.builtIn[1] }
        throw ThemeCodeError.Invalid
    }

    private func findTheme(_ themeId: String) throws -> Theme {
        guard let theme = (MockupThemes.builtIn + profileThemes.settings.customThemes).first(where: { $0.id == themeId }) else {
            throw BridgeError.NotFound(detail: "unknown theme")
        }
        return theme
    }

    /// As the engine: a change arrives as an event; nothing changed, nothing arrives.
    private func changeThemes(_ change: (inout ThemeSettings) -> Void) {
        guard let changed = profileThemes.changeSettings(change) else { return }
        emit(.themesChanged(settings: changed))
    }
}
#endif
