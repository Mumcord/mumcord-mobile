// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Drives the app against the fake engine to the states of the Android chat screenshot test
/// and keeps a picture of each in both themes, as `ios-<name>-<theme>`. CI publishes them next
/// to the Android ones, so the two platforms can be compared. Each state has its own test
/// method, so a failure names the screen.
final class ChatScreenshotUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func capture(_ app: XCUIApplication, _ name: String, theme: String) {
        // The sheets slide in and the pictures are decoded; the picture is taken once they are still.
        Thread.sleep(forTimeInterval: 1)
        keepScreenshot(of: app, named: "ios-\(name)-\(theme)")
    }

    /// Connects to Nordlicht and opens the Chat tab.
    private func openChat(_ options: String, theme: String) -> XCUIApplication {
        let app = launchApp("servers," + options, theme: theme)
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
        tapWhenReady(app.element("Chat"))
        expectAppears(app.element("chat-screen"))
        return app
    }

    // MARK: States

    private func chat(theme: String) {
        let app = openChat("chat", theme: theme)
        expectAppears(app.row("chat-message", containing: "Sure, give me five minutes."))
        capture(app, "chat", theme: theme)
    }

    private func reply(theme: String) {
        let app = openChat("chat,chat-reply", theme: theme)
        expectAppears(app.element(containing: "Replying to Anna"))
        capture(app, "chat-reply", theme: theme)
    }

    private func mentionAutocomplete(theme: String) {
        let app = openChat("chat,chat-mention", theme: theme)
        expectAppears(app.row("chat-mention", containing: "@Jonas"))
        capture(app, "chat-mention-autocomplete", theme: theme)
    }

    private func gifFavourites(theme: String) {
        let app = openChat("chat,chat-gifs", theme: theme)
        expectAppears(app.element("Send GIF 6"))
        capture(app, "gif-favourites", theme: theme)
    }

    private func linkWarning(theme: String) {
        let app = openChat("chat,chat-link-warning", theme: theme)
        expectAppears(app.element("link-warning"))
        capture(app, "chat-link-warning", theme: theme)
    }

    private func imageViewer(theme: String) {
        let app = openChat("chat,chat-viewer", theme: theme)
        expectAppears(app.element("Close image"))
        capture(app, "chat-image-viewer", theme: theme)
    }

    private func empty(theme: String) {
        let app = openChat("plain", theme: theme)
        expectAppears(app.element("chat-empty"))
        capture(app, "chat-empty", theme: theme)
    }

    // MARK: Tests, one per state and theme

    func testChatDark() {
        chat(theme: "dark")
    }

    func testChatLight() {
        chat(theme: "light")
    }

    func testReplyDark() {
        reply(theme: "dark")
    }

    func testReplyLight() {
        reply(theme: "light")
    }

    func testMentionAutocompleteDark() {
        mentionAutocomplete(theme: "dark")
    }

    func testMentionAutocompleteLight() {
        mentionAutocomplete(theme: "light")
    }

    func testGifFavouritesDark() {
        gifFavourites(theme: "dark")
    }

    func testGifFavouritesLight() {
        gifFavourites(theme: "light")
    }

    func testLinkWarningDark() {
        linkWarning(theme: "dark")
    }

    func testLinkWarningLight() {
        linkWarning(theme: "light")
    }

    func testImageViewerDark() {
        imageViewer(theme: "dark")
    }

    func testImageViewerLight() {
        imageViewer(theme: "light")
    }

    func testEmptyDark() {
        empty(theme: "dark")
    }

    func testEmptyLight() {
        empty(theme: "light")
    }
}
