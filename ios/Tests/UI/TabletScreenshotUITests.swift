// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Drives the app on an iPad simulator against the fake engine to the two-column states of the
/// Android tablet screenshots and keeps a picture of each in both themes, as
/// `ios-tablet-<name>-<orientation>-<theme>`: channels and chat of mockup
/// `21-tablet-channels-chat` and the stream in the right column of `22-tablet-stream`, in
/// landscape (the mockups' 1180 x 820 points) and in portrait. The stream's picture is the
/// mockup's, made in the app and drawn through the real render path.
final class TabletScreenshotUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
        try requireIPad()
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
        super.tearDown()
    }

    private func connect(_ options: String, theme: String, landscape: Bool) -> XCUIApplication {
        XCUIDevice.shared.orientation = landscape ? .landscapeLeft : .portrait
        let app = launchApp("servers,messages," + options, theme: theme, locale: "en_US")
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.element("navigation-rail"))
        return app
    }

    private func capture(_ name: String) {
        // Sheets and bars slide in; the picture is taken once they are still. (A playing stream
        // never holds still, so this waits a fixed moment, as the stream screenshots do.)
        Thread.sleep(forTimeInterval: 1)
        keepUprightScreenshot(named: "ios-tablet-\(name)")
    }

    // MARK: States

    private func channelsAndChat(theme: String, landscape: Bool) {
        let app = connect("tablet-chat", theme: theme, landscape: landscape)
        expectAppears(app.element(containing: "Sure, give me two minutes."))
        capture("channels-chat-\(orientation(landscape))-\(theme)")
    }

    private func stream(theme: String, landscape: Bool) {
        let app = connect("streams,tablet-stream-chat", theme: theme, landscape: landscape)
        tapWhenReady(app.element("stream-tile"))
        expectAppears(app.element(containing: "Nice, the stream is crisp"))
        awaitStreamPicture(in: app.element("stream-stage"), isMockupPicture)
        capture("stream-\(orientation(landscape))-\(theme)")
    }

    // No mockup: the stream collapsed to the mini window over the chat in the right column.
    private func miniWindow(theme: String) {
        let app = connect("streams,tablet-chat", theme: theme, landscape: true)
        tapWhenReady(app.element("stream-tile"))
        tapWhenReady(app.element("stream-collapse"))
        expectAppears(app.element(containing: "Sure, give me two minutes."))
        awaitStreamPicture(in: app.element("stream-mini-window"), isMockupPicture)
        capture("mini-window-\(theme)")
    }

    // No mockup: a sheet keeps the width of the tablet mockups' sheets in the middle.
    private func personSheet(theme: String) {
        let app = connect("tablet-chat", theme: theme, landscape: true)
        app.row("person-row", containing: "Anna").tapCenter()
        expectAppears(app.element("person-block"))
        capture("person-sheet-\(theme)")
    }

    private func orientation(_ landscape: Bool) -> String {
        landscape ? "landscape" : "portrait"
    }

    // MARK: Dark and light

    func testChannelsAndChatLandscapeDark() { channelsAndChat(theme: "dark", landscape: true) }
    func testChannelsAndChatLandscapeLight() { channelsAndChat(theme: "light", landscape: true) }
    func testChannelsAndChatPortraitDark() { channelsAndChat(theme: "dark", landscape: false) }
    func testChannelsAndChatPortraitLight() { channelsAndChat(theme: "light", landscape: false) }
    func testStreamLandscapeDark() { stream(theme: "dark", landscape: true) }
    func testStreamLandscapeLight() { stream(theme: "light", landscape: true) }
    func testStreamPortraitDark() { stream(theme: "dark", landscape: false) }
    func testStreamPortraitLight() { stream(theme: "light", landscape: false) }
    func testMiniWindowDark() { miniWindow(theme: "dark") }
    func testMiniWindowLight() { miniWindow(theme: "light") }
    func testPersonSheetDark() { personSheet(theme: "dark") }
    func testPersonSheetLight() { personSheet(theme: "light") }
}

/// The mockup picture (`FakeVideoFrames`): the dark code window on the left, the game's light
/// blue sky on the right.
private func isMockupPicture(_ left: ScreenColour, _ right: ScreenColour) -> Bool {
    left.r < 60 && left.g < 60 && left.b < 90 && right.b > 180 && right.g > 130 && right.r < 150
}
