// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Drives the app against the fake engine to the voice states of the Android screenshot tests
/// and keeps a picture of each in both themes, as `ios-<name>-<theme>`. CI publishes them next
/// to the Android ones, so the two platforms can be compared. Each state has its own test
/// method, so a failure names the screen.
final class VoiceScreenshotUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func capture(_ app: XCUIApplication, _ name: String, theme: String) {
        // The sheets slide in and the lists settle; the picture is taken once they are still.
        Thread.sleep(forTimeInterval: 0.5)
        keepScreenshot(of: app, named: "ios-\(name)-\(theme)")
    }

    /// The server list with Nordlicht at the top, with its ping answers.
    private func launchWithServers(_ options: String, theme: String) -> XCUIApplication {
        let app = launchApp("servers," + options, theme: theme)
        expectAppears(app.element(containing: "5 online · 24 ms"))
        expectAppears(app.element(containing: "Offline"))
        return app
    }

    private func launchConnected(_ options: String, theme: String) -> XCUIApplication {
        let app = launchWithServers(options, theme: theme)
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
        expectAppears(app.row("person-row", containing: "Jonas"))
        return app
    }

    private func openVoiceView(_ app: XCUIApplication) {
        tapWhenReady(app.element(containing: "Voice connected"))
        expectAppears(app.element("voice-view"))
    }

    // MARK: States

    private func voiceBar(theme: String) {
        let app = launchConnected("voice-server,ptt", theme: theme)
        expectAppears(app.element("voice-bar"))
        capture(app, "channels-voice-bar", theme: theme)
    }

    private func pushToTalk(theme: String) {
        let app = launchConnected("voice-server,ptt", theme: theme)
        openVoiceView(app)
        expectAppears(app.element("voice-hold"))
        capture(app, "voice-view-ptt", theme: theme)
    }

    private func voiceActivity(theme: String) {
        let app = launchConnected("voice-server,level", theme: theme)
        openVoiceView(app)
        expectAppears(app.element("voice-level"))
        capture(app, "voice-view-activity", theme: theme)
    }

    private func muted(theme: String) {
        let app = launchConnected("voice-server,ptt,self-muted", theme: theme)
        openVoiceView(app)
        expectAppears(app.row("voice-mute", containing: "Unmute microphone"))
        capture(app, "voice-view-muted", theme: theme)
    }

    private func whisperBar(theme: String) {
        let app = launchConnected("voice-server,ptt", theme: theme)
        tapWhenReady(app.row("person-row", containing: "Jonas"))
        tapWhenReady(app.element("person-whisper"))
        expectAppears(app.element("whisper-bar"))
        capture(app, "whisper-bar", theme: theme)
    }

    private func personSheet(theme: String) {
        let app = launchConnected("voice-server,ptt", theme: theme)
        tapWhenReady(app.row("person-row", containing: "Jonas"))
        expectAppears(app.element("person-whisper"))
        capture(app, "person-sheet-whisper", theme: theme)
    }

    private func microphoneExplain(theme: String) {
        // The sheet opens with the connection and hides the channels from the test.
        let app = launchWithServers("mic-ask", theme: theme)
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.element("microphone-continue"))
        capture(app, "mic-explain", theme: theme)
    }

    private func microphoneDenied(theme: String) {
        let app = launchConnected("mic-declined", theme: theme)
        expectAppears(app.element("microphone-banner"))
        capture(app, "mic-denied", theme: theme)
    }

    private func localNetworkExplain(theme: String) {
        let app = launchWithServers("localnet", theme: theme)
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.element("local-network-continue"))
        capture(app, "localnet-explain", theme: theme)
    }

    private func localNetworkDenied(theme: String) {
        let app = launchWithServers("localnet-refused,unreachable", theme: theme)
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.element(containing: "Mumcord may not access your local network"))
        capture(app, "localnet-denied", theme: theme)
    }

    // MARK: Tests

    func testVoiceBarDark() { voiceBar(theme: "dark") }
    func testVoiceBarLight() { voiceBar(theme: "light") }
    func testPushToTalkDark() { pushToTalk(theme: "dark") }
    func testPushToTalkLight() { pushToTalk(theme: "light") }
    func testVoiceActivityDark() { voiceActivity(theme: "dark") }
    func testVoiceActivityLight() { voiceActivity(theme: "light") }
    func testMutedDark() { muted(theme: "dark") }
    func testMutedLight() { muted(theme: "light") }
    func testWhisperBarDark() { whisperBar(theme: "dark") }
    func testWhisperBarLight() { whisperBar(theme: "light") }
    func testPersonSheetDark() { personSheet(theme: "dark") }
    func testPersonSheetLight() { personSheet(theme: "light") }
    func testMicrophoneExplainDark() { microphoneExplain(theme: "dark") }
    func testMicrophoneExplainLight() { microphoneExplain(theme: "light") }
    func testMicrophoneDeniedDark() { microphoneDenied(theme: "dark") }
    func testMicrophoneDeniedLight() { microphoneDenied(theme: "light") }
    func testLocalNetworkExplainDark() { localNetworkExplain(theme: "dark") }
    func testLocalNetworkExplainLight() { localNetworkExplain(theme: "light") }
    func testLocalNetworkDeniedDark() { localNetworkDenied(theme: "dark") }
    func testLocalNetworkDeniedLight() { localNetworkDenied(theme: "light") }
}
