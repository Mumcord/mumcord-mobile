// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Sharing the own camera against the fake engine and a drawn camera (the simulator's camera
/// films nothing a test can check): the voice view's camera button, the camera question, the
/// preview with the camera's picture and the switch, "Start sharing", the "Camera on" bar with
/// the people watching, going to the background and back, Stop, Cancel and putting the preview
/// away while the start still waits for the engine, a refused start, and the notice of a camera
/// that stops by itself.
final class CameraShareFlowUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// Connected to Nordlicht, with the voice view open.
    private func voiceView(_ options: String) -> XCUIApplication {
        let app = launchApp("servers,voice-server," + options)
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.element("voice-bar"))
        tapWhenReady(app.element(containing: "Voice connected"))
        expectAppears(app.element("voice-view"))
        return app
    }

    /// What the drawn camera was asked to do: its facings and stops, e.g. "front,stop"
    /// (`FakeCameraProbeView`, with the launch option `camera-probe`).
    private func cameraCalls(_ app: XCUIApplication) -> String {
        app.element("fake-camera").value as? String ?? ""
    }

    /// Waits until the drawn camera's calls end with `suffix`.
    private func expectCameraCalls(
        _ app: XCUIApplication,
        endingWith suffix: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let reached = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.cameraCalls(app).hasSuffix(suffix)
        }, object: nil)
        XCTAssertEqual(
            XCTWaiter().wait(for: [reached], timeout: 10), .completed,
            "the camera's calls \"\(cameraCalls(app))\" do not end with \"\(suffix)\"",
            file: file, line: line
        )
    }

    func testThePreviewComesFirstThenTheCameraIsSharedGoesOnInTheBackgroundAndStops() {
        let app = voiceView("camera-viewers,camera-probe")
        XCTAssertFalse(app.element("camera-share-preview").exists)
        expectAppears(app.row("voice-camera", containing: "Share your camera"))
        app.element("voice-camera").tapCenter()

        // FR-5.74: the preview sheet first, with the front camera.
        expectAppears(app.element("camera-share-preview"))
        expectAppears(app.element("fake-camera-picture"))
        expectAppears(app.element("Front camera"))
        expectAppears(app.element("Your camera pauses when Mumcord is in the background"))
        XCTAssertFalse(app.element("camera-bar").exists, "nothing is shared from the preview")
        tapWhenReady(app.element("camera-share-switch"))
        expectAppears(app.element("Back camera"))
        expectGone(app.element("Front camera"))

        tapWhenReady(app.element("camera-share-start"))
        expectAppears(app.element(containing: "Camera on · 2 watching"))
        XCTAssertFalse(app.element("camera-share-preview").exists, "the preview closed")
        expectAppears(app.row("voice-camera", containing: "Stop camera"))
        XCTAssertEqual(cameraCalls(app), "front,back")

        // FR-5.75: the camera is let go in the background while sharing goes on, and runs again
        // with the same camera on return, with the bar.
        XCUIDevice.shared.press(.home)
        let left = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.state != .runningForeground }, object: nil)
        XCTAssertEqual(XCTWaiter().wait(for: [left], timeout: 10), .completed, "the app did not leave the screen")
        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
        expectAppears(app.element(containing: "Camera on · 2 watching"))
        expectCameraCalls(app, endingWith: "front,back,stop,back")

        // The bar stays with the voice bar when the voice view closes.
        tapWhenReady(app.element("voice-collapse"))
        expectAppears(app.element("voice-bar"))
        expectAppears(app.element("camera-bar"))
        tapWhenReady(app.element("camera-bar-stop"))
        expectGone(app.element("camera-bar"))
        XCTAssertFalse(app.element("camera-notice").exists, "no notice after the user's own stop")
        expectCameraCalls(app, endingWith: "back,stop")
    }

    /// Opens the preview with a start of half a minute and taps "Start sharing".
    private func starting() -> XCUIApplication {
        let app = voiceView("camera-slow-start,camera-probe")
        app.element("voice-camera").tapCenter()
        tapWhenReady(app.element("camera-share-start"))
        expectAppears(app.row("camera-share-start", containing: "Starting…"))
        XCTAssertEqual(cameraCalls(app), "front")
        return app
    }

    func testCancelWhileStartingClosesTheCameraAtOnce() {
        let app = starting()
        tapWhenReady(app.element("camera-share-cancel"))
        expectGone(app.element("camera-share-preview"), timeout: 5)
        expectCameraCalls(app, endingWith: "front,stop")
        expectAppears(app.row("voice-camera", containing: "Share your camera"))
        XCTAssertFalse(app.element("camera-bar").exists)
    }

    func testPuttingThePreviewAwayWhileStartingClosesTheCameraAtOnce() {
        let app = starting()
        // A tap above the sheet puts it away.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()
        expectGone(app.element("camera-share-preview"), timeout: 5)
        expectCameraCalls(app, endingWith: "front,stop")
        XCTAssertFalse(app.element("camera-bar").exists)
    }

    func testTheCameraPausesWhileAnotherSheetCoversThePreview() throws {
        let app = voiceView("camera-probe")
        app.element("voice-camera").tapCenter()
        expectAppears(app.element("camera-share-preview"))
        XCTAssertEqual(cameraCalls(app), "front")

        // A link opened meanwhile shows its own sheet in place of the preview.
        XCUIDevice.shared.system.open(try XCTUnwrap(URL(string: "mumble://Ben@new.example:40000/Games")))
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.alerts.buttons["Open"]
        if allow.waitForExistence(timeout: 3) {
            allow.tap()
        }
        expectAppears(app.element("Open this server?"))
        XCTAssertFalse(app.element("camera-share-preview").exists)
        expectCameraCalls(app, endingWith: "front,stop")

        // Back to the preview, the camera runs again.
        tapWhenReady(app.buttons["Cancel"])
        expectAppears(app.element("camera-share-preview"))
        expectCameraCalls(app, endingWith: "front,stop,front")
    }

    func testCancelInThePreviewSharesNothing() {
        let app = voiceView("plain")
        app.element("voice-camera").tapCenter()
        tapWhenReady(app.element("camera-share-cancel"))
        expectGone(app.element("camera-share-preview"))
        XCTAssertFalse(app.element("camera-bar").exists)
        expectAppears(app.row("voice-camera", containing: "Share your camera"))
    }

    func testTheCameraIsExplainedBeforeTheSystemAsks() {
        let app = voiceView("camera-ask")
        app.element("voice-camera").tapCenter()
        expectAppears(app.element(containing: "Mumcord needs the camera to share it with the people in your channel."))
        XCTAssertFalse(app.element("camera-share-preview").exists)
        tapWhenReady(app.element("camera-continue"))
        expectAppears(app.element("camera-share-preview"))
    }

    func testRefusingTheSystemQuestionPointsToTheSettings() {
        let app = voiceView("camera-refuse")
        app.element("voice-camera").tapCenter()
        tapWhenReady(app.element("camera-continue"))
        expectAppears(app.element(containing: "Allow “Camera” for Mumcord in the system settings."))
        expectAppears(app.row("camera-continue", containing: "Open settings"))
        XCTAssertFalse(app.element("camera-share-preview").exists, "the camera stays closed")
    }

    func testAfterARefusalTheSheetPointsToTheSettings() {
        let app = voiceView("camera-blocked")
        app.element("voice-camera").tapCenter()
        expectAppears(app.element(containing: "Allow “Camera” for Mumcord in the system settings."))
        expectAppears(app.row("camera-continue", containing: "Open settings"))
        tapWhenReady(app.element("camera-not-now"))
        expectGone(app.element("camera-continue"))
        XCTAssertFalse(app.element("camera-share-preview").exists, "the camera stays closed")
    }

    func testARefusedStartSaysWhyInThePreview() {
        let app = voiceView("camera-refused")
        app.element("voice-camera").tapCenter()
        tapWhenReady(app.element("camera-share-start"))
        expectAppears(app.element(containing: "Camera sharing needs direct connections between clients, which are turned off."))
        expectAppears(app.element("camera-share-preview"))
        XCTAssertFalse(app.element("camera-bar").exists)
    }

    func testACameraThatStopsByItselfSaysSo() {
        let app = voiceView("camera-fails")
        app.element("voice-camera").tapCenter()
        tapWhenReady(app.element("camera-share-start"))
        expectAppears(app.element(containing: "The camera could not be opened or stopped working."))
        expectGone(app.element("camera-bar"))
        expectAppears(app.element(containing: "Camera stopped."))
        tapWhenReady(app.element("camera-notice-dismiss"))
        expectGone(app.element("camera-notice"))
    }
}
