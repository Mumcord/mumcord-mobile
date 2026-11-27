// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// The moderation section of the person sheet against the fake engine, on the server of mockup
/// `02-channels`: the user is Lea, Jonas is in Gaming. With the option `admin` the server
/// grants every right on every channel.
final class ModerationFlowUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// Starts the app, connects to Nordlicht and opens Jonas's sheet.
    private func openJonas(_ options: String = "admin") -> XCUIApplication {
        let app = launchApp("servers," + options)
        tapWhenReady(app.element("Connect to Nordlicht"))
        openJonasSheet(app)
        return app
    }

    private func openJonasSheet(_ app: XCUIApplication) {
        tapWhenReady(jonas(app))
        expectAppears(app.element("person-sheet"))
    }

    private func jonas(_ app: XCUIApplication) -> XCUIElement {
        app.row("person-row", containing: "Jonas")
    }

    /// Taps an entry that may lie below the fold of a tall sheet.
    private func tapRevealed(_ element: XCUIElement, in app: XCUIApplication) {
        reveal(element, in: app)
        element.tapCenter()
    }

    func testWithoutAnyRightTheSheetHasNoModeration() {
        let app = openJonas("plain")
        expectAppears(app.element("person-block"))
        XCTAssertFalse(app.element("MODERATION").exists)
        for entry in ["moderation-move", "moderation-mute", "moderation-kick", "moderation-ban"] {
            XCTAssertFalse(app.element(entry).exists, entry)
        }
    }

    func testServerMuteFollowsTheServerAndMovingClosesTheSheets() {
        let app = openJonas()
        expectAppears(app.element("MODERATION"))
        tapRevealed(app.row("moderation-mute", containing: "Server mute"), in: app)
        expectAppears(app.row("moderation-mute", containing: "Remove server mute"))
        tapRevealed(app.row("moderation-mute", containing: "Remove server mute"), in: app)
        expectGone(app.row("moderation-mute", containing: "Remove server mute"))
        expectAppears(app.row("moderation-mute", containing: "Server mute"))

        tapRevealed(app.element("moderation-move"), in: app)
        expectAppears(app.element("Pick the channel Jonas should move to."))
        XCTAssertFalse(app.row("move-choice", containing: "Gaming").exists, "the person's own channel is no choice")
        tapRevealed(app.row("move-choice", containing: "AFK"), in: app)
        expectGone(app.element("move-choice"))
        expectGone(app.element("person-sheet"))

        // The kick question names the channel the person is in now.
        openJonasSheet(app)
        tapRevealed(app.element("moderation-kick"), in: app)
        expectAppears(app.element("Jonas · in AFK"))
    }

    func testEveryStepGoesBackToThePersonSheet() {
        let app = openJonas()
        tapRevealed(app.element("moderation-move"), in: app)
        tapRevealed(app.element("Cancel"), in: app)
        expectAppears(app.element("person-sheet"))

        tapRevealed(app.element("moderation-ban"), in: app)
        expectAppears(app.element("ban-confirm"))
        tapRevealed(app.element("ban-kick-instead"), in: app)
        expectAppears(app.element("Kick Jonas?"))
        tapRevealed(app.element("Cancel"), in: app)
        expectAppears(app.element("person-sheet"))
        expectAppears(app.element("moderation-kick"))
    }

    func testARefusedMoveShowsTheServersRefusalBehindTheSheets() {
        let app = openJonas("admin,moderation-refused")
        tapRevealed(app.element("moderation-move"), in: app)
        tapRevealed(app.row("move-choice", containing: "AFK"), in: app)
        expectGone(app.element("person-sheet"))
        expectAppears(app.row("denied-banner", containing: "Moderation is off tonight"))
    }

    func testARefusedKickSaysSoInItsSheetAndATryAgainGoesThrough() {
        let app = openJonas("admin,moderation-refused")
        tapRevealed(app.element("moderation-kick"), in: app)
        expectAppears(app.element("Kick Jonas?"))
        expectAppears(app.element("Jonas · in Gaming"))
        // The return key puts the keyboard away, which covers the buttons.
        typeText("Cool down\n", into: app.input("Reason"))
        tapRevealed(app.element("kick-confirm"), in: app)
        expectAppears(app.row("moderation-error", containing: "Moderation is off tonight"))

        tapRevealed(app.element("kick-confirm"), in: app)
        expectGone(app.element("kick-confirm"))
        expectGone(app.element("person-sheet"))
        expectGone(jonas(app))
    }

    func testABanTakesTheChosenLength() {
        let app = openJonas()
        tapRevealed(app.element("moderation-ban"), in: app)
        expectAppears(app.element("ban-confirm"))
        expectAppears(app.row("ban-confirm", containing: "Ban for 1 hour"))
        expectAppears(app.element(containing: "Ends on"))
        expectAppears(app.element(containing: "Their IP address is always banned too"))
        tapRevealed(app.row("ban-duration", containing: "Permanent"), in: app)
        expectAppears(app.row("ban-confirm", containing: "Ban permanently"))
        expectGone(app.element(containing: "Ends on"))
        tapRevealed(app.row("ban-duration", containing: "7 days"), in: app)
        expectAppears(app.row("ban-confirm", containing: "Ban for 7 days"))

        tapRevealed(app.element("ban-confirm"), in: app)
        expectGone(app.element("ban-confirm"))
        expectGone(app.element("person-sheet"))
        expectGone(jonas(app))
    }

    /// The full ban sheet has less room once the keyboard is up; the field keeps the focus.
    func testAReasonCanBeTypedInTheBanSheet() {
        let app = openJonas()
        tapRevealed(app.element("moderation-ban"), in: app)
        expectAppears(app.element("ban-confirm"))
        typeText("Spam links\n", into: app.input("Reason"))
        XCTAssertEqual(app.input("Reason").value as? String, "Spam links")
        tapRevealed(app.element("ban-kick-instead"), in: app)
        expectAppears(app.element("Kick Jonas?"))
        XCTAssertEqual(app.input("Reason").value as? String, "Spam links")
    }

    func testABanWithoutRemovingLeavesThePersonOnTheServer() {
        let app = openJonas()
        tapRevealed(app.element("moderation-ban"), in: app)
        expectAppears(app.element("ban-confirm"))
        tapRevealed(app.element("ban-remove-now"), in: app)
        tapRevealed(app.element("ban-confirm"), in: app)
        expectGone(app.element("ban-confirm"))
        expectGone(app.element("person-sheet"))
        expectAppears(jonas(app))
    }

    func testAServerWithoutAReadableBanListOnlyGetsItsDefaultBan() {
        let app = openJonas("admin,ban-quick")
        tapRevealed(app.element("moderation-ban"), in: app)
        expectAppears(app.element("This server only allows its default ban length."))
        expectAppears(app.row("ban-confirm", containing: "Ban"))
        for absent in ["ban-duration", "ban-by-certificate", "ban-remove-now"] {
            XCTAssertFalse(app.element(absent).exists, absent)
        }
        tapRevealed(app.element("ban-confirm"), in: app)
        expectGone(app.element("ban-confirm"))
        expectGone(jonas(app))
    }

    func testAConflictAsksBeforeSavingAnyway() {
        let app = openJonas("admin,ban-conflict")
        tapRevealed(app.element("moderation-ban"), in: app)
        tapRevealed(app.element("ban-confirm"), in: app)
        expectAppears(app.element(containing: "Someone changed these bans meanwhile."))
        expectGone(app.element("ban-confirm"))
        tapRevealed(app.element("ban-anyway"), in: app)
        expectGone(app.element("ban-anyway"))
        expectGone(app.element("person-sheet"))
        expectGone(jonas(app))
    }
}
