// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Drives the app against the fake engine and a faked phone to the states of the Android
/// screenshot tests for the audio output and "disconnect when alone", and keeps a picture of
/// each in both themes, as `ios-<name>-<theme>`. Each state has its own test method, so a failure names the screen.
final class PhoneScreenshotUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func capture(_ app: XCUIApplication, _ name: String, theme: String) {
        // The sheets slide in and the lists settle; the picture is taken once they are still.
        Thread.sleep(forTimeInterval: 0.5)
        keepScreenshot(of: app, named: "ios-\(name)-\(theme)")
    }

    /// Connects to Nordlicht through the server list, once its ping answers are there.
    private func launchAndConnect(_ options: String, theme: String) -> XCUIApplication {
        let app = launchApp("servers,voice-server,ptt," + options, theme: theme)
        expectAppears(app.element(containing: "5 online · 24 ms"))
        expectAppears(app.element(containing: "Offline"))
        tapWhenReady(app.element("Connect to Nordlicht"))
        return app
    }

    private func openVoiceView(_ app: XCUIApplication) {
        expectAppears(app.row("person-row", containing: "Jonas"))
        tapWhenReady(app.element(containing: "Voice connected"))
        expectAppears(app.element("voice-view"))
    }

    // MARK: States

    /// Mockup `03-voice-expanded`: the chip in the header of the voice view.
    private func outputChip(theme: String) {
        let app = launchAndConnect("outputs", theme: theme)
        openVoiceView(app)
        expectAppears(app.row("output-chip", containing: "Speaker"))
        capture(app, "voice-view-output-chip", theme: theme)
    }

    /// Mockup `04-audio-output`: the loudspeaker is chosen although a headset is connected.
    private func audioOutput(theme: String) {
        let app = launchAndConnect("outputs,output-bluetooth", theme: theme)
        openVoiceView(app)
        tapWhenReady(app.element("output-chip"))
        tapWhenReady(app.element("output-row-Speaker"))
        expectAppears(app.row("output-chip", containing: "Speaker"))
        tapWhenReady(app.element("output-chip"))
        expectAppears(app.row("output-row-Bluetooth", containing: "Lea's Buds"))
        capture(app, "audio-output", theme: theme)
    }

    /// The question of the first connection without a headset; it has no mockup.
    private func firstConnect(theme: String) {
        let app = launchAndConnect("outputs,output-ask", theme: theme)
        expectAppears(app.element("output-question-earpiece"))
        capture(app, "audio-output-first-connect", theme: theme)
    }

    /// The app after it disconnected because the user was alone; it has no mockup.
    private func idleDisconnected(theme: String) {
        let app = launchApp("servers,alone,idle-on,idle-short", theme: theme)
        expectAppears(app.element(containing: "5 online · 24 ms"))
        expectAppears(app.element(containing: "Offline"))
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.element("session-ended-reconnect"), timeout: 30)
        capture(app, "idle-disconnect-notification-state", theme: theme)
    }

    // MARK: Tests

    func testOutputChipDark() { outputChip(theme: "dark") }
    func testOutputChipLight() { outputChip(theme: "light") }
    func testAudioOutputDark() { audioOutput(theme: "dark") }
    func testAudioOutputLight() { audioOutput(theme: "light") }
    func testFirstConnectDark() { firstConnect(theme: "dark") }
    func testFirstConnectLight() { firstConnect(theme: "light") }
    func testIdleDisconnectedDark() { idleDisconnected(theme: "dark") }
    func testIdleDisconnectedLight() { idleDisconnected(theme: "light") }
}
