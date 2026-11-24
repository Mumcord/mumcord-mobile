// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// The settings icon of the server list (mockup `01-server-list`): without a connection it leads
/// to the More screen with the identities, the themes and the settings, and nothing that needs a
/// server is shown there. Against the fake engine.
final class ServerListMoreFlowUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func openMore() -> XCUIApplication {
        let app = launchApp("servers")
        expectAppears(app.element(containing: "5 online · 24 ms"))
        let icon = app.element("server-settings")
        expectAppears(icon)
        XCTAssertEqual(icon.label, "Settings", "VoiceOver names the icon")
        XCTAssertGreaterThanOrEqual(icon.frame.width, 44, "a 44 pt target")
        XCTAssertGreaterThanOrEqual(icon.frame.height, 44, "a 44 pt target")
        tapWhenReady(icon)
        expectAppears(app.element("more-settings"))
        return app
    }

    func testTheIconLeadsToIdentitiesThemesAndSettingsAndBackToTheList() {
        let app = openMore()
        expectAppears(app.element("more-identities"))
        expectAppears(app.element("more-themes"))
        for connectionBound in ["more-activity", "more-blocked", "more-disconnect", "more-edit-profile"] {
            XCTAssertFalse(app.element(connectionBound).exists, "\(connectionBound) needs a server")
        }

        tapWhenReady(app.element("more-identities"))
        expectAppears(app.element("identities-screen"))
        tapWhenReady(app.element("Back"))
        expectAppears(app.element("more-themes"))

        tapWhenReady(app.element("more-themes"))
        expectAppears(app.element("themes-mode-system"))
        XCTAssertFalse(app.element("themes-server-note").exists, "no server theme without a server")
        tapWhenReady(app.element("Back"))
        expectAppears(app.element("more-settings"))

        tapWhenReady(app.element("more-settings"))
        expectAppears(app.element("settings-talk-mode"))
        tapWhenReady(app.element("Back to More"))
        expectAppears(app.element("more-identities"))

        tapWhenReady(app.element("Back to the server list"))
        expectAppears(app.element("server-settings"))
        expectAppears(app.element("Connect to Nordlicht"))
    }

    /// The settings that need a running voice session are left out; a switch is saved without a
    /// connection and the screen stays open while it is.
    func testTheSettingsWorkWithoutAConnection() {
        let app = openMore()
        tapWhenReady(app.element("more-settings"))
        expectAppears(app.element("settings-talk-mode"))
        expectAppears(app.switches.matching(identifier: "settings-noise-suppression").firstMatch)
        XCTAssertFalse(app.element("settings-input-level").exists, "the level only moves while the voice runs")
        XCTAssertFalse(app.element("settings-default-output").exists, "the outputs are known only during voice")

        let idle = app.switches.matching(identifier: "settings-idle-disconnect").firstMatch
        reveal(idle, in: app)
        XCTAssertEqual(idle.value as? String, "0")
        tapWhenReady(idle)
        let on = NSPredicate(format: "identifier == %@ AND value == %@", "settings-idle-disconnect", "1")
        expectAppears(app.switches.matching(on).firstMatch)
        expectAppears(app.element("settings-talk-mode")) // the settings stay open after a save

        // Closed and opened again, the switch is still on.
        tapWhenReady(app.element("Back to More"))
        tapWhenReady(app.element("more-settings"))
        let reopened = app.switches.matching(on).firstMatch
        reveal(reopened, in: app)
        expectAppears(reopened)
    }
}
