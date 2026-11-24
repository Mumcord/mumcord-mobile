// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Drives the app against the fake engine to the stream states of the Android screenshot tests,
/// on the server of mockup `02-channels` with Mira sharing her screen, and keeps a picture of
/// each in both themes, as `ios-streams-<name>-<theme>`. The picture of the stream is the
/// mockup's, made in the app and drawn through the real render path.
final class StreamsScreenshotUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func capture(_ app: XCUIApplication, _ name: String, theme: String) {
        // Sheets slide in and the first pictures arrive; the picture is taken once they are still.
        Thread.sleep(forTimeInterval: 1)
        keepScreenshot(of: app, named: "ios-streams-\(name)-\(theme)")
    }

    private func connect(_ options: String, theme: String) -> XCUIApplication {
        let app = launchApp("servers,streams," + options, theme: theme)
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.element("stream-tile"))
        return app
    }

    // MARK: States

    /// On Wi-Fi the tile plays by itself.
    private func tile(theme: String) {
        let app = connect("plain", theme: theme)
        capture(app, "tile", theme: theme)
    }

    private func fullScreen(theme: String) {
        let app = connect("stream-crowd", theme: theme)
        app.element("stream-tile").tapCenter()
        expectAppears(app.element(containing: "3 watching"))
        capture(app, "full-screen", theme: theme)
    }

    /// The mini window above the Lobby chat of the chat mockup.
    private func miniWindow(theme: String) {
        let app = connect("chat", theme: theme)
        app.element("stream-tile").tapCenter()
        tapWhenReady(app.element("stream-collapse"))
        expectAppears(app.element("stream-mini-window"))
        tapWhenReady(app.element("Chat"))
        expectAppears(app.element(containing: "Sure, give me five minutes."))
        capture(app, "mini-window", theme: theme)
    }

    // No mockup exists for the states below; they are built from the design system.

    private func tapToWatch(theme: String) {
        let app = connect("metered", theme: theme)
        expectAppears(app.row("stream-tile", containing: "Tap to watch"))
        capture(app, "tap-to-watch", theme: theme)
    }

    private func unreachable(theme: String) {
        let app = connect("metered,stream-unreachable", theme: theme)
        app.element("stream-tile").tapCenter()
        expectAppears(app.element("stream-retry"))
        capture(app, "unreachable", theme: theme)
    }

    private func directQuestion(theme: String) {
        let app = connect("metered,stream-question", theme: theme)
        app.element("stream-tile").tapCenter()
        expectAppears(app.element("direct-connections-yes"))
        capture(app, "direct-question", theme: theme)
    }

    // MARK: Dark and light

    func testTileDark() { tile(theme: "dark") }
    func testTileLight() { tile(theme: "light") }
    func testFullScreenDark() { fullScreen(theme: "dark") }
    func testFullScreenLight() { fullScreen(theme: "light") }
    func testMiniWindowDark() { miniWindow(theme: "dark") }
    func testMiniWindowLight() { miniWindow(theme: "light") }
    func testTapToWatchDark() { tapToWatch(theme: "dark") }
    func testTapToWatchLight() { tapToWatch(theme: "light") }
    func testUnreachableDark() { unreachable(theme: "dark") }
    func testUnreachableLight() { unreachable(theme: "light") }
    func testDirectQuestionDark() { directQuestion(theme: "dark") }
    func testDirectQuestionLight() { directQuestion(theme: "light") }
}
