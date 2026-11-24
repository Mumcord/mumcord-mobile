// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Watching Mira's screen share against the fake engine, whose pictures are made in the app
/// (`FakeVideoFrames`): with `stream-colours` they are red on the left half and blue on the
/// right, and they go through the real render path (Metal, the I420 shader), so the colours on
/// screen prove that the picture is drawn.
final class StreamsFlowUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// Connected to Nordlicht, Mira sharing her screen.
    private func connect(_ options: String) -> XCUIApplication {
        let app = launchApp("servers,streams," + options)
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.element("stream-tile"))
        return app
    }

    func testOnMobileDataATappedStreamPlaysFullScreenThenInTheMiniWindowAndStops() {
        let app = connect("metered,stream-question,stream-colours")
        let tile = app.element("stream-tile")
        expectAppears(app.row("stream-tile", containing: "Tap to watch"))
        // AC-5.23: nothing is fetched before the tap, so the tile keeps its placeholder.
        Thread.sleep(forTimeInterval: 1)
        XCTAssertFalse(showsColours(in: tile.frame), "a picture without a tap")

        tile.tapCenter()
        expectAppears(app.element("stream-full-screen"))
        expectAppears(app.element(containing: "can see your IP address"))
        tapWhenReady(app.element("direct-connections-yes"))
        expectGone(app.element("direct-connections-yes"))
        awaitColours(in: app.element("stream-full-screen"))

        // Collapsing shrinks it to the mini window, which keeps playing above the other screens.
        tapWhenReady(app.element("stream-collapse"))
        let mini = app.element("stream-mini-window")
        expectAppears(mini)
        expectGone(app.element("stream-full-screen"))
        awaitColours(in: mini)
        expectAppears(app.row("stream-tile", containing: "Playing in the mini window"))

        mini.tapCenter()
        expectAppears(app.element("stream-full-screen"))
        tapWhenReady(app.element("stream-collapse"))
        expectAppears(mini)

        tapWhenReady(app.element("stream-stop"))
        expectGone(mini)
        expectAppears(app.row("stream-tile", containing: "Tap to watch"))
    }

    func testOnWifiTheTileStartsByItself() {
        let app = connect("stream-colours")
        let tile = app.element("stream-tile")
        awaitColours(in: tile)
        XCTAssertFalse(app.row("stream-tile", containing: "Tap to watch").exists)
    }

    func testACameraWaitsForTheTapOnMobileData() {
        let app = connect("metered,stream-camera")
        expectAppears(app.row("stream-tile", containing: "Mira's camera, live, Tap to watch"))
        app.element("stream-tile").tapCenter()
        expectAppears(app.element("stream-full-screen"))
        // A camera sends no sound, so it has no volume.
        XCTAssertFalse(app.element("stream-volume").exists)
    }

    func testAStreamThatCannotBeReachedSaysWhyAndIsTriedAgain() {
        // Only the first watch fails, so the picture after the retry proves it was tried again.
        let app = connect("metered,stream-unreachable,stream-colours")
        app.element("stream-tile").tapCenter()
        let unreachable = app.element("Stream not reachable from this network")
        expectAppears(unreachable)
        tapWhenReady(app.element("stream-retry"))
        expectGone(unreachable)
        awaitColours(in: app.element("stream-full-screen"))
    }

    func testTheRotateButtonTurnsToLandscapeAndBack() {
        let app = connect("metered")
        app.element("stream-tile").tapCenter()
        tapWhenReady(app.element("Turn to landscape"))
        expectLandscape(app, true)
        tapWhenReady(app.element("Back to portrait"))
        expectLandscape(app, false)
        // The mini window is never landscape.
        tapWhenReady(app.element("Turn to landscape"))
        expectLandscape(app, true)
        tapWhenReady(app.element("stream-collapse"))
        expectAppears(app.element("stream-mini-window"))
        expectLandscape(app, false)
    }

    // MARK: Helpers

    private func expectLandscape(_ app: XCUIApplication, _ landscape: Bool, file: StaticString = #filePath, line: UInt = #line) {
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            let frame = app.windows.firstMatch.frame
            if (frame.width > frame.height) == landscape { return }
            Thread.sleep(forTimeInterval: 0.2)
        }
        XCTFail(landscape ? "not in landscape" : "not in portrait", file: file, line: line)
    }

    /// Waits until the picture in the element is red on the left and blue on the right.
    private func awaitColours(in element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        awaitStreamPicture(in: element, file: file, line: line, isRedAndBlue)
    }

    private func showsColours(in frame: CGRect) -> Bool {
        streamPictureShows(in: frame, isRedAndBlue)
    }
}

private func isRedAndBlue(_ left: ScreenColour, _ right: ScreenColour) -> Bool {
    left.r > 200 && left.b < 60 && right.b > 200 && right.r < 60
}
