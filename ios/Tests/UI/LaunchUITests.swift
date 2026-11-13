// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

final class LaunchUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testAppStartsAndShowsTheEngineVersion() throws {
        let app = XCUIApplication()
        app.launch()

        let version = app.staticTexts["engine-version"]
        XCTAssertTrue(version.waitForExistence(timeout: 10))

        // The label reads "Engine version 0.8.0 (6d2ac79e)": a version and the pinned engine
        // reference in brackets, both coming from the Rust library.
        let pattern = #"^Engine version \d+\.\d+\.\d+\S* \(.+\)$"#
        XCTAssertNotNil(
            version.label.range(of: pattern, options: .regularExpression),
            "unexpected engine version text: \(version.label)"
        )
    }
}
