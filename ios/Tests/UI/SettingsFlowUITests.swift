// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// The settings under More (mockup `16-settings`, FR-5.100) and "Disconnect when alone"
/// (AC-5.30), against the fake engine.
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

    private func openSettings(_ app: XCUIApplication) {
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "More")).firstMatch)
        tapWhenReady(app.element("more-settings"))
        expectAppears(app.element("settings-talk-mode"))
    }

    /// Scrolls the settings until the element is above the bars at the bottom, so it can be tapped.
    private func scrollTo(_ element: XCUIElement, in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let bars = app.element(containing: "Voice connected")
        var swipes = 0
        while swipes < 4, !element.exists || element.frame.isEmpty || element.frame.maxY > bars.frame.minY {
            app.swipeUp()
            swipes += 1
        }
        expectAppears(element, file: file, line: line)
    }

    private func expect(_ app: XCUIApplication, _ id: String, on: Bool, file: StaticString = #filePath, line: UInt = #line) {
        let wanted = NSPredicate(format: "identifier == %@ AND value == %@", id, on ? "1" : "0")
        expectAppears(app.switches.matching(wanted).firstMatch, file: file, line: line)
    }

    /// Every section is there, the switches show the defaults of the specification, are saved
    /// at once and are still set after the screen was closed and opened again.
    func testMoreLeadsToTheSettingsAndEverySwitchIsSaved() {
        let app = connect("voice-server")
        openSettings(app)

        // Section headers are shown in capitals, and the label is what is shown.
        expectAppears(app.element(containing: "VOICE"))
        expectAppears(app.row("settings-talk-mode", containing: "Voice activity"))
        expectAppears(app.element("settings-input-level"))
        expect(app, "settings-noise-suppression", on: false)
        // The fake phone has no earpiece, so there is no default to choose.
        XCTAssertFalse(app.element("settings-default-output").exists)
        expectAppears(app.element(containing: "MOBILE DATA"))
        expectAppears(app.element(containing: "BATTERY"))
        expectAppears(app.element(containing: "You get a notification first"))
        expect(app, "settings-autoplay-streams", on: false)
        expect(app, "settings-load-media", on: true)
        expect(app, "settings-reduced-voice", on: false)
        expect(app, "settings-idle-disconnect", on: false)

        for id in ["settings-noise-suppression", "settings-autoplay-streams", "settings-load-media", "settings-reduced-voice", "settings-idle-disconnect"] {
            toggle(app, id).tapCenter()
        }
        expect(app, "settings-noise-suppression", on: true)
        expect(app, "settings-autoplay-streams", on: true)
        expect(app, "settings-load-media", on: false)
        expect(app, "settings-reduced-voice", on: true)
        expect(app, "settings-idle-disconnect", on: true)

        scrollTo(toggle(app, "settings-sender-only"), in: app)
        expectAppears(app.element(containing: "NOTIFICATIONS"))
        expectAppears(app.element(containing: "Notifications only arrive while you are connected to a server."))
        // "Chat" is also the name of a tab; the section is found by its row.
        expectAppears(app.row("settings-history", containing: "Never"))
        expect(app, "settings-notify-private", on: true)
        expect(app, "settings-notify-mentions", on: true)
        expect(app, "settings-sender-only", on: false)
        for id in ["settings-notify-private", "settings-notify-mentions", "settings-sender-only"] {
            toggle(app, id).tapCenter()
        }
        expect(app, "settings-notify-private", on: false)
        expect(app, "settings-notify-mentions", on: false)
        expect(app, "settings-sender-only", on: true)

        tapWhenReady(app.element("Back to More"))
        expectAppears(app.element("more-disconnect"))
        openSettings(app)
        expect(app, "settings-noise-suppression", on: true)
        expect(app, "settings-autoplay-streams", on: true)
        expect(app, "settings-load-media", on: false)
        expect(app, "settings-reduced-voice", on: true)
        expect(app, "settings-idle-disconnect", on: true)
        scrollTo(toggle(app, "settings-sender-only"), in: app)
        expect(app, "settings-notify-private", on: false)
        expect(app, "settings-notify-mentions", on: false)
        expect(app, "settings-sender-only", on: true)
    }

    /// The rows with a value open their choices; a choice is saved, shown in the row and
    /// closes the sheet.
    func testTheRowsWithAValueOpenTheirChoicesAndAChoiceIsSaved() {
        let app = connect("voice-server,outputs")
        openSettings(app)

        tapWhenReady(app.element("settings-talk-mode"))
        expectAppears(app.row("settings-option-0", containing: "Voice activity"))
        tapWhenReady(app.row("settings-option-1", containing: "Push-to-talk"))
        expectGone(app.element("settings-option-1"))
        expectAppears(app.row("settings-talk-mode", containing: "Push-to-talk"))

        // A phone with an earpiece can choose where the voice plays without a headset.
        expectAppears(app.row("settings-default-output", containing: "Loudspeaker"))
        tapWhenReady(app.element("settings-default-output"))
        expectAppears(app.element(containing: "Used when no headset is connected"))
        expectAppears(app.row("settings-option-0", containing: "Ask"))
        tapWhenReady(app.row("settings-option-2", containing: "Earpiece"))
        expectGone(app.element("settings-option-2"))
        expectAppears(app.row("settings-default-output", containing: "Earpiece"))

        scrollTo(app.element("settings-history"), in: app)
        tapWhenReady(app.element("settings-history"))
        expectAppears(app.element(containing: "Older messages are deleted from this phone automatically. The server is not affected."))
        for choice in ["Never", "7 days", "30 days", "90 days", "365 days"] {
            expectAppears(app.element(containing: choice))
        }
        tapWhenReady(app.row("settings-option-3", containing: "90 days"))
        expectGone(app.element("settings-option-3"))
        expectAppears(app.row("settings-history", containing: "90 days"))

        // Tapping beside a sheet closes it without a choice.
        tapWhenReady(app.element("settings-history"))
        expectAppears(app.element("settings-option-0"))
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.1)).tap()
        expectGone(app.element("settings-option-0"))
        expectAppears(app.row("settings-history", containing: "90 days"))

        // The talk mode is the one of the voice view.
        tapWhenReady(app.element("Back to More"))
        tapWhenReady(app.element(containing: "Voice connected"))
        expectAppears(app.element("voice-hold"))
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
}
