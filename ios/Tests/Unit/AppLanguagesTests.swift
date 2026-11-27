// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

@testable import Mumcord

/// The app's languages and its own language in the system settings (FR-5.82).
final class AppLanguagesTests: XCTestCase {
    func testTheAppShipsTheTwentyFourLanguagesOfTheDesktop() {
        XCTAssertEqual(appLanguages.count, 24)
        XCTAssertEqual(Set(Bundle.main.localizations.filter { $0 != "Base" }), Set(appLanguages))
    }

    func testTheSystemsLanguageForTheAppIsOneOfTheAppsLanguages() {
        XCTAssertNil(AppLanguages { nil }.current(), "follows the phone")
        XCTAssertNil(AppLanguages { [] }.current())
        XCTAssertEqual(AppLanguages { ["de"] }.current(), "de")
        XCTAssertEqual(AppLanguages { ["pt-BR", "en"] }.current(), "pt", "the language without its region")
        XCTAssertNil(AppLanguages { ["ja"] }.current(), "a language without texts follows the phone, as on Android")
    }

    func testLanguagesShowUnderTheirOwnNames() {
        XCTAssertEqual(languageEndonym("de"), "Deutsch")
        XCTAssertEqual(languageEndonym("fr"), "Français")
        XCTAssertEqual(languageEndonym("pl"), "Polski")
    }
}
