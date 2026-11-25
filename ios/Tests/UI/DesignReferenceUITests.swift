// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Opens the debug-only screens of the design system: the channels reference screen in both
/// themes and the component gallery. Screenshots are kept in the result bundle, which CI
/// publishes.
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

    /// The gallery is longer than the screen, so it is captured page by page.
    func testComponentGalleryShowsEveryGroup() {
        let app = XCUIApplication()
        app.launchArguments = ["-mumcordDesignScreen", "gallery", "-mumcordDesignTheme", "dark"] + languageArguments()
        app.launch()

        XCTAssertTrue(app.staticTexts["Components"].waitForExistence(timeout: 10))
        attachScreenshot(of: app, name: "ios-gallery-dark-1")
        let lastGroup = app.staticTexts["TAB BAR"]
        var page = 1
        // Dragged along an edge: the talk button and the slider keep a touch that starts on them
        // for themselves. Where a drag moved nothing, one of them was under it, and the next
        // starting point is tried.
        let starts = [CGVector(dx: 0.02, dy: 0.8), CGVector(dx: 0.98, dy: 0.8), CGVector(dx: 0.02, dy: 0.65)]
        while !lastGroup.isHittable && page < 12 {
            let before = lastGroup.frame.minY
            for start in starts where lastGroup.frame.minY == before {
                let from = app.coordinate(withNormalizedOffset: start)
                let to = app.coordinate(withNormalizedOffset: CGVector(dx: start.dx, dy: start.dy - 0.5))
                from.press(forDuration: 0.1, thenDragTo: to)
            }
            page += 1
            attachScreenshot(of: app, name: "ios-gallery-dark-\(page)")
        }
        XCTAssertTrue(lastGroup.isHittable, "the gallery does not scroll to its last group")
    }

    private func attachScreenshot(of app: XCUIApplication, name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    private func captureChannelsReference(theme: String) {
        let app = XCUIApplication()
        app.launchArguments = ["-mumcordDesignScreen", "channels", "-mumcordDesignTheme", theme] + languageArguments()
        app.launch()

        XCTAssertTrue(app.staticTexts["Nordlicht"].waitForExistence(timeout: 10))
        // Parts of every area of the screen: tree, voice bar and tab bar, with their labels.
        XCTAssertTrue(app.buttons["Collapse Lobby"].exists)
        XCTAssertTrue(app.buttons["Leave server"].exists)
        XCTAssertTrue(app.buttons["Messages, 2 unread"].exists)

        attachScreenshot(of: app, name: "ios-channels-reference-\(theme)")
    }
}
