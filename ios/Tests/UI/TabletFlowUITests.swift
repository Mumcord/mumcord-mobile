// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// The two-column iPad layout (AC-5.31) against the fake engine, on an iPad simulator: channels
/// and chat side by side in landscape and portrait, a stream in the right column with the chat
/// strip below, the whole screen and back, the mini window over Messages, sheets of the tablet's
/// width, and a window narrow as a Split View half, which is laid out like a phone. Turning the
/// iPad changes the window's size while the app runs; what the user did must survive it.
final class TabletFlowUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
        try requireIPad()
        XCUIDevice.shared.orientation = .landscapeLeft
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
        super.tearDown()
    }

    /// Connected to Nordlicht in two columns, Mira sharing her screen.
    private func connect(_ options: String, splitWidth: Int? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-mumcordFakeEngine", "servers,streams," + options, "-mumcordSetupDone", "YES", "-mumcordTheme", "dark"]
            + languageArguments()
            + (splitWidth.map { ["-mumcordSplitWidth", String($0)] } ?? [])
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 120), "the app did not come on screen")
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.element("stream-tile"))
        return app
    }

    func testChannelsAndChatSideBySideAndAStreamFillsTheRightColumnWithTheChatStripBelow() {
        let app = connect("chat")
        // Left: the rail and the channel tree with the voice bar; right: the channel chat.
        expectAppears(app.element("navigation-rail"))
        expectAppears(app.element("side-column"))
        expectAppears(app.element("chat-screen"))
        XCTAssertTrue(app.element("voice-bar").exists)
        XCTAssertFalse(app.buttons["Chat"].exists, "no bottom tab bar in two columns")
        XCTAssertTrue(isInside(app.element("voice-bar"), app.element("side-column")), "the voice bar is under the channels")
        typeIntoChat("Draft from the tablet", app)

        app.element("stream-tile").tapCenter()
        expectAppears(app.element("stream-column"))
        expectAppears(app.element("stream-chat-strip"))
        XCTAssertFalse(app.element("stream-full-screen").exists, "the stream covers only the right column")
        XCTAssertFalse(app.element("stream-tile").exists, "its tile left the tree")
        XCTAssertTrue(app.element("navigation-rail").exists && app.element("side-column").exists)
        expectAppears(draft("Draft from the tablet", in: app))

        // The full screen button takes the whole screen; leaving it returns to the column.
        tapWhenReady(app.element("stream-fill-screen"))
        expectAppears(app.element("stream-full-screen"))
        XCTAssertFalse(app.element("navigation-rail").exists)
        XCTAssertFalse(app.element("Turn to landscape").exists, "an iPad turns by itself")
        tapWhenReady(app.element("stream-leave-full-screen"))
        expectAppears(app.element("stream-column"))
        expectAppears(app.element("navigation-rail"))

        // Portrait keeps both columns and the stream in the right one.
        XCUIDevice.shared.orientation = .portrait
        expectPortrait(app, true)
        expectAppears(app.element("stream-column"))
        expectAppears(app.element("side-column"))

        // The mini window button leaves the whole chat with the stream floating above it.
        tapWhenReady(app.element("stream-collapse"))
        expectAppears(app.element("stream-mini-window"))
        expectAppears(app.element("chat-screen"))
        tapWhenReady(app.element("stream-expand"))
        expectAppears(app.element("stream-column"))
    }

    func testMessagesTakesTheRightColumnWithTheStreamInTheMiniWindowAndSheetsKeepTheTabletWidth() {
        let app = connect("messages")
        app.element("stream-tile").tapCenter()
        expectAppears(app.element("stream-column"))

        // Messages takes the right column; the channels stay on the left.
        tapWhenReady(app.element(containing: "Messages"))
        expectAppears(app.element("messages-screen"))
        XCTAssertTrue(app.element("side-column").exists)
        expectAppears(app.element("stream-mini-window"))
        XCTAssertGreaterThanOrEqual(
            app.element("stream-mini-window").frame.minX,
            app.element("side-column").frame.maxX,
            "the mini window floats over the right column"
        )

        // Its expand button goes back to Channels, where the stream has its place.
        tapWhenReady(app.element("stream-expand"))
        expectAppears(app.element("stream-column"))
        expectGone(app.element("messages-screen"))

        // A sheet is at most 480 points wide and stands in the middle of the window.
        app.row("person-row", containing: "Anna").tapCenter()
        let block = app.element("person-block")
        expectAppears(block)
        let window = app.windows.firstMatch.frame
        XCTAssertLessThanOrEqual(block.frame.width, 480)
        XCTAssertEqual(block.frame.midX, window.midX, accuracy: 2)
    }

    func testANarrowWindowIsLaidOutLikeAPhoneAndTurningKeepsTheTabTheDraftAndTheStream() {
        // Portrait gets a window as narrow as half of a Split View: the phone layout.
        XCUIDevice.shared.orientation = .portrait
        let app = connect("chat", splitWidth: 507)
        expectAppears(app.buttons["Chat"])
        XCTAssertFalse(app.element("navigation-rail").exists)
        app.element("stream-tile").tapCenter()
        expectAppears(app.element("stream-full-screen"))
        XCTAssertFalse(app.element("Turn to landscape").exists, "iOS does not turn an iPad for an app")
        tapWhenReady(app.element("stream-collapse"))
        expectAppears(app.element("stream-mini-window"))
        tapWhenReady(app.buttons["Chat"])
        typeIntoChat("Kept across sizes", app)

        // The whole screen in landscape: two columns, the Chat tab is part of Channels and the
        // stream still floats over the chat.
        XCUIDevice.shared.orientation = .landscapeLeft
        expectAppears(app.element("navigation-rail"))
        expectAppears(app.element("chat-screen"))
        expectAppears(app.element("stream-mini-window"))
        expectAppears(draft("Kept across sizes", in: app))
        XCTAssertTrue(app.row("channel-row", containing: "Lobby").exists, "the channels beside the chat")

        // Narrow again: the Chat tab as it was.
        XCUIDevice.shared.orientation = .portrait
        expectAppears(app.buttons["Chat"])
        expectGone(app.element("navigation-rail"))
        expectAppears(draft("Kept across sizes", in: app))
        XCTAssertFalse(app.element("channel-row").exists, "the Chat tab, not Channels")
    }

    // MARK: Helpers

    /// Types into the chat's input and puts the iPad's keyboard away with its own key; its
    /// return key would start a new line. The field is tapped again while it has no focus yet:
    /// right after a tab changes, the first tap can come too early.
    private func typeIntoChat(_ text: String, _ app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let field = app.element("chat-input")
        expectAppears(field, file: file, line: line)
        for _ in 0..<3 {
            field.tapCenter()
            if waitForFocus(field) { break }
        }
        field.typeText(text)
        let hide = app.keyboards.buttons["Hide keyboard"]
        if hide.exists {
            hide.tap()
            _ = app.keyboards.firstMatch.waitForNonExistence(timeout: 3)
        }
    }

    private func waitForFocus(_ field: XCUIElement) -> Bool {
        let deadline = Date().addingTimeInterval(2)
        while Date() < deadline {
            if (field.value(forKey: "hasKeyboardFocus") as? Bool) == true { return true }
            Thread.sleep(forTimeInterval: 0.1)
        }
        return false
    }

    /// The chat's input field holding the text.
    private func draft(_ text: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == %@ AND value CONTAINS %@", "chat-input", text))
            .firstMatch
    }

    private func isInside(_ element: XCUIElement, _ container: XCUIElement) -> Bool {
        container.frame.contains(element.frame)
    }

    private func expectPortrait(_ app: XCUIApplication, _ portrait: Bool, file: StaticString = #filePath, line: UInt = #line) {
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            let frame = app.windows.firstMatch.frame
            if (frame.height > frame.width) == portrait { return }
            Thread.sleep(forTimeInterval: 0.2)
        }
        XCTFail(portrait ? "not in portrait" : "not in landscape", file: file, line: line)
    }
}

extension XCTestCase {
    /// The tablet tests need an iPad; on an iPhone simulator they are skipped.
    func requireIPad() throws {
        if UIDevice.current.userInterfaceIdiom != .pad {
            throw XCTSkip("needs an iPad simulator (scripts/build-ios.sh runs the tablet group on one)")
        }
    }
}
