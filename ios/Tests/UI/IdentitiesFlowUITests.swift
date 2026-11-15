// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// The identity screens and "Scan from desktop app" in the simulator, against the fake engine.
/// The simulator has no camera, so the scanner sees the QR codes of a scripted transfer
/// (`qr-parts`); the real format is tested in the engine.
final class IdentitiesFlowUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// FR-5.1, AC-5.1: a fresh install asks for an identity before the talk mode.
    func testTheFirstStartCreatesAnIdentityBeforeTheTalkMode() {
        let app = launchApp("no-identity", setupDone: false)
        expectAppears(app.element("Your identity"))
        tapWhenReady(app.element("identity-create"))
        typeText("Lea", into: app.input("Name on servers"))
        tapWhenReady(app.element("identity-create-confirm"))
        expectAppears(app.element("How do you want to talk?"))
    }

    /// FR-5.65, FR-5.66: the codes are scanned, a wrong code is refused, the right one shows
    /// what arrives, and only "Import" imports it.
    func testScanFromTheEmptyServerListImportsAfterTheRightCode() {
        let app = launchApp("qr-parts")
        tapWhenReady(app.element("server-scan"))
        let code = app.element("scan-code")
        expectAppears(code, timeout: 20)
        typeText("11112222", into: code)
        expectAppears(app.element("Wrong code. 2 tries left."), timeout: 10)
        typeText("48217305", into: app.element("scan-code"))
        expectAppears(app.row("scan-summary", containing: "1 identity, 4 servers"), timeout: 10)
        XCTAssertTrue(app.element("Server passwords are not transferred.").exists)
        tapWhenReady(app.element("scan-import"))
        expectAppears(app.element("Imported"))
        tapWhenReady(app.element("scan-done"))
        expectAppears(app.element(containing: "Night owls"))
    }

    /// The camera is explained first; "Not now" leaves a clear way to allow it, and after the
    /// system's "no" only the system settings help.
    func testTheCameraIsExplainedBeforeTheSystemAsks() {
        let app = launchApp("camera-refuse")
        tapWhenReady(app.element("server-scan"))
        expectAppears(app.element(containing: "read the codes on your desktop"))
        tapWhenReady(app.element("camera-not-now"))
        expectAppears(app.row("scan-camera-action", containing: "Allow the camera"))
        tapWhenReady(app.element("scan-camera-action"))
        expectAppears(app.row("scan-camera-action", containing: "Open settings"))
        let close = app.element("scan-close")
        if !close.waitForExistence(timeout: 5) {
            // Only fake data is on screen: the tree says why the close button is not there.
            print(app.debugDescription)
        }
        tapWhenReady(close)
        expectAppears(app.element("server-add"))
    }

    /// FR-5.62: an identity file is imported with its password; a wrong one is said as such.
    func testAnIdentityFileIsImportedWithItsPassword() {
        let app = launchApp("file-password", identitiesPreset: "import")
        let password = app.input("Password of the file")
        typeText("wrong", into: password)
        tapWhenReady(app.element("identity-import-confirm"))
        expectAppears(app.element(containing: "The password is wrong"))
        typeText("secret", into: app.input("Password of the file"))
        tapWhenReady(app.element("identity-import-confirm"))
        expectAppears(app.row("identity-card", containing: "Imported"))
    }

    /// More → Identities: another identity becomes the default, and one is deleted after its
    /// label was typed.
    func testAnIdentityBecomesTheDefaultAndAnotherIsDeleted() {
        let app = launchApp("servers,two-identities")
        expectAppears(app.element(containing: "5 online · 24 ms"))
        tapWhenReady(app.element("Connect to Nordlicht"))
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "More")).firstMatch)
        tapWhenReady(app.element("more-identities"))
        tapWhenReady(app.row("identity-card", containing: "Work"))
        tapWhenReady(app.element("identity-set-default"))
        expectAppears(app.element(containing: "This is your default identity."))
        tapWhenReady(app.element("identity-details-close"))
        expectAppears(app.row("identity-card", containing: "Work (default)"))
        tapWhenReady(app.row("identity-card", containing: "Lea"))
        tapWhenReady(app.element("identity-delete"))
        let confirm = app.element("identity-delete-confirm")
        expectAppears(confirm)
        XCTAssertFalse(confirm.isEnabled)
        // The return key puts the keyboard away; the keyboard's own Done key was invalidated by
        // the sheet moving up with it in CI.
        typeText("Lea\n", into: app.input("Type the identity label to confirm"))
        reveal(confirm, in: app)
        tapWhenReady(confirm)
        expectGone(app.row("identity-card", containing: "Lea"))
    }
}
