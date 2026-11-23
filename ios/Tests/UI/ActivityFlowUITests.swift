// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// The activity log in the More tab (mockup `10-activity-log`, FR-5.94), against the fake engine.
final class ActivityFlowUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func openMore(_ options: String) -> XCUIApplication {
        let app = launchApp("servers,voice-server" + options)
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "More")).firstMatch)
        expectAppears(app.element("more-activity"))
        return app
    }

    private func entry(_ app: XCUIApplication, _ sentence: String) -> XCUIElement {
        app.row("activity-entry", containing: sentence)
    }

    private func expectEntries(_ app: XCUIApplication, _ count: Int, file: StaticString = #filePath, line: UInt = #line) {
        let entries = app.descendants(matching: .any).matching(identifier: "activity-entry")
        let reached = XCTNSPredicateExpectation(predicate: NSPredicate(format: "count == %d", count), object: entries)
        XCTAssertEqual(
            XCTWaiter().wait(for: [reached], timeout: 10),
            .completed,
            "\(entries.count) entries instead of \(count)",
            file: file,
            line: line
        )
    }

    /// What happened is listed under its day, what happens appears at once, the filters work
    /// and the row on More counts what is new.
    func testEntriesAppearLiveTheFiltersWorkAndTheBadgeCountsWhatIsNew() {
        let app = openMore(",activity,activity-live")
        expectAppears(app.element("Activity, 7 new entries"))

        tapWhenReady(app.element("more-activity"))
        // Section headers are shown in capitals.
        expectAppears(app.element(containing: "TODAY"))
        expectAppears(app.element(containing: "YESTERDAY"))
        expectAppears(entry(app, "You connected to Nordlicht"))
        expectAppears(entry(app, "Anna joined Lobby"))
        expectAppears(entry(app, "Ben left the server"))
        expectAppears(entry(app, "Jonas moved to Gaming"))

        // While the log is open, what happens appears at once.
        expectAppears(entry(app, "Ben joined Lobby"))
        expectEntries(app, 9)

        tapWhenReady(app.buttons["Streams"])
        expectEntries(app, 2)
        expectAppears(entry(app, "Mira started a screen share in Gaming"))
        expectAppears(entry(app, "Mira started a screen share in Music"))

        tapWhenReady(app.buttons["Joins"])
        expectEntries(app, 7)
        XCTAssertFalse(entry(app, "Mira started a screen share in Gaming").exists)

        tapWhenReady(app.buttons["All"])
        expectEntries(app, 9)

        // Back on More, what was in the log counts as seen; only what follows is new.
        tapWhenReady(app.element("Back to More"))
        expectAppears(app.element("Activity, 1 new entry"))
        tapWhenReady(app.element("more-activity"))
        expectAppears(entry(app, "Mira stopped their screen share"))
    }

    func testWithoutActivityTheLogSaysSo() {
        let app = openMore("")
        XCTAssertFalse(app.element(containing: "new entr").exists)
        tapWhenReady(app.element("more-activity"))
        expectAppears(app.element("activity-empty"))
        expectAppears(app.element(containing: "No activity yet"))
        expectAppears(app.element("Nordlicht"))
        tapWhenReady(app.element("Back to More"))
        expectAppears(app.element("more-disconnect"))
    }

    func testAFilterWithoutEntriesSaysSoToo() {
        let app = openMore(",activity-live")
        tapWhenReady(app.element("more-activity"))
        expectAppears(entry(app, "Ben joined Lobby"))
        tapWhenReady(app.buttons["Streams"])
        expectAppears(app.element(containing: "Nothing of this kind yet."))
        expectEntries(app, 0)
    }
}
