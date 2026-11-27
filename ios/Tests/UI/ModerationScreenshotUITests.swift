// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Drives the app against the fake engine to the moderation states of the Android screenshot
/// tests, as server admin of the server of mockup `02-channels`, and keeps a picture of each in
/// both themes, as `ios-moderation-<name>-<theme>`. Each state has its own test method, so a
/// failure names the screen.
final class ModerationScreenshotUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func capture(_ app: XCUIApplication, _ name: String, theme: String) {
        // The sheets slide in and the keyboard slides out; the picture is taken once they are still.
        Thread.sleep(forTimeInterval: 0.5)
        keepScreenshot(of: app, named: "ios-moderation-\(name)-\(theme)")
    }

    /// Connected to Nordlicht with Jonas's sheet open.
    private func openJonas(_ options: String = "admin", theme: String) -> XCUIApplication {
        // The end of a ban is written as the phone writes dates; the pictures show the English way.
        let app = launchApp("servers," + options, theme: theme, locale: "en_US")
        tapWhenReady(app.element("Connect to Nordlicht"))
        tapWhenReady(app.row("person-row", containing: "Jonas"))
        expectAppears(app.element("person-sheet"))
        return app
    }

    private func open(_ entry: String, in app: XCUIApplication) {
        let element = app.element(entry)
        reveal(element, in: app)
        element.tapCenter()
    }

    // MARK: States

    private func personSheet(theme: String) {
        let app = openJonas(theme: theme)
        reveal(app.element("moderation-ban"), in: app)
        capture(app, "person-sheet", theme: theme)
    }

    private func move(theme: String) {
        let app = openJonas(theme: theme)
        open("moderation-move", in: app)
        expectAppears(app.element("move-choice"))
        capture(app, "move", theme: theme)
    }

    private func kick(theme: String) {
        let app = openJonas(theme: theme)
        open("moderation-kick", in: app)
        typeText("Cool down\n", into: app.input("Reason"))
        capture(app, "kick", theme: theme)
    }

    private func kickRefused(theme: String) {
        let app = openJonas("admin,moderation-refused", theme: theme)
        open("moderation-kick", in: app)
        open("kick-confirm", in: app)
        expectAppears(app.element("moderation-error"))
        capture(app, "kick-refused", theme: theme)
    }

    /// The ban form with a reason and one day chosen.
    private func fillBan(_ app: XCUIApplication) {
        open("moderation-ban", in: app)
        expectAppears(app.element("ban-confirm"))
        typeText("Spam links\n", into: app.input("Reason"))
    }

    private func ban(theme: String) {
        let app = openJonas(theme: theme)
        fillBan(app)
        app.row("ban-duration", containing: "1 day").tapCenter()
        expectAppears(app.row("ban-confirm", containing: "Ban for 1 day"))
        capture(app, "ban", theme: theme)
    }

    private func banQuick(theme: String) {
        let app = openJonas("admin,ban-quick", theme: theme)
        fillBan(app)
        capture(app, "ban-quick", theme: theme)
    }

    private func banConflict(theme: String) {
        let app = openJonas("admin,ban-conflict", theme: theme)
        fillBan(app)
        app.row("ban-duration", containing: "1 day").tapCenter()
        open("ban-confirm", in: app)
        expectAppears(app.element("ban-anyway"))
        // The question and its buttons are at the end of the sheet, which scrolls now. The
        // scroll indicator fades a moment after the swipe.
        app.swipeUp()
        Thread.sleep(forTimeInterval: 1.5)
        capture(app, "ban-conflict", theme: theme)
    }

    // MARK: Tests

    func testPersonSheetDark() { personSheet(theme: "dark") }
    func testPersonSheetLight() { personSheet(theme: "light") }
    func testMoveDark() { move(theme: "dark") }
    func testMoveLight() { move(theme: "light") }
    func testKickDark() { kick(theme: "dark") }
    func testKickLight() { kick(theme: "light") }
    func testKickRefusedDark() { kickRefused(theme: "dark") }
    func testKickRefusedLight() { kickRefused(theme: "light") }
    func testBanDark() { ban(theme: "dark") }
    func testBanLight() { ban(theme: "light") }
    func testBanQuickDark() { banQuick(theme: "dark") }
    func testBanQuickLight() { banQuick(theme: "light") }
    func testBanConflictDark() { banConflict(theme: "dark") }
    func testBanConflictLight() { banConflict(theme: "light") }
}
