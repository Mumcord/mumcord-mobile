// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Walks through the Chat tab against the fake engine with the conversation of the mockup:
/// reading, writing, replying, mentions, pictures, GIF favourites and a misleading link.
final class ChatFlowUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// Connects to Nordlicht and opens the Chat tab.
    private func openChat(_ options: String, tab: String = "Chat") -> XCUIApplication {
        let app = launchApp("servers," + options)
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
        tapWhenReady(app.element(tab))
        expectAppears(app.element("chat-screen"))
        return app
    }

    private func message(_ app: XCUIApplication, _ text: String) -> XCUIElement {
        app.row("chat-message", containing: text)
    }

    /// The field of the input bar, whatever type the system reports it as.
    private func input(_ app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: "chat-input").firstMatch
    }

    func testTheChatShowsTheConversationOfTheChannel() {
        let app = openChat("chat")
        expectAppears(app.element(containing: "# Lobby"))
        expectAppears(message(app, "Evening all! Ready for tonight's session?"))
        expectAppears(message(app, "Always. Here is the map we picked:"))
        expectAppears(message(app, "Rules are here: nordlicht.example/rules"))
        expectAppears(message(app, "@Lea can you host the first round?"))
        expectAppears(message(app, "Sure, give me five minutes."))
        // A picture is part of its message for a screen reader.
        expectAppears(message(app, "Always. Here is the map we picked:, Image"))
        expectAppears(app.row("chat-hint", containing: "Jonas shared a file"))
        expectAppears(app.element("Today"))
        // The header has neither a search nor an info button.
        XCTAssertFalse(app.element("Search").exists)
    }

    func testASentMessageAppearsAndTextThatLooksLikeMarkupStaysText() {
        let app = openChat("chat")
        expectAppears(message(app, "Sure, give me five minutes."))
        typeText("**bold** [a](http://b)", into: input(app))
        tapWhenReady(app.element("chat-send"))
        // The stars and brackets are still there: nothing was read as formatting or as a link.
        expectAppears(message(app, "**bold**"))
        expectAppears(message(app, "[a]("))
        keepScreenshot(of: app, named: "ios-chat-literal-text-dark")
    }

    func testHoldingAMessageStartsAReplyWhichCanBeGivenUp() {
        let app = openChat("chat")
        let anna = message(app, "Evening all! Ready for tonight's session?")
        expectAppears(anna)
        // The lower half of the row is the line of text.
        anna.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75)).press(forDuration: 1.2)
        expectAppears(app.element(containing: "Replying to Anna"))
        tapWhenReady(app.element("Cancel reply"))
        expectGone(app.element(containing: "Replying to Anna"))
    }

    func testTypingAnAtSignOffersNamesAndATapCompletesTheMention() {
        let app = openChat("chat")
        expectAppears(message(app, "Sure, give me five minutes."))
        typeText("Hi @", into: input(app))
        let anna = app.row("chat-mention", containing: "@Anna")
        tapWhenReady(anna)
        expectGone(anna)
        let completed = NSPredicate(format: "value CONTAINS %@", "@Anna")
        let done = XCTNSPredicateExpectation(predicate: completed, object: input(app))
        XCTAssertEqual(XCTWaiter().wait(for: [done], timeout: 10), .completed)
    }

    func testAGifFavouriteIsSentWithATap() {
        let app = openChat("chat")
        tapWhenReady(app.element("chat-add-gif"))
        expectAppears(app.element("Send GIF 6"))
        tapWhenReady(app.element("Send GIF 1"))
        expectGone(app.element("Send GIF 6"))
        expectAppears(message(app, "gifs.example/0.gif"))
    }

    func testAddingAnImageOffersThePhotoLibraryAndTheCamera() {
        let app = openChat("chat")
        tapWhenReady(app.element("chat-add-image"))
        expectAppears(app.element("image-source-library"))
        // The simulator has no camera: the app says so instead of opening one.
        tapWhenReady(app.element("image-source-camera"))
        expectGone(app.element("image-source-library"))
        expectAppears(app.element(containing: "That picture could not be used."))
    }

    func testAPictureOpensLargeAndCloses() {
        let app = openChat("chat")
        let ben = message(app, "Always. Here is the map we picked:")
        expectAppears(ben)
        // The picture is the lower part of the row, under the name and the line of text.
        ben.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0.7)).withOffset(CGVector(dx: 150, dy: 0)).tap()
        expectAppears(app.element("Close image"))
        tapWhenReady(app.element("Close image"))
        expectGone(app.element("Close image"))
    }

    func testALinkThatNamesAnotherAddressShowsWhereItReallyGoesFirst() {
        let app = openChat("chat,chat-misleading")
        let mira = message(app, "Rules: nordlicht.example")
        expectAppears(mira)
        // The real host stands next to the link already.
        XCTAssertTrue(mira.label.contains("nordlicht.evil.example"))
        // The link is a few words inside the row; a tap beside it does nothing, so the test
        // tries the places the link can be at until the warning opens.
        let warning = app.element("link-warning")
        let frame = mira.frame
        search: for y in [0.75, 0.5, 0.6, 0.9] {
            for x in [130.0, 170.0, 210.0, 100.0] {
                let point = mira.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
                    .withOffset(CGVector(dx: x, dy: frame.height * y))
                point.tap()
                if warning.waitForExistence(timeout: 1.5) { break search }
            }
        }
        expectAppears(warning)
        XCTAssertTrue(warning.label.contains("https://nordlicht.evil.example/rules"))
        tapWhenReady(app.element("Cancel"))
        expectGone(warning)
        // Nothing was opened: the app is still in front.
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testUnreadMessagesShowOnTheTabUntilTheChatIsRead() {
        let app = openChat("chat,chat-unread", tab: "Chat, 2 unread messages")
        expectAppears(message(app, "Sure, give me five minutes."))
        tapWhenReady(app.element("Channels"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
        expectAppears(app.element("Chat"))
        XCTAssertFalse(app.element("Chat, 2 unread messages").exists)
    }
}
