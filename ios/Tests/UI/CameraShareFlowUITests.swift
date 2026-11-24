// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Sharing the own camera against the fake engine and a drawn camera (the simulator's camera
/// films nothing a test can check): the voice view's camera button, the camera question, the
/// preview with the camera's picture and the switch, "Start sharing", the "Camera on" bar with
/// the people watching, going to the background and back, Stop, a refused start, and the
/// notice of a camera that stops by itself.
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

    func testThePreviewComesFirstThenTheCameraIsSharedGoesOnInTheBackgroundAndStops() {
        let app = voiceView("camera-viewers")
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
        expectAppears(app.element(containing: "Camera on · 0 watching"))
        XCTAssertFalse(app.element("camera-share-preview").exists, "the preview closed")
        expectAppears(app.element(containing: "Camera on · 2 watching"))
        expectAppears(app.row("voice-camera", containing: "Stop camera"))

        // FR-5.75: sharing goes on while the app is in the background, and the bar is back with it.
        XCUIDevice.shared.press(.home)
        let left = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.state != .runningForeground }, object: nil)
        XCTAssertEqual(XCTWaiter().wait(for: [left], timeout: 10), .completed, "the app did not leave the screen")
        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
        expectAppears(app.element(containing: "Camera on · 2 watching"))

        // The bar stays with the voice bar when the voice view closes.
        tapWhenReady(app.element("voice-collapse"))
        expectAppears(app.element("voice-bar"))
        expectAppears(app.element("camera-bar"))
        tapWhenReady(app.element("camera-bar-stop"))
        expectGone(app.element("camera-bar"))
        XCTAssertFalse(app.element("camera-notice").exists, "no notice after the user's own stop")
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
        expectAppears(app.element("camera-bar"))
        expectAppears(app.element(containing: "The camera could not be opened or stopped working."))
        expectGone(app.element("camera-bar"))
        expectAppears(app.element(containing: "Camera stopped."))
        tapWhenReady(app.element("camera-notice-dismiss"))
        expectGone(app.element("camera-notice"))
    }
}
