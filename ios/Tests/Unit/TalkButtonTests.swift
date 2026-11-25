// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

@testable import Mumcord

/// The hold buttons (talk, whisper, shout) let go when they leave the screen while held.
final class TalkButtonTests: XCTestCase {
    func testAButtonThatLeavesTheScreenWhileHeldReportsTheRelease() {
        var hold = HoldReport()
        XCTAssertEqual(hold.pressed(true), true)
        XCTAssertEqual(hold.disappeared(), false, "the voice must not go on being sent")
        XCTAssertNil(hold.pressed(false), "the late end of the touch is not reported twice")
    }

    func testAButtonThatWasLetGoReportsNothingWhenItLeaves() {
        var hold = HoldReport()
        XCTAssertNil(hold.disappeared(), "never held")
        XCTAssertEqual(hold.pressed(true), true)
        XCTAssertEqual(hold.pressed(false), false)
        XCTAssertNil(hold.disappeared())
    }

    func testEachChangeIsReportedOnce() {
        var hold = HoldReport()
        XCTAssertEqual(hold.pressed(true), true)
        XCTAssertNil(hold.pressed(true))
        XCTAssertEqual(hold.pressed(false), false)
        XCTAssertNil(hold.pressed(false))
    }
}
