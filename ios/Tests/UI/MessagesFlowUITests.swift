// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Walks through the Messages tab against the fake engine with the conversations of mockup
/// `09-messages`: the list, a conversation, requests, starting a conversation, chat requests,
/// blocking and the notification question.
final class MessagesFlowUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func connect(_ options: String) -> XCUIApplication {
        let app = launchApp("servers," + options)
        tapWhenReady(app.element("Connect to Nordlicht"))
        // The question about notifications covers the channels, which are then not offered
        // to the tests either.
        if options.contains("notif-ask") {
            expectAppears(app.element("notifications-continue"))
        } else {
            expectAppears(app.row("channel-row", containing: "Lobby"))
        }
        return app
    }

    /// The tab's label carries the unread count, so it is found by how it begins.
    private func tab(_ app: XCUIApplication, _ name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
    }

    private func openMessages(_ options: String = "messages") -> XCUIApplication {
        let app = connect(options)
        tapWhenReady(tab(app, "Messages"))
        expectAppears(app.element("messages-screen"))
        return app
    }

    func testTheListShowsConversationsAndOpensOne() {
        let app = openMessages()
        expectAppears(app.row("conversation-row", containing: "Anna"))
        expectAppears(app.row("conversation-row", containing: "2 unread messages"))
        expectAppears(app.row("conversation-row", containing: "Jonas"))
        tapWhenReady(app.row("conversation-row", containing: "Ben"))
        expectAppears(app.element("chat-screen"))
        expectAppears(app.row("chat-message", containing: "Thanks, got the map!"))
        tapWhenReady(app.element("Back to the conversations"))
        expectAppears(app.element("messages-screen"))
    }

    func testAMessageCanBeWrittenInAConversation() {
        let app = openMessages()
        tapWhenReady(app.row("conversation-row", containing: "Ben"))
        expectAppears(app.element("chat-screen"))
        let input = app.descendants(matching: .any).matching(identifier: "chat-input").firstMatch
        typeText("See you there", into: input)
        tapWhenReady(app.element("chat-send"))
        expectAppears(app.row("chat-message", containing: "See you there"))
    }

    func testARequestCanBeAcceptedOrDeclined() {
        var app = openMessages()
        tapWhenReady(app.element("messages-requests"))
        expectAppears(app.element("requests-screen"))
        tapWhenReady(app.element("request-accept"))
        // The last request was answered: the list is back, without the row of requests.
        expectAppears(app.element("messages-screen"))
        expectGone(app.element("messages-requests"))
        app.terminate()

        app = openMessages()
        tapWhenReady(app.element("messages-requests"))
        tapWhenReady(app.element("request-decline"))
        expectAppears(app.element("messages-screen"))
        expectGone(app.element("messages-requests"))
    }

    func testTheSearchFiltersByName() {
        let app = openMessages()
        tapWhenReady(app.element("Search conversations"))
        typeText("mi", into: app.input("messages-search"))
        expectAppears(app.row("conversation-row", containing: "Mira"))
        expectGone(app.row("conversation-row", containing: "Ben"))
    }

    func testThePickerStartsAConversation() {
        let app = openMessages()
        tapWhenReady(app.element("messages-new"))
        expectAppears(app.element("picker-screen"))
        tapWhenReady(app.row("picker-person", containing: "Ben"))
        expectAppears(app.element("chat-screen"))
    }

    func testThePersonSheetOpensAConversation() {
        let app = connect("messages")
        tapWhenReady(app.row("person-row", containing: "Anna"))
        tapWhenReady(app.element("person-message"))
        expectAppears(app.element("chat-screen"))
    }

    func testBlockingAsksFirstAndCanBeLifted() {
        let app = connect("messages")
        tapWhenReady(app.row("person-row", containing: "Anna"))
        tapWhenReady(app.element("person-block"))
        expectAppears(app.element("block-sheet"))
        tapWhenReady(app.element("block-confirm"))
        expectGone(app.element("block-sheet"))
        // The person sheet now offers to lift the block.
        tapWhenReady(app.row("person-row", containing: "Anna"))
        expectAppears(app.row("person-block", containing: "Unblock"))
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.1)).tap()
        expectGone(app.element("person-block"))
        tapWhenReady(tab(app, "More"))
        tapWhenReady(app.element("more-blocked"))
        expectAppears(app.element("blocked-screen"))
        tapWhenReady(app.element("blocked-unblock"))
        expectAppears(app.element("You have not blocked anybody."))
    }

    func testAChatRequestIsPendingAfterSending() {
        let app = connect("messages,chat-requests")
        tapWhenReady(app.row("person-row", containing: "Anna"))
        tapWhenReady(app.element("person-chat-request"))
        tapWhenReady(app.element("chat-request-send"))
        expectGone(app.element("chat-request-send"))
        tapWhenReady(app.row("person-row", containing: "Anna"))
        expectAppears(app.element("Chat request pending"))
    }

    func testTheNotificationQuestionIsPutOnce() {
        let app = connect("messages,notif-ask")
        expectAppears(app.element("notifications-continue"))
        tapWhenReady(app.element("notifications-not-now"))
        expectGone(app.element("notifications-continue"))
        tapWhenReady(tab(app, "Messages"))
        expectAppears(app.element("messages-notifications-off"))
    }

    func testAllowingNotificationsClosesTheQuestion() {
        let app = connect("messages,notif-ask")
        tapWhenReady(app.element("notifications-continue"))
        expectGone(app.element("notifications-continue"))
        tapWhenReady(tab(app, "Messages"))
        expectAppears(app.element("messages-screen"))
        XCTAssertFalse(app.element("messages-notifications-off").exists)
    }

    /// "Show sender only" is a setting under Notifications; the switch changes its value.
    func testShowSenderOnlyIsASettingAndChangesItsValue() {
        let app = connect("messages")
        tapWhenReady(tab(app, "More"))
        expectAppears(app.element("more-blocked"))
        XCTAssertFalse(app.element(containing: "Show sender only").exists)
        tapWhenReady(app.element("more-settings"))
        expectAppears(app.element("settings"))
        app.swipeUp()
        let senderOnly = app.switches.matching(identifier: "settings-sender-only").firstMatch
        expectAppears(senderOnly)
        XCTAssertEqual(senderOnly.value as? String, "0")
        senderOnly.tapCenter()
        expectAppears(app.switches.matching(NSPredicate(format: "identifier == %@ AND value == %@", "settings-sender-only", "1")).firstMatch)
        senderOnly.tapCenter()
        expectAppears(app.switches.matching(NSPredicate(format: "identifier == %@ AND value == %@", "settings-sender-only", "0")).firstMatch)
    }
}
