// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

final class LaunchUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// Started without arguments the app runs on the real engine, which is created on first use.
    /// A fresh install shows the identity step of the setup (FR-5.1), an installation that was
    /// set up the list. An installation with an identity but no talk mode shows the talk mode
    /// choice.
    func testAppStartsOnTheRealEngineAndShowsItsFirstScreen() {
        let app = XCUIApplication()
        app.launch()

        let talkModeChoice = app.element("How do you want to talk?")
        let serverList = app.element("Servers")
        let identitySetup = app.element("identity-setup")
        let deadline = Date().addingTimeInterval(20)
        while !talkModeChoice.exists && !serverList.exists && !identitySetup.exists && Date() < deadline {
            _ = talkModeChoice.waitForExistence(timeout: 0.5)
        }
        XCTAssertTrue(
            talkModeChoice.exists || serverList.exists || identitySetup.exists,
            "neither the identity step, the talk mode choice nor the server list is shown"
        )

        // Kept in the result bundle also for a passing test, so CI can publish the first screen.
        keepScreenshot(of: app, named: "first-screen")
    }
}
