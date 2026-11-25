// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// VoiceOver in the two-column iPad layout (AC-5.31, AC-5.33), on an iPad simulator: it reads the
/// navigation rail, then the whole left column with the voice bar, then the right column, also
/// with a stream in the right column, and both pass the accessibility audit with the checks of
/// `AccessibilityUITests`.
final class TabletAccessibilityUITests: XCTestCase {
    /// The rail's width and the left column's, in points (`railWidth`, `sideColumnWidth`).
    private let railEnd: CGFloat = 88
    private let sideEnd: CGFloat = 88 + 340

    private let auditTypes = XCUIAccessibilityAuditType.all
        .subtracting([.contrast, .dynamicType, .textClipped, .elementDetection])

    override func setUpWithError() throws {
        continueAfterFailure = false
        try requireIPad()
        XCUIDevice.shared.orientation = .landscapeLeft
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
        super.tearDown()
    }

    private func connect() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "-mumcordFakeEngine", "servers,streams,chat", "-mumcordSetupDone", "YES", "-mumcordTheme", "dark",
            "-mumcordAccessibilityProbe", "YES",
        ] + languageArguments()
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 120), "the app did not come on screen")
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.element("stream-tile"))
        return app
    }

    /// 0 for the rail, 1 for the left column, 2 for the right one.
    private func column(_ element: VoiceOverElement) -> Int {
        element.frame.minX < railEnd ? 0 : element.frame.minX < sideEnd ? 1 : 2
    }

    /// The columns are read one after the other, never back and forth.
    private func expectColumnsInOrder(_ app: XCUIApplication, rightTitle: String, file: StaticString = #filePath, line: UInt = #line) {
        let elements = voiceOverElements(in: app, file: file, line: line)
        let list = elements.map { "\(column($0)) \($0)" }.joined(separator: "\n")
        let columns = elements.map(column)
        XCTAssertEqual(columns, columns.sorted(), "the columns are read back and forth:\n\(list)", file: file, line: line)
        XCTAssertEqual(Set(columns), [0, 1, 2], "a column is missing:\n\(list)", file: file, line: line)
        XCTAssertEqual(elements.first?.label, "Channels", "the rail comes first:\n\(list)", file: file, line: line)
        XCTAssertEqual(elements.first { column($0) == 1 }?.label, "Nordlicht", "then the channels' title:\n\(list)", file: file, line: line)
        XCTAssertTrue(elements.contains { column($0) == 1 && $0.label == "Leave server" }, "the voice bar is in the left column:\n\(list)", file: file, line: line)
        let right = elements.first { column($0) == 2 }
        XCTAssertEqual(right?.label, rightTitle, "the right column's title comes first:\n\(list)", file: file, line: line)
        XCTAssertEqual(right?.heading, true, file: file, line: line)
    }

    func testTheRailTheLeftColumnAndTheRightColumnAreReadOneAfterTheOther() throws {
        let app = connect()
        expectAppears(app.element("chat-screen"))
        expectColumnsInOrder(app, rightTitle: "# Lobby")
        try app.performAccessibilityAudit(for: auditTypes)

        app.element("stream-tile").tapCenter()
        expectAppears(app.element("stream-column"))
        expectColumnsInOrder(app, rightTitle: "Mira's screen")
        try app.performAccessibilityAudit(for: auditTypes)
    }
}
