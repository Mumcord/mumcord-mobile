// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// The screens Android shows at 200 % (`android-large-text-*`) at the largest text size the app
/// follows (235 %, `largestTextSize`), plus the settings, in both themes, as
/// `ios-large-text-<name>-<theme>`. Looked at for clipped and overlapping text (AC-5.33).
final class LargeTextScreenshotUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    // MARK: Helpers

    private func launch(_ options: String, theme: String) -> XCUIApplication {
        launchApp(options, theme: theme, locale: "en_US", textSize: AccessibilityUITests.largestTextSize)
    }

    private func capture(_ app: XCUIApplication, _ name: String, theme: String) {
        awaitStillScreen()
        keepScreenshot(of: app, named: "ios-large-text-\(name)-\(theme)")
    }

    private func connect(_ options: String, theme: String) -> XCUIApplication {
        let app = launch("servers," + options, theme: theme)
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.row("channel-row", containing: "Lobby"), timeout: 20)
        expectAppears(app.row("person-row", containing: "Jonas"))
        return app
    }

    // MARK: States

    private func serverList(theme: String) {
        let app = launch("servers", theme: theme)
        expectAppears(app.element(containing: "5 online · 24 ms"))
        capture(app, "server-list", theme: theme)
    }

    /// An invalid port and the errors after a try to save, as on Android.
    private func serverForm(theme: String) {
        let app = launch("plain", theme: theme)
        tapWhenReady(app.element("server-add"))
        replaceText(in: app.input("Port"), with: "99999")
        typeText("Nordlicht", into: app.input("Name"))
        typeText("nordlicht.example", into: app.input("Address"))
        dismissKeyboard(in: app)
        let save = app.element("server-form-save")
        reveal(save, in: app)
        save.tapCenter()
        expectAppears(app.element(containing: "Enter a number between 1 and 65535."))
        // The form's keyboard has no key that only closes it; dragging the form down into the
        // keyboard does, and more drags bring the first field back to the top.
        let top = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3))
        let bottom = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.98))
        top.press(forDuration: 0.1, thenDragTo: bottom)
        app.swipeDown()
        app.swipeDown()
        capture(app, "server-form", theme: theme)
    }

    private func voiceBar(theme: String) {
        let app = connect("voice-server,ptt", theme: theme)
        expectAppears(app.element("voice-bar"))
        capture(app, "channels-voice-bar", theme: theme)
    }

    private func pushToTalk(theme: String) {
        let app = connect("voice-server,ptt", theme: theme)
        openVoiceViewThroughTheBar(in: app)
        expectAppears(app.element("voice-hold"))
        capture(app, "voice-view-ptt", theme: theme)
    }

    private func chat(theme: String) {
        let app = connect("chat", theme: theme)
        tapWhenReady(app.element("Chat"))
        expectAppears(app.row("chat-message", containing: "Sure, give me five minutes."))
        capture(app, "chat", theme: theme)
    }

    private func messages(theme: String) {
        let app = connect("messages", theme: theme)
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Messages")).firstMatch)
        expectAppears(app.element("messages-screen"))
        capture(app, "messages", theme: theme)
    }

    private func settings(theme: String) {
        let app = connect("voice-server", theme: theme)
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "More")).firstMatch)
        tapWhenReady(app.element("more-settings"))
        expectAppears(app.element("settings-talk-mode"))
        capture(app, "settings", theme: theme)
    }

    // MARK: Tests

    func testServerListDark() { serverList(theme: "dark") }

    func testServerListLight() { serverList(theme: "light") }

    func testServerFormDark() { serverForm(theme: "dark") }

    func testServerFormLight() { serverForm(theme: "light") }

    func testChannelsVoiceBarDark() { voiceBar(theme: "dark") }

    func testChannelsVoiceBarLight() { voiceBar(theme: "light") }

    func testVoiceViewPushToTalkDark() { pushToTalk(theme: "dark") }

    func testVoiceViewPushToTalkLight() { pushToTalk(theme: "light") }

    func testChatDark() { chat(theme: "dark") }

    func testChatLight() { chat(theme: "light") }

    func testMessagesDark() { messages(theme: "dark") }

    func testMessagesLight() { messages(theme: "light") }

    func testSettingsDark() { settings(theme: "dark") }

    func testSettingsLight() { settings(theme: "light") }
}
