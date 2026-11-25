// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

@testable import Mumcord

/// The plural forms of `Localizable.stringsdict` (written by scripts/translations.py), picked by
/// each language's own rules.
final class StringsPluralTests: XCTestCase {
    /// A text in one of the app's languages, formatted as the app formats it in that language.
    private func plural(_ language: String, _ key: String, count: Int, _ arguments: CVarArg...) throws -> String {
        let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"), "\(language).lproj is missing")
        let bundle = try XCTUnwrap(Bundle(path: path))
        let locale = Strings.textLocale(language: language, region: Locale.Region("US"))
        return Strings.pluralText(key, count: count, arguments: arguments, bundle: bundle, locale: locale)
    }

    func testEnglishHasOneAndOther() throws {
        XCTAssertEqual(try plural("en", "channels_people_count", count: 1, 1), "1 person")
        XCTAssertEqual(try plural("en", "channels_people_count", count: 0, 0), "0 people")
        XCTAssertEqual(try plural("en", "channels_people_count", count: 2, 2), "2 people")
        XCTAssertEqual(try plural("en", "settings_history_days", count: 1, 1), "1 day")
        XCTAssertEqual(try plural("en", "settings_history_days", count: 30, 30), "30 days")
    }

    func testGermanHasOneAndOther() throws {
        XCTAssertEqual(try plural("de", "channels_people_count", count: 1, 1), "1 Person")
        XCTAssertEqual(try plural("de", "channels_people_count", count: 3, 3), "3 Personen")
        XCTAssertEqual(try plural("de", "settings_history_days", count: 1, 1), "1 Tag")
        XCTAssertEqual(try plural("de", "settings_history_days", count: 7, 7), "7 Tage")
    }

    /// Polish has four forms: one, few (2–4, 22–24 …), many (5–21, 25 …) and other (fractions).
    func testPolishPicksAmongItsForms() throws {
        XCTAssertEqual(try plural("pl", "channels_people_count", count: 1, 1), "1 osoba")
        XCTAssertEqual(try plural("pl", "channels_people_count", count: 3, 3), "3 osoby")
        XCTAssertEqual(try plural("pl", "channels_people_count", count: 5, 5), "5 osób")
        XCTAssertEqual(try plural("pl", "channels_people_count", count: 12, 12), "12 osób")
        XCTAssertEqual(try plural("pl", "channels_people_count", count: 22, 22), "22 osoby")
    }

    /// The form follows the number passed as the count, not the first argument, and the other
    /// arguments stay in place.
    func testTheCountIsApartFromTheArguments() throws {
        XCTAssertEqual(try plural("en", "public_count_shown", count: 1, 1, 1), "1 of 1 server")
        XCTAssertEqual(try plural("en", "public_count_shown", count: 12, 1, 12), "1 of 12 servers")
        XCTAssertEqual(try plural("de", "public_count_shown", count: 12, 1, 12), "1 von 12 Servern")
        XCTAssertEqual(try plural("en", "chat_subtitle", count: 1, 1, "Gaming"), "1 person · Gaming")
        XCTAssertEqual(try plural("de", "chat_subtitle", count: 4, 4, "Gaming"), "4 Personen · Gaming")
    }

    /// Numbers passed as text keep their digits as they are.
    func testTheCommentCounterHasNoThousandsSeparator() throws {
        XCTAssertEqual(
            try plural("en", "profile_comment_count_description", count: 5000, "1200", "5000"),
            "1200 of 5000 characters"
        )
    }

    /// Czech: one, few (2–4) and other (5 and more); many is for fractions only.
    func testCzechPicksAmongItsForms() throws {
        XCTAssertEqual(try plural("cs", "settings_history_days", count: 1, 1), "1 den")
        XCTAssertEqual(try plural("cs", "settings_history_days", count: 3, 3), "3 dny")
        XCTAssertEqual(try plural("cs", "settings_history_days", count: 30, 30), "30 dní")
    }

    func testAnUntranslatedPluralIsEnglishInEveryForm() throws {
        // A text only the phone app has, with no Czech translation yet.
        XCTAssertEqual(try plural("cs", "public_count_all", count: 1, 1), "1 server")
        XCTAssertEqual(try plural("cs", "public_count_all", count: 3, 3), "3 servers")
    }

    func testTheTextsFollowTheLanguageOfTheAppWithThePhonesRegion() {
        let locale = Strings.textLocale(language: "de", region: Locale.Region("CH"))
        XCTAssertEqual(locale.language.languageCode, "de")
        XCTAssertEqual(locale.region, "CH")
        XCTAssertEqual(Strings.textLocale(language: "pl", region: nil).identifier, "pl")
    }
}
