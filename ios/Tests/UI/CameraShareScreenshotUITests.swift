// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Drives the app against the fake engine to the camera sharing states of the Android
/// screenshot tests and keeps a picture of each in both themes, as `ios-camera-<name>-<theme>`:
/// the preview sheet of mockup `13-camera-share` (without its "Blur background" row), and,
/// without a mockup, the preview after a refused start, the "Camera on" bar above the voice bar,
/// the voice view with its camera button, the camera question and the notice of a camera that
/// stopped by itself. A drawn person stands in for the camera's picture, as in the mockup.
final class CameraShareScreenshotUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func capture(_ app: XCUIApplication, _ name: String, theme: String) {
        // Sheets and bars slide in; the picture is taken once they are still.
        awaitStillScreen()
        keepScreenshot(of: app, named: "ios-camera-\(name)-\(theme)")
    }

    /// Connected to the server of mockup `02-channels`, with the voice view open.
    private func voiceView(_ options: String, theme: String) -> XCUIApplication {
        let app = launchApp("servers," + options, theme: theme)
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.row("person-row", containing: "Jonas"))
        openVoiceViewThroughTheBar(in: app)
        return app
    }

    private func openPreview(_ app: XCUIApplication) {
        tapWhenReady(app.element("voice-camera"))
        expectAppears(app.element("fake-camera-picture"))
    }

    /// Shared, with two people watching.
    private func share(_ options: String, theme: String) -> XCUIApplication {
        let app = voiceView("camera-viewers," + options, theme: theme)
        openPreview(app)
        tapWhenReady(app.element("camera-share-start"))
        expectAppears(app.element(containing: "Camera on · 2 watching"))
        return app
    }

    // MARK: States

    private func preview(theme: String) {
        let app = voiceView("plain", theme: theme)
        openPreview(app)
        expectAppears(app.element("Front camera"))
        capture(app, "preview", theme: theme)
    }

    private func refused(theme: String) {
        let app = voiceView("camera-refused", theme: theme)
        openPreview(app)
        tapWhenReady(app.element("camera-share-switch"))
        expectAppears(app.element("Back camera"))
        tapWhenReady(app.element("camera-share-start"))
        expectAppears(app.element("camera-share-problem"))
        capture(app, "refused", theme: theme)
    }

    private func sharing(theme: String) {
        let app = share("plain", theme: theme)
        tapWhenReady(app.element("voice-collapse"))
        expectAppears(app.element("voice-bar"))
        expectAppears(app.element("camera-bar"))
        capture(app, "sharing", theme: theme)
    }

    private func voiceViewSharing(theme: String) {
        let app = share("plain", theme: theme)
        expectAppears(app.row("voice-camera", containing: "Stop camera"))
        capture(app, "voice-view", theme: theme)
    }

    private func explain(theme: String) {
        let app = voiceView("camera-ask", theme: theme)
        tapWhenReady(app.element("voice-camera"))
        expectAppears(app.element("camera-continue"))
        capture(app, "explain", theme: theme)
    }

    private func stopped(theme: String) {
        let app = voiceView("camera-fails", theme: theme)
        openPreview(app)
        tapWhenReady(app.element("camera-share-start"))
        expectAppears(app.element("camera-notice"))
        capture(app, "stopped", theme: theme)
    }

    // MARK: Dark and light

    func testPreviewDark() { preview(theme: "dark") }
    func testPreviewLight() { preview(theme: "light") }
    func testRefusedDark() { refused(theme: "dark") }
    func testRefusedLight() { refused(theme: "light") }
    func testSharingDark() { sharing(theme: "dark") }
    func testSharingLight() { sharing(theme: "light") }
    func testVoiceViewDark() { voiceViewSharing(theme: "dark") }
    func testVoiceViewLight() { voiceViewSharing(theme: "light") }
    func testExplainDark() { explain(theme: "dark") }
    func testExplainLight() { explain(theme: "light") }
    func testStoppedDark() { stopped(theme: "dark") }
    func testStoppedLight() { stopped(theme: "light") }
}
