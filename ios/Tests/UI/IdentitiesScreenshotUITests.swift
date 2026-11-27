// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Drives the app against the fake engine to the states of the Android identity screenshots and
/// keeps a picture of each in both themes, as `ios-identities-<name>-<theme>`. Each state has its
/// own test method, so a failure names the screen. States that need typing or a picked file are
/// put in place by `-mumcordIdentities` (`IdentitiesPreset`).
final class IdentitiesScreenshotUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private struct Shot {
        let options: String
        var setupDone = true
        var preset: String?
        var taps: [String] = []
        let ready: (XCUIApplication) -> XCUIElement
    }

    private static let shots: [String: Shot] = [
        "first-start": Shot(options: "no-identity", setupDone: false, ready: { app in app.element("identity-setup") }),
        "list": Shot(options: "servers", preset: "list", ready: { app in app.element("identity-card") }),
        "list-file-unreadable": Shot(options: "servers", preset: "file-unreadable", ready: { app in app.element("identity-file-notice") }),
        "details": Shot(options: "servers,two-identities", preset: "details", ready: { app in app.element("identity-set-default") }),
        "details-default": Shot(options: "servers", preset: "details-default", ready: { app in app.element("identity-export") }),
        "create": Shot(options: "plain", preset: "create", ready: { app in app.element("identity-create-confirm") }),
        "import-password": Shot(options: "plain", preset: "import", ready: { app in app.element("identity-import-confirm") }),
        "import-wrong-password": Shot(options: "file-password", preset: "import-wrong", ready: { app in app.element(containing: "The password is wrong") }),
        "export": Shot(options: "plain", preset: "export", ready: { app in app.element("identity-export-confirm") }),
        "delete": Shot(options: "servers,two-identities", preset: "delete", ready: { app in app.element("identity-delete-confirm") }),
        "scan-camera-explain": Shot(options: "camera-ask", taps: ["server-scan"], ready: { app in app.element("camera-continue") }),
        "scan-camera-denied": Shot(options: "camera-ask", taps: ["server-scan", "camera-not-now"], ready: { app in app.element("scan-camera-action") }),
        "scan-camera-blocked": Shot(options: "camera-blocked", taps: ["server-scan"], ready: { app in app.element("scan-camera-action") }),
        "scan-waiting": Shot(options: "plain", taps: ["server-scan"], ready: { app in app.element("Waiting for the first code") }),
        "scan": Shot(options: "plain", preset: "scan", ready: { app in app.element("Code 3 of 7") }),
        "scan-foreign-code": Shot(options: "plain", preset: "scan-foreign", ready: { app in app.element("scan-note") }),
        "scan-expired": Shot(options: "transfer-expired", preset: "scan-expired", ready: { app in app.element("scan-note") }),
        "scan-code": Shot(options: "plain", preset: "scan-code", ready: { app in app.element("scan-code") }),
        "scan-checking": Shot(options: "transfer-slow", preset: "scan-checking", ready: { app in app.element("Checking the code…") }),
        "scan-wrong-code": Shot(options: "plain", preset: "scan-wrong", ready: { app in app.element("scan-code-status") }),
        "scan-rescan": Shot(options: "plain", preset: "scan-rescan", ready: { app in app.element("scan-note") }),
        "scan-preview": Shot(options: "plain", preset: "scan-preview", ready: { app in app.element("scan-import") }),
        "scan-import-failed": Shot(options: "transfer-import-fails", preset: "scan-import-failed", ready: { app in app.element("scan-note") }),
        "scan-done": Shot(options: "servers", preset: "scan-done", ready: { app in app.element("scan-done") }),
    ]

    private func capture(_ name: String, theme: String) {
        guard let shot = Self.shots[name] else { return XCTFail("no state \(name)") }
        let app = launchApp(shot.options, setupDone: shot.setupDone, theme: theme, identitiesPreset: shot.preset, locale: "en_US")
        for tap in shot.taps {
            tapWhenReady(app.element(tap))
        }
        expectAppears(shot.ready(app), timeout: 20)
        // The sheets slide in and the lists settle; the picture is taken once they are still.
        Thread.sleep(forTimeInterval: 0.8)
        keepScreenshot(of: app, named: "ios-identities-\(name)-\(theme)")
    }

    func testFirstStartDark() { capture("first-start", theme: "dark") }
    func testFirstStartLight() { capture("first-start", theme: "light") }
    func testListDark() { capture("list", theme: "dark") }
    func testListLight() { capture("list", theme: "light") }
    func testListFileUnreadableDark() { capture("list-file-unreadable", theme: "dark") }
    func testListFileUnreadableLight() { capture("list-file-unreadable", theme: "light") }
    func testDetailsDark() { capture("details", theme: "dark") }
    func testDetailsLight() { capture("details", theme: "light") }
    func testDetailsDefaultDark() { capture("details-default", theme: "dark") }
    func testDetailsDefaultLight() { capture("details-default", theme: "light") }
    func testCreateDark() { capture("create", theme: "dark") }
    func testCreateLight() { capture("create", theme: "light") }
    func testImportPasswordDark() { capture("import-password", theme: "dark") }
    func testImportPasswordLight() { capture("import-password", theme: "light") }
    func testImportWrongPasswordDark() { capture("import-wrong-password", theme: "dark") }
    func testImportWrongPasswordLight() { capture("import-wrong-password", theme: "light") }
    func testExportDark() { capture("export", theme: "dark") }
    func testExportLight() { capture("export", theme: "light") }
    func testDeleteDark() { capture("delete", theme: "dark") }
    func testDeleteLight() { capture("delete", theme: "light") }
    func testScanCameraExplainDark() { capture("scan-camera-explain", theme: "dark") }
    func testScanCameraExplainLight() { capture("scan-camera-explain", theme: "light") }
    func testScanCameraDeniedDark() { capture("scan-camera-denied", theme: "dark") }
    func testScanCameraDeniedLight() { capture("scan-camera-denied", theme: "light") }
    func testScanCameraBlockedDark() { capture("scan-camera-blocked", theme: "dark") }
    func testScanCameraBlockedLight() { capture("scan-camera-blocked", theme: "light") }
    func testScanWaitingDark() { capture("scan-waiting", theme: "dark") }
    func testScanWaitingLight() { capture("scan-waiting", theme: "light") }
    func testScanDark() { capture("scan", theme: "dark") }
    func testScanLight() { capture("scan", theme: "light") }
    func testScanForeignCodeDark() { capture("scan-foreign-code", theme: "dark") }
    func testScanForeignCodeLight() { capture("scan-foreign-code", theme: "light") }
    func testScanExpiredDark() { capture("scan-expired", theme: "dark") }
    func testScanExpiredLight() { capture("scan-expired", theme: "light") }
    func testScanCodeDark() { capture("scan-code", theme: "dark") }
    func testScanCodeLight() { capture("scan-code", theme: "light") }
    func testScanCheckingDark() { capture("scan-checking", theme: "dark") }
    func testScanCheckingLight() { capture("scan-checking", theme: "light") }
    func testScanWrongCodeDark() { capture("scan-wrong-code", theme: "dark") }
    func testScanWrongCodeLight() { capture("scan-wrong-code", theme: "light") }
    func testScanRescanDark() { capture("scan-rescan", theme: "dark") }
    func testScanRescanLight() { capture("scan-rescan", theme: "light") }
    func testScanPreviewDark() { capture("scan-preview", theme: "dark") }
    func testScanPreviewLight() { capture("scan-preview", theme: "light") }
    func testScanImportFailedDark() { capture("scan-import-failed", theme: "dark") }
    func testScanImportFailedLight() { capture("scan-import-failed", theme: "light") }
    func testScanDoneDark() { capture("scan-done", theme: "dark") }
    func testScanDoneLight() { capture("scan-done", theme: "light") }
}
