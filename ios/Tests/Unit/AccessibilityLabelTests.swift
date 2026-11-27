// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

@testable import Mumcord

/// What VoiceOver reads beside the action of the mute button, and the labels under the voice
/// view's buttons that never break inside a word.
final class AccessibilityLabelTests: XCTestCase {
    func testTheMuteButtonSaysMutedWhileTheMicrophoneIsOff() {
        XCTAssertNil(MuteControl.unmuted.stateLabel)
        XCTAssertEqual(MuteControl.muted.stateLabel, "Muted")
        XCTAssertEqual(MuteControl.noMicrophone.stateLabel, "Muted")
        // Its action label already says "Muted by the server".
        XCTAssertNil(MuteControl.serverMuted.stateLabel)
    }

    func testALabelShrinksOnlyAsFarAsItsLongestWordNeeds() {
        let font = MumcordFonts.font(.smallStrong, scale: 2.35)
        XCTAssertEqual(wordFitFactor("Mute", font: font, width: 400), 1, "a label that fits keeps its size")
        XCTAssertEqual(wordFitFactor("Mute", font: font, width: 0), 1, "before it is laid out")

        let factor = wordFitFactor("Stop sharing", font: font, width: 60)
        XCTAssertLessThan(factor, 1)
        let shrunk = MumcordFonts.font(TextToken.smallStrong.with(size: TextToken.smallStrong.size * factor), scale: 2.35)
        XCTAssertEqual(wordFitFactor("Stop sharing", font: shrunk, width: 60), 1, accuracy: 0.02, "the longest word now fits")
        XCTAssertEqual(
            wordFitFactor("sharing", font: font, width: 60),
            factor,
            accuracy: 0.0001,
            "the longest word decides, not the whole label"
        )
    }
}
