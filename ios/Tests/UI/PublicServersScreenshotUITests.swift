// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Drives the app against the fake engine to the states of the Android screenshot test of the
/// public server browser and keeps a picture of each in both themes, as
/// `ios-public-servers-<state>-<theme>`. No mockup exists for the browser: by the product
/// manager's decision it follows the Android screen, and these pictures are its review. Lantern
/// Guild is in the server list, so it offers Connect instead of Add.
final class PublicServersScreenshotUITests: XCTestCase {
    private let search = "Search by name, address or country"

    override func setUp() {
        continueAfterFailure = false
    }

    private func capture(_ app: XCUIApplication, _ name: String, theme: String) {
        awaitStillScreen()
        keepScreenshot(of: app, named: "ios-public-servers-\(name)-\(theme)")
    }

    private func openBrowser(_ options: String, theme: String) -> XCUIApplication {
        let app = launchApp(options, theme: theme)
        let entry = app.element("server-browse-public")
        reveal(entry, in: app)
        entry.tapCenter()
        expectAppears(app.element("public-servers"))
        return app
    }

    /// Every row on screen has its ping answer; Tidepool, the last, does not answer.
    private func awaitPings(_ app: XCUIApplication) {
        expectAppears(app.element(containing: "12 of 50 people online, 34 ms"))
        expectAppears(app.element(containing: "0 of 25 people online, 63 ms"))
        expectAppears(app.element(containingAll: ["voice.tidepool.example", "No answer"]))
    }

    // MARK: States

    private func list(theme: String) {
        let app = openBrowser("public,public-lantern", theme: theme)
        awaitPings(app)
        capture(app, "list", theme: theme)
    }

    /// "voice" in Sweden: Nordic Friends.
    private func filtered(theme: String) {
        let app = openBrowser("public,public-lantern", theme: theme)
        awaitPings(app)
        // Sweden is further along the row of countries than the screen is wide.
        let canada = app.buttons["Canada"]
        expectAppears(canada)
        canada.swipeLeft()
        tapWhenReady(app.buttons["Sweden"])
        typeText("voice", into: app.input(search))
        dismissKeyboard(in: app)
        expectAppears(app.element(containing: "1 OF 6 SERVERS"))
        expectAppears(app.element(containing: "Nordic Friends"))
        capture(app, "filtered", theme: theme)
    }

    private func loading(theme: String) {
        let app = openBrowser("public,public-slow", theme: theme)
        expectAppears(app.element(containing: "Loading the list…"))
        capture(app, "loading", theme: theme)
    }

    private func failed(theme: String) {
        let app = openBrowser("public,public-down", theme: theme)
        expectAppears(app.element("public-servers-retry"))
        capture(app, "failed", theme: theme)
    }

    private func refreshFailed(theme: String) {
        let app = openBrowser("public,public-lantern,public-refresh-fails", theme: theme)
        awaitPings(app)
        tapWhenReady(app.buttons["Refresh"])
        expectAppears(app.element("public-servers-refresh-failed"))
        awaitPings(app)
        capture(app, "refresh-failed", theme: theme)
    }

    private func noMatch(theme: String) {
        let app = openBrowser("public,public-lantern", theme: theme)
        typeText("zeppelin", into: app.input(search))
        dismissKeyboard(in: app)
        expectAppears(app.element(containing: "No server matches your search and filter."))
        capture(app, "no-match", theme: theme)
    }

    func testListDark() { list(theme: "dark") }
    func testListLight() { list(theme: "light") }
    func testFilteredDark() { filtered(theme: "dark") }
    func testFilteredLight() { filtered(theme: "light") }
    func testLoadingDark() { loading(theme: "dark") }
    func testLoadingLight() { loading(theme: "light") }
    func testFailedDark() { failed(theme: "dark") }
    func testFailedLight() { failed(theme: "light") }
    func testRefreshFailedDark() { refreshFailed(theme: "dark") }
    func testRefreshFailedLight() { refreshFailed(theme: "light") }
    func testNoMatchDark() { noMatch(theme: "dark") }
    func testNoMatchLight() { noMatch(theme: "light") }
}
