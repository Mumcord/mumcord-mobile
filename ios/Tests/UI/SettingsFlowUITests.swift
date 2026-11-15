// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// The settings under More (mockup `16-settings`) and "Disconnect when alone" (AC-5.30), against
/// the fake engine.
final class SettingsFlowUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func connect(_ options: String) -> XCUIApplication {
        let app = launchApp("servers," + options)
        expectAppears(app.element(containing: "5 online · 24 ms"))
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
        return app
    }

    private func toggle(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.switches.matching(identifier: id).firstMatch
    }

    private func isOn(_ element: XCUIElement) -> Bool {
        (element.value as? String) == "1"
    }

    /// The four switches show the saved settings, are saved at once and are still set after
    /// the screen was closed and opened again.
    func testTheSwitchesAreSavedAndShownAgain() {
        let app = connect("voice-server")
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "More")).firstMatch)
        tapWhenReady(app.element("more-settings"))
        expectAppears(app.element(containing: "Mobile data"))
        let media = toggle(app, "settings-load-media")
        let idle = toggle(app, "settings-idle-disconnect")
        expectAppears(media)
        XCTAssertTrue(isOn(media))
        XCTAssertFalse(isOn(toggle(app, "settings-autoplay-streams")))
        XCTAssertFalse(isOn(toggle(app, "settings-reduced-voice")))
        XCTAssertFalse(isOn(idle))

        media.tapCenter()
        idle.tapCenter()
        XCTAssertTrue(waitFor { !self.isOn(media) && self.isOn(idle) })

        tapWhenReady(app.element("Back to More"))
        expectAppears(app.element("more-disconnect"))
        tapWhenReady(app.element("more-settings"))
        expectAppears(toggle(app, "settings-load-media"))
        XCTAssertFalse(isOn(toggle(app, "settings-load-media")))
        XCTAssertTrue(isOn(toggle(app, "settings-idle-disconnect")))
    }

    /// Alone in a channel with the setting on: the app disconnects by itself when nobody
    /// reacts, says so, and offers to connect again (times shortened by the debug launch).
    func testAloneTooLongDisconnectsAndSaysSo() {
        let app = connect("alone,idle-on,idle-short")
        expectAppears(app.element("session-ended-reconnect"), timeout: 30)
        expectAppears(app.element(containing: "alone in a channel on Nordlicht for 30 minutes"))
        tapWhenReady(app.element("session-ended-reconnect"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
    }

    /// Someone else in the channel: nothing happens, however long it takes.
    func testNotAloneStaysConnected() {
        let app = connect("voice-server,idle-on,idle-short")
        XCTAssertFalse(app.element("session-ended-reconnect").waitForExistence(timeout: 10))
        XCTAssertTrue(app.row("channel-row", containing: "Lobby").exists)
    }

    private func waitFor(_ condition: @escaping () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if condition() { return true }
            Thread.sleep(forTimeInterval: 0.1)
        }
        return condition()
    }
}
