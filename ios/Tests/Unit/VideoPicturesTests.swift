// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

@testable import Mumcord

/// The last picture of each watched stream, which the next view of the stream starts with.
final class VideoPicturesTests: XCTestCase {
    private let pictures = VideoPictures()

    func testTheViewsOfAWatchedStreamShareItsPicture() {
        pictures.keep([1])
        let first = pictures.picture(for: 1, held: nil)
        let next = pictures.picture(for: 1, held: nil)
        XCTAssertTrue(first === next, "the next view starts with the picture of the view before")
    }

    func testAnotherStreamNeverGetsThePictureOfTheFirst() {
        pictures.keep([1, 2])
        let first = pictures.picture(for: 1, held: nil)
        XCTAssertFalse(pictures.picture(for: 2, held: nil) === first)
    }

    func testAStreamNoLongerWatchedDropsItsPicture() {
        pictures.keep([1])
        let watched = pictures.picture(for: 1, held: nil)
        pictures.keep([])
        XCTAssertFalse(pictures.picture(for: 1, held: nil) === watched, "a new view starts empty")
        XCTAssertTrue(pictures.picture(for: 1, held: watched) === watched, "a view keeps what it shows")
    }

    func testThePictureAViewHeldBeforeTheStreamWasWatchedBecomesTheShared() {
        let own = pictures.picture(for: 1, held: nil)
        pictures.keep([1])
        XCTAssertTrue(pictures.picture(for: 1, held: own) === own)
        XCTAssertTrue(pictures.picture(for: 1, held: nil) === own)
    }
}
