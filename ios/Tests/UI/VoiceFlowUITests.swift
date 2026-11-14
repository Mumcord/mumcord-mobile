// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Voice in the running app against the fake engine: the voice bar and the full voice view,
/// mute, deafen, leave, the talk mode switch, whisper and shout, the microphone question and the
/// explanation of local network access. The server is the one of the voice mockup: everybody is
/// in the Lobby.
final class VoiceFlowUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// Connects to Nordlicht through the server list. A sheet that opens at once hides the
    /// channels from the test, so it does not wait for them.
    private func launchAndConnect(_ options: String) -> XCUIApplication {
        let app = launchApp("servers,voice-server," + options)
        tapWhenReady(app.element("Connect to Nordlicht"))
        return app
    }

    /// The channels of Nordlicht, connected through the server list.
    private func launchConnected(_ options: String) -> XCUIApplication {
        let app = launchAndConnect(options)
        expectAppears(app.row("channel-row", containing: "Lobby"))
        return app
    }

    /// Holds the middle of the element down with a real touch, see `tapCenter`.
    private func hold(_ element: XCUIElement, seconds: TimeInterval) {
        element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: seconds)
    }

    private func openVoiceView(_ app: XCUIApplication) {
        expectAppears(app.element("voice-bar"))
        tapWhenReady(app.element(containing: "Voice connected"))
        expectAppears(app.element("voice-view"))
    }

    private func expectLabel(_ label: String, of identifier: String, in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        expectAppears(app.row(identifier, containing: label), file: file, line: line)
    }

    func testTheVoiceBarOpensTheVoiceViewWithEverybodyOfTheChannelAndTheChevronClosesIt() {
        let app = launchConnected("ptt")
        openVoiceView(app)
        for name in ["Anna", "Ben", "Mira", "Jonas", "Lea (you)"] {
            expectAppears(app.row("voice-tile", containing: name))
        }
        expectAppears(app.element("voice-hold"))
        XCTAssertFalse(app.element("voice-level").exists)

        tapWhenReady(app.element("voice-collapse"))
        expectGone(app.element("voice-view"))
        expectAppears(app.element("voice-bar"))
    }

    func testMuteAndDeafenChangeTheButtonsAndUnmutingEndsTheDeafen() {
        let app = launchConnected("ptt")
        openVoiceView(app)
        expectLabel("Mute microphone", of: "voice-mute", in: app)
        tapWhenReady(app.element("voice-mute"))
        expectLabel("Unmute microphone", of: "voice-mute", in: app)
        tapWhenReady(app.element("voice-mute"))
        expectLabel("Mute microphone", of: "voice-mute", in: app)

        tapWhenReady(app.element("voice-deafen"))
        expectLabel("Undeafen", of: "voice-deafen", in: app)
        // Deafen implies mute.
        expectLabel("Unmute microphone", of: "voice-mute", in: app)
        tapWhenReady(app.element("voice-mute"))
        expectLabel("Deafen", of: "voice-deafen", in: app)
        expectLabel("Mute microphone", of: "voice-mute", in: app)
    }

    func testTheTalkModeSwitchShowsTheLevelMeterOrTheHoldButtonWhichCanBeHeld() {
        let app = launchConnected("ptt")
        openVoiceView(app)
        tapWhenReady(app.element("Voice activity"))
        expectAppears(app.element("voice-level"))
        expectGone(app.element("voice-hold"))

        tapWhenReady(app.element("Push-to-talk"))
        let hold = app.element("voice-hold")
        expectAppears(hold)
        expectGone(app.element("voice-level"))
        self.hold(hold, seconds: 0.6)
        // Let go: nobody talks any more.
        expectAppears(app.row("voice-hold", containing: "Hold to talk"))
    }

    func testLeaveEndsTheConnectionAtOnceFromTheVoiceViewAndFromTheBar() {
        let app = launchConnected("ptt")
        openVoiceView(app)
        tapWhenReady(app.element("voice-leave"))
        expectAppears(app.element("Connect to Nordlicht"))
        expectGone(app.element("voice-view"))

        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.element("voice-bar"))
        tapWhenReady(app.element("Leave server"))
        expectAppears(app.element("Connect to Nordlicht"))
        expectGone(app.element("voice-bar"))
    }

    func testWhisperToAPersonAndShoutToAChannelShowTheWhisperBarUntilItIsClosed() {
        let app = launchConnected("ptt")
        tapWhenReady(app.row("person-row", containing: "Jonas"))
        tapWhenReady(app.element("person-whisper"))
        expectGone(app.element("person-whisper"))
        let hold = app.row("whisper-hold", containing: "Hold to whisper to Jonas")
        expectAppears(hold)
        self.hold(hold, seconds: 0.6)
        expectAppears(hold)
        tapWhenReady(app.element("whisper-close"))
        expectGone(app.element("whisper-bar"))

        self.hold(app.row("channel-row", containing: "Gaming"), seconds: 1.0)
        tapWhenReady(app.element("channel-menu-shout"))
        expectAppears(app.row("whisper-hold", containing: "Hold to shout to Gaming"))
        // The bar is also in the full voice view.
        openVoiceView(app)
        expectAppears(app.row("whisper-hold", containing: "Hold to shout to Gaming"))
    }

    func testTheMicrophoneIsExplainedOnceAndNotNowLeavesANoticeThatAllowsIt() {
        let app = launchAndConnect("mic-ask")
        expectAppears(app.element("microphone-continue"))
        tapWhenReady(app.element("microphone-not-now"))
        expectGone(app.element("microphone-continue"))
        let banner = app.element("microphone-banner")
        expectAppears(banner)
        expectAppears(app.element(containing: "The others cannot hear you"))
        // The mute button of the voice bar leads to the permission too.
        expectAppears(app.element(containing: "Microphone not allowed"))

        // Tapping the notice lets the system ask; the fake system says yes.
        banner.tapCenter()
        expectGone(banner)
        XCTAssertFalse(app.element("microphone-continue").exists)
    }

    func testAllowingTheMicrophoneFromTheExplanationLeavesNoNotice() {
        let app = launchAndConnect("mic-ask")
        tapWhenReady(app.element("microphone-continue"))
        expectGone(app.element("microphone-continue"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
        XCTAssertFalse(app.element("microphone-banner").exists)
    }

    func testARefusedMicrophoneLeavesTheNoticeThatPointsToTheSystemSettings() {
        let app = launchAndConnect("mic-refuse")
        tapWhenReady(app.element("microphone-continue"))
        expectAppears(app.element("microphone-banner"))
        expectAppears(app.element(containing: "in the system settings"))
        // Listening goes on: the channels are still there.
        expectAppears(app.row("person-row", containing: "Anna"))
    }

    func testALocalServerIsExplainedBeforeItConnects() {
        let app = launchApp("servers,localnet")
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.element("Local network access"))
        expectAppears(app.element(containing: "Nordlicht is on your local network"))
        XCTAssertFalse(app.row("channel-row", containing: "Lobby").exists)

        tapWhenReady(app.element("local-network-continue"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
    }

    func testALocalServerThatIosDoesNotLetTheAppReachSaysSoAndOffersTheSettings() {
        let app = launchApp("servers,localnet-refused,unreachable")
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.element("connect-error"))
        expectAppears(app.element(containing: "Mumcord may not access your local network"))
        expectAppears(app.element("local-network-settings"))

        // Allowed meanwhile (the fake only fails once): trying again connects.
        tapWhenReady(app.element("connect-retry"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
    }
}
