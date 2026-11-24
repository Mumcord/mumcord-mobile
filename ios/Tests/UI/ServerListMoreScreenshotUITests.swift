// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// The server list's More screen and its settings without a connection, in both themes, as
/// `ios-<name>-<theme>`. Each state has its own test method, so a failure names the screen.
final class ServerListMoreScreenshotUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func capture(_ app: XCUIApplication, _ name: String, theme: String) {
        // The screens settle; the picture is taken once they are still.
        Thread.sleep(forTimeInterval: 0.5)
        keepScreenshot(of: app, named: "ios-\(name)-\(theme)")
    }

    private func openMore(theme: String) -> XCUIApplication {
        let app = launchApp("servers", theme: theme)
        expectAppears(app.element(containing: "5 online · 24 ms"))
        tapWhenReady(app.element("server-settings"))
        expectAppears(app.element("more-settings"))
        return app
    }

    private func more(theme: String) {
        let app = openMore(theme: theme)
        capture(app, "server-list-more", theme: theme)
    }

    private func settings(theme: String) {
        let app = openMore(theme: theme)
        tapWhenReady(app.element("more-settings"))
        expectAppears(app.element("settings-talk-mode"))
        capture(app, "server-list-settings", theme: theme)
    }

    func testMoreDark() { more(theme: "dark") }
    func testMoreLight() { more(theme: "light") }
    func testSettingsDark() { settings(theme: "dark") }
    func testSettingsLight() { settings(theme: "light") }
}
