// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Drives the app against the fake engine to the states of the Android messages screenshot
/// test and keeps a picture of each in both themes, as `ios-messages-<name>-<theme>`. Each
/// state has its own test method, so a failure names the screen.
final class MessagesScreenshotUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func capture(_ app: XCUIApplication, _ name: String, theme: String) {
        // The sheets slide in; the picture is taken once they are still.
        Thread.sleep(forTimeInterval: 1)
        keepScreenshot(of: app, named: "ios-messages-\(name)-\(theme)")
    }

    private func connect(_ options: String, theme: String) -> XCUIApplication {
        let app = launchApp(options.isEmpty ? "servers" : "servers," + options, theme: theme)
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
        return app
    }

    /// The tab's label carries the unread count, so it is found by how it begins.
    private func tab(_ app: XCUIApplication, _ name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
    }

    // MARK: States

    private func list(theme: String) {
        let app = connect("messages", theme: theme)
        tapWhenReady(tab(app, "Messages"))
        expectAppears(app.element("messages-screen"))
        capture(app, "list", theme: theme)
    }

    private func empty(theme: String) {
        let app = connect("", theme: theme)
        tapWhenReady(tab(app, "Messages"))
        expectAppears(app.element("messages-empty"))
        capture(app, "empty", theme: theme)
    }

    private func search(theme: String) {
        let app = connect("messages", theme: theme)
        tapWhenReady(tab(app, "Messages"))
        tapWhenReady(app.element("Search conversations"))
        expectAppears(app.element("messages-search"))
        capture(app, "search", theme: theme)
    }

    private func notificationsOff(theme: String) {
        let app = connect("messages,notif-denied", theme: theme)
        tapWhenReady(tab(app, "Messages"))
        expectAppears(app.element("messages-notifications-off"))
        capture(app, "notifications-off", theme: theme)
    }

    private func requests(theme: String) {
        let app = connect("messages", theme: theme)
        tapWhenReady(tab(app, "Messages"))
        tapWhenReady(app.element("messages-requests"))
        expectAppears(app.element("requests-screen"))
        capture(app, "requests", theme: theme)
    }

    private func picker(theme: String) {
        let app = connect("messages", theme: theme)
        tapWhenReady(tab(app, "Messages"))
        tapWhenReady(app.element("messages-new"))
        expectAppears(app.element("picker-screen"))
        capture(app, "picker", theme: theme)
    }

    private func conversation(theme: String) {
        let app = connect("messages", theme: theme)
        tapWhenReady(tab(app, "Messages"))
        tapWhenReady(app.row("conversation-row", containing: "Ben"))
        expectAppears(app.element("chat-screen"))
        capture(app, "conversation", theme: theme)
    }

    private func blockConfirm(theme: String) {
        let app = connect("messages", theme: theme)
        tapWhenReady(app.row("person-row", containing: "Anna"))
        tapWhenReady(app.element("person-block"))
        expectAppears(app.element("block-sheet"))
        capture(app, "block-confirm", theme: theme)
    }

    private func chatRequest(theme: String) {
        let app = connect("messages,chat-requests", theme: theme)
        tapWhenReady(app.row("person-row", containing: "Anna"))
        tapWhenReady(app.element("person-chat-request"))
        expectAppears(app.element("chat-request-send"))
        capture(app, "chat-request", theme: theme)
    }

    private func blockedPeople(theme: String) {
        let app = connect("messages", theme: theme)
        tapWhenReady(app.row("person-row", containing: "Anna"))
        tapWhenReady(app.element("person-block"))
        tapWhenReady(app.element("block-confirm"))
        tapWhenReady(tab(app, "More"))
        tapWhenReady(app.element("more-blocked"))
        expectAppears(app.element("blocked-screen"))
        capture(app, "blocked-people", theme: theme)
    }

    private func personSheet(theme: String) {
        let app = connect("messages,chat-requests", theme: theme)
        tapWhenReady(app.row("person-row", containing: "Anna"))
        expectAppears(app.element("person-block"))
        capture(app, "person-sheet", theme: theme)
    }

    private func more(theme: String) {
        let app = connect("messages", theme: theme)
        tapWhenReady(tab(app, "More"))
        expectAppears(app.element("more-sender-only"))
        capture(app, "more", theme: theme)
    }

    private func notificationsExplain(theme: String) {
        let app = connect("messages,notif-ask", theme: theme)
        expectAppears(app.element("notifications-continue"))
        capture(app, "notifications-explain", theme: theme)
    }

    // MARK: Tests

    func testListDark() {
        list(theme: "dark")
    }

    func testListLight() {
        list(theme: "light")
    }

    func testEmptyDark() {
        empty(theme: "dark")
    }

    func testEmptyLight() {
        empty(theme: "light")
    }

    func testSearchDark() {
        search(theme: "dark")
    }

    func testSearchLight() {
        search(theme: "light")
    }

    func testNotificationsOffDark() {
        notificationsOff(theme: "dark")
    }

    func testNotificationsOffLight() {
        notificationsOff(theme: "light")
    }

    func testRequestsDark() {
        requests(theme: "dark")
    }

    func testRequestsLight() {
        requests(theme: "light")
    }

    func testPickerDark() {
        picker(theme: "dark")
    }

    func testPickerLight() {
        picker(theme: "light")
    }

    func testConversationDark() {
        conversation(theme: "dark")
    }

    func testConversationLight() {
        conversation(theme: "light")
    }

    func testBlockConfirmDark() {
        blockConfirm(theme: "dark")
    }

    func testBlockConfirmLight() {
        blockConfirm(theme: "light")
    }

    func testChatRequestDark() {
        chatRequest(theme: "dark")
    }

    func testChatRequestLight() {
        chatRequest(theme: "light")
    }

    func testBlockedPeopleDark() {
        blockedPeople(theme: "dark")
    }

    func testBlockedPeopleLight() {
        blockedPeople(theme: "light")
    }

    func testPersonSheetDark() {
        personSheet(theme: "dark")
    }

    func testPersonSheetLight() {
        personSheet(theme: "light")
    }

    func testMoreDark() {
        more(theme: "dark")
    }

    func testMoreLight() {
        more(theme: "light")
    }

    func testNotificationsExplainDark() {
        notificationsExplain(theme: "dark")
    }

    func testNotificationsExplainLight() {
        notificationsExplain(theme: "light")
    }
}
