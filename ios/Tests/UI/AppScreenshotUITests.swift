// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Drives the app against the fake engine to the states of the Android screenshot tests and keeps
/// a picture of each in both themes, as `ios-<name>-<theme>`. CI publishes them next to the
/// Android ones, so the two platforms can be compared. Each state has its own test method, so a
/// failure names the screen.
final class AppScreenshotUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    // MARK: Helpers

    private func capture(_ app: XCUIApplication, _ name: String, theme: String) {
        // The sheets slide in and the lists settle; the picture is taken once they are still.
        Thread.sleep(forTimeInterval: 0.5)
        keepScreenshot(of: app, named: "ios-\(name)-\(theme)")
    }

    /// The server list with Nordlicht at the top, with its ping answers, as the mockup shows it.
    private func launchWithServers(_ options: String, theme: String) -> XCUIApplication {
        let app = launchApp(options, theme: theme)
        expectAppears(app.element(containing: "5 online · 24 ms"))
        expectAppears(app.element(containing: "Offline"))
        return app
    }

    private func connectToNordlicht(_ app: XCUIApplication) {
        tapWhenReady(app.element("Connect to Nordlicht"))
    }

    /// The channels of the server, connected through the list.
    private func launchConnected(_ options: String, theme: String) -> XCUIApplication {
        let app = launchWithServers(options, theme: theme)
        connectToNordlicht(app)
        expectAppears(app.row("channel-row", containing: "Lobby"))
        expectAppears(app.row("person-row", containing: "Jonas"))
        return app
    }

    // MARK: States

    private func talkModeChoice(theme: String) {
        let app = launchApp("plain", setupDone: false, theme: theme)
        // Push-to-talk picked, as the mockup shows it; the app itself starts with nothing picked.
        tapWhenReady(app.element("talk-mode-push-to-talk"))
        expectAppears(app.element("talk-mode-continue"))
        capture(app, "talk-mode-choice", theme: theme)
    }

    private func serverList(theme: String) {
        let app = launchWithServers("servers", theme: theme)
        capture(app, "server-list", theme: theme)
    }

    private func serverListEmpty(theme: String) {
        let app = launchApp("plain", theme: theme)
        expectAppears(app.element(containing: "No servers yet"))
        capture(app, "server-list-empty", theme: theme)
    }

    /// An invalid port and no user name, with the errors shown after a try to save.
    private func serverForm(theme: String) {
        let app = launchApp("plain", theme: theme)
        tapWhenReady(app.element("server-add"))
        replaceText(in: app.input("Port"), with: "99999")
        typeText("Nordlicht", into: app.input("Name"))
        typeText("nordlicht.example", into: app.input("Address"))
        dismissKeyboard(in: app)
        let save = app.element("server-form-save")
        reveal(save, in: app)
        save.tapCenter()
        expectAppears(app.element(containing: "Enter a number between 1 and 65535."))
        expectAppears(app.element(containing: "Enter the name you want to use on this server."))
        // The form's keyboard has no key that only closes it; dragging the form down does, and
        // a second drag brings the first field back to the top.
        app.swipeDown()
        app.swipeDown()
        capture(app, "server-form", theme: theme)
    }

    private func certificateUnknown(theme: String) {
        let app = launchWithServers("servers,prompt-unknown", theme: theme)
        connectToNordlicht(app)
        expectAppears(app.element("Trust this server?"))
        expectAppears(app.element("certificate-fingerprint"))
        capture(app, "certificate-unknown", theme: theme)
    }

    private func certificateChanged(theme: String) {
        let app = launchWithServers("servers,prompt-changed", theme: theme)
        connectToNordlicht(app)
        expectAppears(app.element("Server identity changed"))
        capture(app, "certificate-changed", theme: theme)
    }

    private func connectError(theme: String) {
        let app = launchWithServers("servers,unreachable", theme: theme)
        connectToNordlicht(app)
        expectAppears(app.element("connect-error"))
        capture(app, "connect-error", theme: theme)
    }

    private func channels(theme: String) {
        let app = launchConnected("servers", theme: theme)
        capture(app, "channels", theme: theme)
    }

    private func personSheet(theme: String) {
        let app = launchConnected("servers", theme: theme)
        tapWhenReady(app.row("person-row", containing: "Jonas"))
        expectAppears(app.element(containing: "Volume for you"))
        capture(app, "person-sheet", theme: theme)
    }

    private func channelPassword(theme: String) {
        let app = launchConnected("servers,locks", theme: theme)
        tapWhenReady(app.row("channel-row", containing: "Music"))
        expectAppears(app.element("Channel password"))
        // The sheet may have opened the keyboard; the mockup shows none. An empty password is
        // never sent, so its return key leaves the question as it is.
        dismissKeyboard(in: app)
        capture(app, "channel-password", theme: theme)
    }

    private func channelSearch(theme: String) {
        let app = launchConnected("servers", theme: theme)
        tapWhenReady(app.element("Search channels and people"))
        typeText("mi", into: app.anyInput)
        expectAppears(app.row("person-row", containing: "Mira"))
        expectGone(app.row("person-row", containing: "Anna"))
        dismissKeyboard(in: app)
        capture(app, "channel-search", theme: theme)
    }

    private func more(theme: String) {
        let app = launchConnected("servers", theme: theme)
        tapWhenReady(app.element("More"))
        expectAppears(app.element("more-disconnect"))
        capture(app, "more", theme: theme)
    }

    private func tabEmpty(theme: String) {
        let app = launchConnected("servers", theme: theme)
        tapWhenReady(app.element("Chat"))
        expectAppears(app.element("empty-tab"))
        capture(app, "tab-empty", theme: theme)
    }

    // MARK: Tests, one per state and theme

    func testTalkModeChoiceDark() {
        talkModeChoice(theme: "dark")
    }

    func testTalkModeChoiceLight() {
        talkModeChoice(theme: "light")
    }

    func testServerListDark() {
        serverList(theme: "dark")
    }

    func testServerListLight() {
        serverList(theme: "light")
    }

    func testServerListEmptyDark() {
        serverListEmpty(theme: "dark")
    }

    func testServerListEmptyLight() {
        serverListEmpty(theme: "light")
    }

    func testServerFormDark() {
        serverForm(theme: "dark")
    }

    func testServerFormLight() {
        serverForm(theme: "light")
    }

    func testCertificateUnknownDark() {
        certificateUnknown(theme: "dark")
    }

    func testCertificateUnknownLight() {
        certificateUnknown(theme: "light")
    }

    func testCertificateChangedDark() {
        certificateChanged(theme: "dark")
    }

    func testCertificateChangedLight() {
        certificateChanged(theme: "light")
    }

    func testConnectErrorDark() {
        connectError(theme: "dark")
    }

    func testConnectErrorLight() {
        connectError(theme: "light")
    }

    func testChannelsDark() {
        channels(theme: "dark")
    }

    func testChannelsLight() {
        channels(theme: "light")
    }

    func testPersonSheetDark() {
        personSheet(theme: "dark")
    }

    func testPersonSheetLight() {
        personSheet(theme: "light")
    }

    func testChannelPasswordDark() {
        channelPassword(theme: "dark")
    }

    func testChannelPasswordLight() {
        channelPassword(theme: "light")
    }

    func testChannelSearchDark() {
        channelSearch(theme: "dark")
    }

    func testChannelSearchLight() {
        channelSearch(theme: "light")
    }

    func testMoreDark() {
        more(theme: "dark")
    }

    func testMoreLight() {
        more(theme: "light")
    }

    func testTabEmptyDark() {
        tabEmpty(theme: "dark")
    }

    func testTabEmptyLight() {
        tabEmpty(theme: "light")
    }
}
