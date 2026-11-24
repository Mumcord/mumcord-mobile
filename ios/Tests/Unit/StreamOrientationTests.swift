// SPDX-License-Identifier: AGPL-3.0-only

import UIKit
import XCTest

@testable import Mumcord

/// Where the screen turns when the stream's landscape lock ends.
final class StreamOrientationTests: XCTestCase {
    func testAPhoneHeldSidewaysIsLeftToTheSystem() {
        XCTAssertNil(orientationAfterLandscape(phone: .landscapeLeft, before: .portrait))
        XCTAssertNil(orientationAfterLandscape(phone: .landscapeRight, before: .portrait))
    }

    func testAPhoneHeldUprightTurnsBackToPortrait() {
        XCTAssertEqual(orientationAfterLandscape(phone: .portrait, before: .landscapeLeft), .portrait)
    }

    func testAPhoneLyingFlatGoesBackToWhereTheScreenWasBefore() {
        for phone in [UIDeviceOrientation.faceUp, .faceDown, .unknown] {
            XCTAssertEqual(orientationAfterLandscape(phone: phone, before: .landscapeLeft), .landscapeLeft, "\(phone.rawValue)")
            XCTAssertEqual(orientationAfterLandscape(phone: phone, before: .landscapeRight), .landscapeRight, "\(phone.rawValue)")
            XCTAssertEqual(orientationAfterLandscape(phone: phone, before: .portrait), .portrait, "\(phone.rawValue)")
        }
    }
}
