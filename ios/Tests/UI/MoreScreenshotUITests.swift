// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Drives the app against the fake engine to the states of the Android screenshot test for the
/// More tab, the activity log and the settings, and keeps a picture of each in both themes, as
/// `ios-<name>-<theme>`. Each state has its own test method, so a failure names the screen.
final class MoreScreenshotUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func capture(_ app: XCUIApplication, _ name: String, theme: String) {
        // The sheets slide in and the lists settle; the picture is taken once they are still.
        Thread.sleep(forTimeInterval: 0.5)
        keepScreenshot(of: app, named: "ios-\(name)-\(theme)")
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

    /// The settings on a phone with an earpiece, with noise suppression switched on and the
    /// microphone at 60 %.
    private func openSettings(theme: String) -> XCUIApplication {
        let app = openMore(",outputs,level", theme: theme)
        tapWhenReady(app.element("more-settings"))
        let noise = app.switches.matching(identifier: "settings-noise-suppression").firstMatch
        tapWhenReady(noise)
        expectAppears(app.element("settings-default-output"))
        return app
    }

    // MARK: States

    /// Mockup `15-more`, as far as it exists, with the user's profile; seven entries of the log
    /// are new.
    private func more(theme: String) {
        let app = openMore(",activity,profile", theme: theme)
        expectAppears(app.element("Activity, 7 new entries"))
        expectAppears(app.element("more-edit-profile"))
        capture(app, "more", theme: theme)
    }

    /// Mockup `10-activity-log`.
    private func activity(theme: String) {
        let app = openMore(",activity", theme: theme)
        tapWhenReady(app.element("more-activity"))
        expectAppears(app.row("activity-entry", containing: "Mira started a screen share in Gaming"))
        capture(app, "activity", theme: theme)
    }

    private func activityStreams(theme: String) {
        let app = openMore(",activity", theme: theme)
        tapWhenReady(app.element("more-activity"))
        tapWhenReady(app.buttons["Streams"])
        expectGone(app.row("activity-entry", containing: "Anna joined Lobby"))
        capture(app, "activity-streams", theme: theme)
    }

    /// The log before anything happened; it has no mockup.
    private func activityEmpty(theme: String) {
        let app = openMore("", theme: theme)
        tapWhenReady(app.element("more-activity"))
        expectAppears(app.element("activity-empty"))
        capture(app, "activity-empty", theme: theme)
    }

    /// Mockup `16-settings`: "Voice", "Mobile data" and "Battery".
    private func settings(theme: String) {
        let app = openSettings(theme: theme)
        capture(app, "settings", theme: theme)
    }

    /// The rest of the settings: "Notifications" and "Chat".
    private func settingsEnd(theme: String) {
        let app = openSettings(theme: theme)
        app.swipeUp()
        expectAppears(app.element("settings-history"))
        capture(app, "settings-end", theme: theme)
    }

    /// The choice of the talk mode; it has no mockup.
    private func talkModeSheet(theme: String) {
        let app = openSettings(theme: theme)
        tapWhenReady(app.element("settings-talk-mode"))
        expectAppears(app.element("settings-option-1"))
        capture(app, "settings-talk-mode-sheet", theme: theme)
    }

    /// After how many days chat history is deleted; it has no mockup.
    private func historySheet(theme: String) {
        let app = openSettings(theme: theme)
        app.swipeUp()
        tapWhenReady(app.element("settings-history"))
        expectAppears(app.element("settings-option-4"))
        capture(app, "settings-history-sheet", theme: theme)
    }

    // MARK: Tests

    func testMoreDark() { more(theme: "dark") }
    func testMoreLight() { more(theme: "light") }
    func testActivityDark() { activity(theme: "dark") }
    func testActivityLight() { activity(theme: "light") }
    func testActivityStreamsDark() { activityStreams(theme: "dark") }
    func testActivityStreamsLight() { activityStreams(theme: "light") }
    func testActivityEmptyDark() { activityEmpty(theme: "dark") }
    func testActivityEmptyLight() { activityEmpty(theme: "light") }
    func testSettingsDark() { settings(theme: "dark") }
    func testSettingsLight() { settings(theme: "light") }
    func testSettingsEndDark() { settingsEnd(theme: "dark") }
    func testSettingsEndLight() { settingsEnd(theme: "light") }
    func testTalkModeSheetDark() { talkModeSheet(theme: "dark") }
    func testTalkModeSheetLight() { talkModeSheet(theme: "light") }
    func testHistorySheetDark() { historySheet(theme: "dark") }
    func testHistorySheetLight() { historySheet(theme: "light") }
}
