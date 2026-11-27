// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// The app in another of its languages (FR-5.82), started in German with the launch argument
/// `-AppleLanguages (de)` as the system's per-app language would, against the fake engine.
final class LanguageFlowUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testTheServerListIsInGerman() {
        let app = launchApp("servers", language: "de")
        // A plural, filled in by the German texts.
        expectAppears(app.element(containing: "5 online · 24 ms"))
        expectAppears(app.element("Server"))
        expectAppears(app.element("FAVORITEN"))
        expectAppears(app.element("Server hinzufügen"))
        expectAppears(app.element("Mit Nordlicht verbinden"))
        XCTAssertEqual(app.element("server-settings").label, "Einstellungen", "VoiceOver names the icon in German")
        XCTAssertFalse(app.element("Connect to Nordlicht").exists, "no English left")
    }

    /// iOS offers an app's language only in the system settings: the row shows the language and
    /// opens the app's page there.
    func testTheLanguageRowFollowsTheSystemAndOpensTheSystemSettings() {
        let app = launchApp("servers", language: "de")
        tapWhenReady(app.element("server-settings"))
        tapWhenReady(app.element("more-settings"))
        expectAppears(app.element("settings-talk-mode"))
        let row = app.element("settings-language")
        reveal(row, in: app)
        // The launch argument is not the system's per-app language: the app follows the phone.
        XCTAssertTrue(row.label.contains("Sprache der Oberfläche"), row.label)
        XCTAssertTrue(row.label.contains("Wie das System"), row.label)

        row.tapCenter()
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        XCTAssertTrue(settings.wait(for: .runningForeground, timeout: 30), "the system settings did not open")
        app.activate()
        expectAppears(app.element("settings-language"))
    }
}
