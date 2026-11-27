// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// The languages of the app (FR-5.82): the 24 of the desktop app as ISO 639-1 codes, the codes
/// the shared `language` setting stores. `scripts/translations.py` writes their texts into
/// `Resources/<language>.lproj` with the same list.
let appLanguages: [String] = [
    "en", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "ga", "hr",
    "hu", "it", "lt", "lv", "mt", "nl", "pl", "pt", "ro", "sk", "sl", "sv",
]

/// A language under its own name ("Deutsch", "Français"), as the desktop's language picker shows it.
func languageEndonym(_ code: String) -> String {
    let locale = Locale(identifier: code)
    let name = locale.localizedString(forLanguageCode: code) ?? code
    return name.prefix(1).uppercased(with: locale) + name.dropFirst()
}

/// The app's own language in the system settings (iOS → Settings → Mumcord → Language). iOS has
/// no way for an app to switch its own language: the user picks it there, and iOS restarts the
/// app in it. It says the same as the shared `language` setting: nil follows the phone,
/// otherwise one of `appLanguages`.
struct AppLanguages: Sendable {
    /// The languages saved for this app alone, most wanted first; nil when it follows the phone.
    let appPreferences: @Sendable () -> [String]?

    /// The app's own language, or nil when it follows the phone.
    func current() -> String? {
        guard let first = appPreferences()?.first,
              let code = Locale(identifier: first).language.languageCode?.identifier
        else { return nil }
        // A language the app has no texts for counts as following the phone, as on Android.
        return appLanguages.contains(code) ? code : nil
    }

    /// The system's per-app language: iOS saves it as `AppleLanguages` in the app's own
    /// preferences. The launch argument `-AppleLanguages` of the tests is not saved there, so a
    /// test launch follows the phone.
    static let system = AppLanguages {
        guard let id = Bundle.main.bundleIdentifier else { return nil }
        return UserDefaults.standard.persistentDomain(forName: id)?["AppleLanguages"] as? [String]
    }

    /// The app follows the phone's language (the tests).
    static let phoneOnly = AppLanguages { nil }
}
