// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// The public server browser in the running app against the fake engine and the list of
/// `PublicServersMockup`, as `PublicServersUiFlowTest` on Android: nothing is requested before it
/// opens, search and the country filter narrow the list, the rows on screen show their ping, a
/// listed server is added and connected to, a list that cannot be loaded or refreshed is handled
/// calmly, and the pings stop when the browser closes or the app leaves the screen. Neither the
/// real list service nor any listed server is contacted. The invisible element
/// "fake-public-list" says what the fake engine was asked.
final class PublicServersFlowUITests: XCTestCase {
    private let search = "Search by name, address or country"

    override func setUp() {
        continueAfterFailure = false
    }

    private func rows(_ app: XCUIApplication) -> XCUIElementQuery {
        app.descendants(matching: .any).matching(identifier: "public-server-row")
    }

    private func expectRows(_ count: Int, in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let counted = XCTNSPredicateExpectation(predicate: NSPredicate(format: "count == %d", count), object: rows(app))
        XCTAssertEqual(XCTWaiter().wait(for: [counted], timeout: 10), .completed, "not \(count) rows", file: file, line: line)
    }

    /// Waits until the fake engine's summary ("downloads 1, pings 4, answered 4") matches.
    private func expectEngine(
        _ app: XCUIApplication,
        _ format: String,
        _ value: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let probe = app.element("fake-public-list")
        expectAppears(probe, file: file, line: line)
        let matched = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value \(format) %@", value), object: probe)
        let result = XCTWaiter().wait(for: [matched], timeout: 30)
        XCTAssertEqual(result, .completed, "the fake engine says \(probe.value ?? "nothing"), not \(value)", file: file, line: line)
    }

    /// The summary stays as it is for `seconds`. Pings that had not stopped would have sent more
    /// meanwhile: the slow pings answer after ten seconds, which frees room for the next ones.
    /// The one wait for a time, because it checks that nothing happens.
    private func expectEngineStays(
        _ app: XCUIApplication,
        _ value: String,
        seconds: TimeInterval,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let changed = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value != %@", value),
            object: app.element("fake-public-list")
        )
        XCTAssertEqual(XCTWaiter().wait(for: [changed], timeout: seconds), .timedOut, "more pings after stopping", file: file, line: line)
    }

    private func openBrowser(_ app: XCUIApplication) {
        let entry = app.element("server-browse-public")
        reveal(entry, in: app)
        entry.tapCenter()
        expectAppears(app.element("public-servers"))
    }

    func testTheListIsLoadedOnlyWhenOpenedNarrowedBySearchAndCountryAndAListedServerIsAddedAndConnectedTo() {
        let app = launchApp("servers,public,public-probe")
        expectAppears(app.element(containing: "5 online · 24 ms"))
        expectEngine(app, "==", "downloads 0, pings 0, answered 0")

        openBrowser(app)
        expectRows(6, in: app)
        expectEngine(app, "BEGINSWITH", "downloads 1,")
        XCTAssertFalse(app.element(containing: "Loading").exists)
        // Name, address and country of every row; the ping of the rows on screen.
        expectAppears(app.element(containing: "voice.nordic.example · Sweden"))
        expectAppears(app.element(containing: "12 of 50 people online, 34 ms"))
        expectAppears(app.element(containing: "No answer"))
        // The servers already in the server list offer Connect, the others Add.
        expectAppears(app.element("Connect to Lantern Guild"))
        expectAppears(app.element("Add Nordic Friends to your servers"))

        typeText("lantern", into: app.input(search))
        expectRows(1, in: app)
        expectAppears(app.element(containing: "Lantern Guild"))
        replaceText(in: app.input(search), with: "")
        dismissKeyboard(in: app)
        expectRows(6, in: app)

        tapWhenReady(app.buttons["Germany"])
        expectRows(2, in: app)
        expectAppears(app.element(containing: "Berlin Gaming"))
        expectAppears(app.element(containing: "Lantern Guild"))
        XCTAssertFalse(app.element(containing: "Nordic Friends").exists)
        tapWhenReady(app.buttons["Any country"])
        expectRows(6, in: app)

        tapWhenReady(app.element("Add Nordic Friends to your servers"))
        tapWhenReady(app.element("Connect to Nordic Friends"))
        expectGone(app.element("public-servers"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
    }

    func testAListThatCannotBeLoadedOffersTryAgainAndAFailedRefreshKeepsTheLastList() {
        let app = launchApp("public,public-down-first,public-refresh-fails,public-probe")
        openBrowser(app)
        expectAppears(app.element(containing: "The list could not be loaded"))
        expectAppears(app.element("public-servers-retry"))
        XCTAssertEqual(rows(app).count, 0)

        tapWhenReady(app.element("public-servers-retry"))
        expectRows(6, in: app)

        tapWhenReady(app.buttons["Refresh"])
        expectAppears(app.element("public-servers-refresh-failed"))
        XCTAssertEqual(rows(app).count, 6, "the last list stays")
        expectEngine(app, "BEGINSWITH", "downloads 3,")
    }

    func testBackLeavesTheBrowserAndPingsStop() {
        let app = launchApp("servers,public,public-ping-slow,public-probe")
        openBrowser(app)
        // Six rows on screen: four pings at once, the other two wait for a free place.
        expectEngine(app, "CONTAINS", "pings 4, answered 0")

        tapWhenReady(app.element("Back to the server list"))
        expectGone(app.element("public-servers"))
        expectAppears(app.element("server-browse-public"))
        // The four on their way end at once, and the two that waited are never sent.
        expectEngine(app, "==", "downloads 1, pings 4, answered 4")
        expectEngineStays(app, "downloads 1, pings 4, answered 4", seconds: 12)
    }

    func testPingsPauseWhileTheAppIsInTheBackgroundAndGoOnWhenItIsBack() {
        let app = launchApp("public,public-ping-slow,public-probe")
        openBrowser(app)
        expectEngine(app, "CONTAINS", "pings 4, answered 0")

        // Back long before the four pings could answer: only pings that stopped in the
        // background are asked again on return. Pings that had gone on would answer after ten
        // seconds and leave room for the last two only, six in all.
        XCUIDevice.shared.press(.home)
        let left = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "state != %d", XCUIApplication.State.runningForeground.rawValue),
            object: app
        )
        XCTAssertEqual(XCTWaiter().wait(for: [left], timeout: 10), .completed, "the app did not leave the screen")
        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
        expectEngine(app, "CONTAINS", "pings 8, answered 4")
        expectAppears(app.element(containing: "12 of 50 people online, 34 ms"), timeout: 30)
        expectEngine(app, "==", "downloads 1, pings 10, answered 10")
    }

    func testAtLargeTextTheBrowserIsReachableAndItsButtonsAreLargeEnoughToTap() {
        let app = launchApp("servers,public", textSize: "UICTContentSizeCategoryAccessibilityL")
        let entry = app.element("server-browse-public")
        reveal(entry, in: app)
        XCTAssertGreaterThanOrEqual(entry.frame.height, 44)
        entry.tapCenter()
        expectAppears(app.element("public-servers"))
        let add = app.descendants(matching: .any).matching(identifier: "public-server-add").firstMatch
        expectAppears(add)
        XCTAssertGreaterThanOrEqual(add.frame.height, 44)
        XCTAssertGreaterThan(rows(app).count, 0)
    }
}
