// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Opens the channels reference screen of the design system (debug builds only) in both themes
/// and keeps a screenshot of each in the result bundle, which CI publishes.
final class DesignReferenceUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testChannelsReferenceScreenInDark() {
        captureChannelsReference(theme: "dark")
    }

    func testChannelsReferenceScreenInLight() {
        captureChannelsReference(theme: "light")
    }

    private func captureChannelsReference(theme: String) {
        let app = XCUIApplication()
        app.launchArguments = ["-mumcordDesignScreen", "channels", "-mumcordDesignTheme", theme]
        app.launch()

        XCTAssertTrue(app.staticTexts["Nordlicht"].waitForExistence(timeout: 10))
        // Parts of every area of the screen: tree, voice bar and tab bar, with their labels.
        XCTAssertTrue(app.buttons["Collapse Lobby"].exists)
        XCTAssertTrue(app.buttons["Leave server"].exists)
        XCTAssertTrue(app.buttons["Messages, 2 unread"].exists)

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "ios-channels-reference-\(theme)"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
