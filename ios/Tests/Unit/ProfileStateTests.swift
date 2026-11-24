// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

@testable import Mumcord

/// When the edit profile screen may save, and what it shows.
final class ProfileStateTests: XCTestCase {
    private let edit = ProfileEdit(comment: "Hello", initialComment: "Hello", limit: 10)

    func testNothingChangedCannotBeSaved() {
        XCTAssertFalse(edit.changed)
        XCTAssertFalse(edit.canSave)
    }

    func testAChangedCommentOrAvatarCanBeSaved() {
        var comment = edit
        comment.comment = "Hi"
        XCTAssertTrue(comment.canSave)
        var removed = edit
        removed.avatar = .remove
        XCTAssertTrue(removed.canSave)
        var set = edit
        set.avatar = .set(Data([1]))
        XCTAssertTrue(set.canSave)
        comment.saving = true
        XCTAssertFalse(comment.canSave, "not while saving")
    }

    func testACommentLongerThanTheServersLimitCannotBeSaved() {
        var atLimit = edit
        atLimit.comment = String(repeating: "a", count: 10)
        XCTAssertFalse(atLimit.tooLong)
        XCTAssertTrue(atLimit.canSave)
        var over = edit
        over.comment = String(repeating: "a", count: 11)
        XCTAssertTrue(over.tooLong)
        XCTAssertFalse(over.canSave)
        over.limit = nil
        XCTAssertFalse(over.tooLong, "no limit known yet")
    }

    /// Mumble reports 0 for a server without a comment limit, as for chat messages.
    func testAServerWithoutALimitTakesAnyComment() {
        var unlimited = edit
        unlimited.limit = 0
        unlimited.comment = String(repeating: "a", count: 6000)
        XCTAssertFalse(unlimited.tooLong)
        XCTAssertTrue(unlimited.canSave)
        XCTAssertNil(unlimited.shownLimit, "no counter")
        var photoOnly = ProfileEdit(comment: "", initialComment: "", limit: 0)
        photoOnly.avatar = .set(Data([1]))
        XCTAssertTrue(photoOnly.canSave)
        XCTAssertEqual(edit.shownLimit, 10)
    }

    /// As on Android, a character outside the basic plane counts twice.
    func testTheLimitCountsAsTheAndroidAppDoes() {
        var emoji = edit
        emoji.comment = "🎨🎨🎨🎨🎨🎨"
        XCTAssertEqual(emoji.length, 12)
        XCTAssertTrue(emoji.tooLong)
    }

    func testTheMoreTabShowsTheFirstLineOfTheComment() {
        XCTAssertEqual(OwnProfile(name: "Lea", comment: "\n  Playing late \nAsk me", avatar: nil).commentLine, "Playing late")
        XCTAssertEqual(OwnProfile(name: "Lea", comment: "", avatar: nil).commentLine, "")
    }

    func testTheEditorShowsTheNewTheRemovedOrTheCurrentPicture() {
        let own = OwnProfile(name: "Lea", comment: "", avatar: Data([1]))
        XCTAssertEqual(profileEditUiState(own: own, edit: edit).avatar, Data([1]))
        var removed = edit
        removed.avatar = .remove
        XCTAssertNil(profileEditUiState(own: own, edit: removed).avatar)
        var set = edit
        set.avatar = .set(Data([2]))
        XCTAssertEqual(profileEditUiState(own: own, edit: set).avatar, Data([2]))
    }

    func testProfilesAreComparedByTheirContent() {
        XCTAssertEqual(
            OwnProfile(name: "Lea", comment: "x", avatar: Data([1, 2])),
            OwnProfile(name: "Lea", comment: "x", avatar: Data([1, 2]))
        )
    }
}
