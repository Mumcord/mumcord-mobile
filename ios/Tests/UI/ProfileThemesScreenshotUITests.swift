// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Drives the app against the fake engine to the states of the Android screenshot test for edit
/// profile, the themes screen and the server's theme, and three screens in the built-in theme
/// "Deep Ocean", and keeps a picture of each in both phone appearances, as
/// `ios-<name>-<theme>`. Each state has its own test method, so a failure names the screen.
final class ProfileThemesScreenshotUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func capture(_ app: XCUIApplication, _ name: String, theme: String) {
        // Sheets slide in and the colours of a theme are drawn a moment after the engine answers.
        awaitStillScreen()
        keepScreenshot(of: app, named: "ios-\(name)-\(theme)")
    }

    /// Waits until the element is in the same place twice in a row: it has stopped sliding.
    private func awaitResting(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        expectAppears(element, file: file, line: line)
        let deadline = Date().addingTimeInterval(10)
        var before = element.frame
        while Date() < deadline {
            Thread.sleep(forTimeInterval: 0.3)
            let now = element.frame
            if now == before { return }
            before = now
        }
        XCTFail("still moving: \(element)", file: file, line: line)
    }

    /// The More tab of Nordlicht, connected through the server list.
    private func openMore(_ options: String, theme: String) -> XCUIApplication {
        let app = launchApp("servers,voice-server" + options, theme: theme)
        expectAppears(app.element(containing: "5 online · 24 ms"))
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.row("person-row", containing: "Jonas"))
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "More")).firstMatch)
        expectAppears(app.element("more-disconnect"))
        return app
    }

    private func openEditProfile(_ options: String, theme: String) -> XCUIApplication {
        let app = openMore(",profile" + options, theme: theme)
        tapWhenReady(app.element("more-edit-profile"))
        expectAppears(app.element("61 of 5000 characters"))
        return app
    }

    private func openThemes(_ options: String, theme: String) -> XCUIApplication {
        let app = openMore(options, theme: theme)
        tapWhenReady(app.element("more-themes"))
        expectAppears(app.element("themes-mode-system"))
        return app
    }

    // MARK: States

    /// Mockup `20-edit-profile`.
    private func editProfile(theme: String) {
        let app = openEditProfile("", theme: theme)
        capture(app, "profile-edit", theme: theme)
    }

    /// Where a new picture comes from; it has no mockup.
    private func avatarSheet(theme: String) {
        let app = openEditProfile(",profile-avatar", theme: theme)
        tapWhenReady(app.element("profile-photo"))
        expectAppears(app.element("avatar-remove"))
        capture(app, "profile-avatar-sheet", theme: theme)
    }

    /// Mockup `19-themes`, with a theme of the user's own.
    private func themes(theme: String) {
        let app = openThemes(",own-theme", theme: theme)
        expectAppears(app.element("themes-tile-custom:1"))
        capture(app, "themes", theme: theme)
    }

    /// "Paste theme code" with the preview of a desktop's share message; it has no mockup.
    private func paste(theme: String) {
        let app = openThemes("", theme: theme)
        tapWhenReady(app.element("themes-paste"))
        typeText("🎨 Mumcord theme \"Deep Ocean\" – mct1.eyJ2IjoiMC45LjAiLCJuIjoiRGVlcCBPY2VhbiJ9", into: app.element("Theme message or code"))
        // The keyboard stays, as while pasting; the preview and "Apply theme" are above it. The
        // cursor blinks, so the screen is never still: the sheet and the keyboard are.
        let apply = app.element("themes-apply")
        reveal(apply, in: app)
        awaitResting(apply)
        awaitResting(app.keyboards.firstMatch)
        keepScreenshot(of: app, named: "ios-themes-paste-\(theme)")
    }

    /// Sharing or deleting an own theme after a long press; it has no mockup.
    private func actions(theme: String) {
        let app = openThemes(",own-theme", theme: theme)
        let tile = app.element("themes-tile-custom:1")
        expectAppears(tile)
        tile.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 1)
        expectAppears(app.element("themes-delete"))
        capture(app, "themes-actions", theme: theme)
    }

    private func deleteQuestion(theme: String) {
        let app = openThemes(",own-theme", theme: theme)
        let tile = app.element("themes-tile-custom:1")
        expectAppears(tile)
        tile.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 1)
        tapWhenReady(app.element("themes-delete"))
        expectAppears(app.element("themes-delete-confirm"))
        capture(app, "themes-delete", theme: theme)
    }

    /// The theme of a server, chosen in its form; it has no mockup.
    private func serverTheme(theme: String) {
        let app = launchApp("servers", theme: theme)
        tapWhenReady(app.element("Edit"))
        tapWhenReady(app.element("Edit Nordlicht"))
        let row = app.element("server-form-theme")
        reveal(row, in: app)
        row.tapCenter()
        expectAppears(app.element("server-theme-option-preset:ocean"))
        capture(app, "server-theme", theme: theme)
    }

    private func oceanChannels(theme: String) {
        let app = launchApp("servers,voice-server,theme-ocean", theme: theme)
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.row("person-row", containing: "Jonas"))
        capture(app, "themes-ocean-channels", theme: theme)
    }

    private func oceanMore(theme: String) {
        let app = openMore(",theme-ocean,profile,activity", theme: theme)
        expectAppears(app.element("Activity, 7 new entries"))
        capture(app, "themes-ocean-more", theme: theme)
    }

    private func oceanProfile(theme: String) {
        let app = openEditProfile(",theme-ocean", theme: theme)
        capture(app, "themes-ocean-profile", theme: theme)
    }

    // MARK: Tests

    func testEditProfileDark() { editProfile(theme: "dark") }
    func testEditProfileLight() { editProfile(theme: "light") }
    func testAvatarSheetDark() { avatarSheet(theme: "dark") }
    func testAvatarSheetLight() { avatarSheet(theme: "light") }
    func testThemesDark() { themes(theme: "dark") }
    func testThemesLight() { themes(theme: "light") }
    func testPasteDark() { paste(theme: "dark") }
    func testPasteLight() { paste(theme: "light") }
    func testActionsDark() { actions(theme: "dark") }
    func testActionsLight() { actions(theme: "light") }
    func testDeleteQuestionDark() { deleteQuestion(theme: "dark") }
    func testDeleteQuestionLight() { deleteQuestion(theme: "light") }
    func testServerThemeDark() { serverTheme(theme: "dark") }
    func testServerThemeLight() { serverTheme(theme: "light") }
    func testOceanChannelsDark() { oceanChannels(theme: "dark") }
    func testOceanChannelsLight() { oceanChannels(theme: "light") }
    func testOceanMoreDark() { oceanMore(theme: "dark") }
    func testOceanMoreLight() { oceanMore(theme: "light") }
    func testOceanProfileDark() { oceanProfile(theme: "dark") }
    func testOceanProfileLight() { oceanProfile(theme: "light") }
}
