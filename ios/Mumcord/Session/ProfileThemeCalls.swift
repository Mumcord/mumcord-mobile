// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

// The engine commands of the profile and theme screens (FR-5.80, FR-5.81).

extension SessionStore {
    /// The comment and avatar of a person on the connected server, the user's own included.
    func personProfile(_ session: UInt32) async -> Outcome<Profile> {
        await call("read profile") { try await $0.personProfile(session: session) }
    }

    /// Saves the user's comment on the connected server; blank removes it.
    func setOwnComment(_ text: String) async -> Outcome<Void> {
        await call("save comment") { try await $0.setOwnComment(text: text) }
    }

    /// Sets the user's avatar from image bytes, or removes it with nil; the engine scales it.
    func setOwnAvatar(_ image: Data?) async -> Outcome<Void> {
        await call("save avatar") { try await $0.setOwnAvatar(image: image) }
    }

    /// The longest comment the connected server takes, in characters; nil when unknown.
    func commentLimit() async -> UInt32? {
        await call("chat limits") { try await $0.chatLimits() }.value?.text
    }

    func setThemeMode(_ mode: ThemeMode) async -> Outcome<Void> {
        await call("save appearance") { try await $0.setThemeMode(mode: mode) }
    }

    func setAppTheme(_ themeId: String) async -> Outcome<Void> {
        await call("save app theme") { try await $0.setAppTheme(themeId: themeId) }
    }

    func setServerTheme(_ serverId: Int64, _ themeId: String?) async -> Outcome<Void> {
        await call("save server theme") { try await $0.setServerTheme(serverId: serverId, themeId: themeId) }
    }

    func deleteCustomTheme(_ themeId: String) async -> Outcome<Void> {
        await call("delete theme") { try await $0.deleteCustomTheme(themeId: themeId) }
    }

    func themeShare(_ themeId: String) async -> Outcome<ThemeShare> {
        await call("share theme") { try $0.themeShare(themeId: themeId) }
    }

    /// Imports the theme code in `text` (untrusted, checked by the engine) and returns the theme.
    func importThemeCode(_ text: String) async -> Outcome<Theme> {
        await call("import theme") { try await $0.importThemeCode(text: text) }
    }

    /// Reads the first theme code in a pasted `text` without importing it.
    func readThemeCode(_ text: String) async -> Outcome<ThemeCodeRead> {
        await call("read theme code") { engine in
            do {
                return ThemeCodeRead.found(try engine.themeCodePreview(text: text))
            } catch ThemeCodeError.Invalid {
                return ThemeCodeRead.invalid
            } catch ThemeCodeError.Unreadable {
                return ThemeCodeRead.unreadable
            }
        }
    }
}

/// What a pasted text holds: a theme to preview, or why it is none.
enum ThemeCodeRead: Equatable {
    case found(ThemeCodePreview)
    /// No theme code, or one that is broken or too large.
    case invalid
    /// A theme whose text cannot reach the contrast the app needs.
    case unreadable
}
