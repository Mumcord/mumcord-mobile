// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// The audio output in the running app against the fake engine and a faked phone: the chip of
/// the voice view, the sheet that switches between the outputs, and the question of the first
/// connection (FR-5.34, AC-5.11). The phone has a loudspeaker and an earpiece.
final class OutputFlowUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// Connects to Nordlicht through the server list. A sheet that opens at once hides the
    /// channels from the test, so it does not wait for them.
    private func launchAndConnect(_ options: String) -> XCUIApplication {
        let app = launchApp("servers,voice-server,ptt,outputs," + options)
        tapWhenReady(app.element("Connect to Nordlicht"))
        return app
    }

    private func openVoiceView(_ app: XCUIApplication) {
        expectAppears(app.element("voice-bar"))
        tapWhenReady(app.element(containing: "Voice connected"))
        expectAppears(app.element("voice-view"))
    }

    private func expectChip(_ output: String, in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        expectAppears(app.row("output-chip", containing: "Audio output: " + output), file: file, line: line)
    }

    func testAConnectedHeadsetIsUsedAndTheSheetSwitchesBetweenAllOutputs() {
        let app = launchAndConnect("output-bluetooth")
        openVoiceView(app)
        expectChip("Lea's Buds", in: app)

        tapWhenReady(app.element("output-chip"))
        expectAppears(app.row("output-row-Bluetooth", containing: "Lea's Buds"))
        expectAppears(app.row("output-row-Earpiece", containing: "Earpiece"))
        // No wired headset is plugged in: it is listed, but cannot be picked.
        let wired = app.row("output-row-WiredHeadset", containing: "Not connected")
        expectAppears(wired)
        XCTAssertFalse(wired.isEnabled)
        wired.tapCenter()
        expectAppears(app.element("output-row-Speaker"))

        tapWhenReady(app.element("output-row-Speaker"))
        expectGone(app.element("output-row-Speaker"))
        expectChip("Speaker", in: app)

        tapWhenReady(app.element("output-chip"))
        tapWhenReady(app.element("output-row-Earpiece"))
        expectChip("Earpiece", in: app)
    }

    func testTheSavedLoudspeakerIsUsedWithoutAQuestion() {
        let app = launchAndConnect("plain")
        expectAppears(app.row("channel-row", containing: "Lobby"))
        XCTAssertFalse(app.element("output-question-speaker").exists)
        openVoiceView(app)
        expectChip("Speaker", in: app)
    }

    func testTheFirstConnectionAsksLoudspeakerOrEarpieceAndUsesTheAnswer() {
        let app = launchAndConnect("output-ask")
        expectAppears(app.element("Loudspeaker or earpiece?"))
        tapWhenReady(app.element("output-question-speaker"))
        expectGone(app.element("output-question-speaker"))
        openVoiceView(app)
        expectChip("Speaker", in: app)
    }

    func testAnsweringEarpieceKeepsTheVoiceOnTheEarpiece() {
        let app = launchAndConnect("output-ask")
        tapWhenReady(app.element("output-question-earpiece"))
        expectGone(app.element("output-question-earpiece"))
        openVoiceView(app)
        expectChip("Earpiece", in: app)
    }
}
