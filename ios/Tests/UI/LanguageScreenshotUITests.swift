// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// The app in German, in both themes, as `ios-lang-de-<name>-<theme>`: the server list and the
/// end of the settings with the language row. Each state has its own test method, so a failure names the
/// screen.
final class LanguageScreenshotUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func capture(_ app: XCUIApplication, _ name: String, theme: String) {
        // The screens settle; the picture is taken once they are still.
        Thread.sleep(forTimeInterval: 0.5)
        keepScreenshot(of: app, named: "ios-lang-de-\(name)-\(theme)")
    }

    private func serverList(theme: String) {
        let app = launchApp("servers", theme: theme, locale: "de_DE", language: "de")
        expectAppears(app.element("Mit Nordlicht verbinden"))
        capture(app, "server-list", theme: theme)
    }

    /// The settings while connected, at their end with the language row; the voice bar and the
    /// tabs cover the space below the list.
    private func settings(theme: String) {
        let app = launchApp("servers,voice-server", theme: theme, locale: "de_DE", language: "de")
        tapWhenReady(app.element("Mit Nordlicht verbinden"))
        expectAppears(app.row("person-row", containing: "Jonas"))
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Mehr")).firstMatch)
        tapWhenReady(app.element("more-settings"))
        expectAppears(app.element("settings-talk-mode"))
        app.swipeUp()
        app.swipeUp()
        expectAppears(app.element("settings-language"))
        // The list bounces after the swipe; the picture shows it at rest.
        awaitStillScreen()
        capture(app, "settings", theme: theme)
    }

    func testServerListDark() { serverList(theme: "dark") }
    func testServerListLight() { serverList(theme: "light") }
    func testSettingsDark() { settings(theme: "dark") }
    func testSettingsLight() { settings(theme: "light") }
}
