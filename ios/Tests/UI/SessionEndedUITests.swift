// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// The notice at the start after the app was closed in the middle of a voice session
/// (FR-5.37), against the fake engine: it offers to reconnect and never does so by itself.
/// The pictures are kept as `ios-session-ended-notice-<theme>`, next to the Android ones.
final class SessionEndedUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func launchWithNotice(theme: String = "dark") -> XCUIApplication {
        let app = launchApp("servers,session-ended", theme: theme)
        expectAppears(app.element("Session ended"))
        expectAppears(app.element(containing: "Mumcord was closed by the system while you were connected to Nordlicht."))
        return app
    }

    private func capture(theme: String) {
        let app = launchWithNotice(theme: theme)
        // The sheet slides in and the servers behind it answer; they cannot be waited for, as
        // the screen behind a sheet is hidden from the tests.
        Thread.sleep(forTimeInterval: 1.5)
        keepScreenshot(of: app, named: "ios-session-ended-notice-\(theme)")
    }

    func testCloseLeavesTheServerListWithoutConnecting() {
        let app = launchWithNotice()
        tapWhenReady(app.element("session-ended-close"))
        expectGone(app.element("Session ended"))
        expectAppears(app.element("Connect to Nordlicht"))
        XCTAssertFalse(app.row("channel-row", containing: "Lobby").exists)
    }

    func testReconnectConnectsToTheServerOfTheSession() {
        let app = launchWithNotice()
        // Nothing was connected by itself.
        XCTAssertFalse(app.row("channel-row", containing: "Lobby").exists)
        tapWhenReady(app.element("session-ended-reconnect"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
        expectGone(app.element("Session ended"))
    }

    func testWithoutACutOffSessionThereIsNoNotice() {
        let app = launchApp("servers")
        expectAppears(app.element(containing: "5 online · 24 ms"))
        XCTAssertFalse(app.element("Session ended").exists)
    }

    func testNoticeDark() {
        capture(theme: "dark")
    }

    func testNoticeLight() {
        capture(theme: "light")
    }
}
