// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// The flows of first start, the server list and connecting, in the running app against the
/// fake engine and the canned data of the mockups: servers Nordlicht, Tidepool, Lantern Guild and
/// Quiet Harbor; the server Nordlicht with the channels Lobby, Gaming, Music and AFK and the
/// people Anna, Ben, Lea (the user), Mira and Jonas.
final class AppFlowUITests: XCTestCase {
    private let linkURL = "mumble://Ben@new.example:40000/Games/Chess"

    override func setUp() {
        continueAfterFailure = false
    }

    private func connect(_ serverName: String, in app: XCUIApplication) {
        tapWhenReady(app.element("Connect to \(serverName)"))
    }

    /// Taps the button of a sheet below a text field. The keyboard is put away first, as it may
    /// cover the button; its return key may already have submitted the sheet, which is fine.
    private func submit(_ button: String, in app: XCUIApplication) {
        dismissKeyboard(in: app)
        let element = app.element(button)
        if element.exists {
            element.tapCenter()
        }
    }

    func testFirstStartChooseTheTalkModeAddAServerConnectTrustSeeTheChannelsAndDisconnect() {
        let app = launchApp("prompt-unknown", setupDone: false)

        // Nothing is picked for the user.
        let continueButton = app.element("talk-mode-continue")
        expectAppears(continueButton)
        XCTAssertFalse(continueButton.isEnabled)
        tapWhenReady(app.element("talk-mode-push-to-talk"))
        XCTAssertTrue(continueButton.isEnabled)
        continueButton.tapCenter()

        expectAppears(app.element(containing: "No servers yet"))
        tapWhenReady(app.element("server-add"))

        // An incomplete form says what is missing instead of saving.
        let save = app.element("server-form-save")
        reveal(save, in: app)
        save.tapCenter()
        expectAppears(app.element(containing: "Enter the address of the server."))

        typeText("10.0.2.2", into: app.input("Address"))
        replaceText(in: app.input("Port"), with: "40000")
        typeText("Home", into: app.input("Name"))
        typeText("Tester", into: app.input("User name"))
        dismissKeyboard(in: app)
        reveal(save, in: app)
        save.tapCenter()

        expectAppears(app.element(containing: "Home"))
        expectAppears(app.element(containing: "10.0.2.2:40000"))
        connect("Home", in: app)

        expectAppears(app.element("Trust this server?"))
        expectAppears(app.element("certificate-fingerprint"))
        expectAppears(app.element(containing: "9F:86:D0:81"))
        tapWhenReady(app.element("certificate-trust"))

        expectAppears(app.element("Channels"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
        expectAppears(app.row("channel-row", containing: "Gaming"))
        expectAppears(app.row("person-row", containing: "Anna"))

        tapWhenReady(app.element("More"))
        tapWhenReady(app.element("more-disconnect"))
        expectAppears(app.element(containing: "Home"))
        expectGone(app.element("more-disconnect"))
    }

    func testConnectedJoinAChannelOpenAPersonSearchSwitchTabsAndDisconnect() {
        let app = launchApp("servers")
        connect("Nordlicht", in: app)
        expectAppears(app.row("channel-row", containing: "Lobby"))
        expectAppears(app.element(containing: "you are here"))

        // Tapping a channel joins it, which moves the "you are here" marker.
        tapWhenReady(app.row("channel-row", containing: "Gaming"))
        expectAppears(app.element(containingAll: ["Gaming", "you are here"]))

        // Tapping a person opens the sheet with the settings for the user only.
        tapWhenReady(app.row("person-row", containing: "Anna"))
        expectAppears(app.element(containing: "Volume for you"))
        expectAppears(app.element(containing: "Mute for me"))
        let mute = app.element("person-mute")
        tapWhenReady(mute)
        if mute.elementType == .switch {
            let muted = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == '1'"), object: mute)
            XCTAssertEqual(XCTWaiter().wait(for: [muted], timeout: 10), .completed, "the mute switch did not turn on")
        }
        // A tap on the dimmed area above the sheet closes it.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()
        expectGone(app.element(containing: "Volume for you"))

        // Search filters channels and people by name.
        tapWhenReady(app.element("Search channels and people"))
        typeText("mir", into: app.anyInput)
        expectAppears(app.row("person-row", containing: "Mira"))
        expectGone(app.row("person-row", containing: "Anna"))
        tapWhenReady(app.element("Close search"))
        expectAppears(app.row("person-row", containing: "Anna"))

        tapWhenReady(app.element("Chat"))
        expectAppears(app.element(containing: "No messages here yet."))
        tapWhenReady(app.element("More"))
        tapWhenReady(app.element("more-disconnect"))
        expectGone(app.element("more-disconnect"))
        expectAppears(app.element("Connect to Nordlicht"))
    }

    func testALockedChannelAsksForItsPasswordAndARefusalShowsABanner() {
        let app = launchApp("servers,locks")
        connect("Nordlicht", in: app)
        expectAppears(app.row("channel-row", containing: "Lobby"))

        tapWhenReady(app.row("channel-row", containing: "Music"))
        expectAppears(app.element("Channel password"))
        typeText("wrong", into: app.anyInput)
        submit("channel-password-join", in: app)
        expectAppears(app.element(containing: "That password was not accepted. Try again."))
        typeText("secret", into: app.anyInput)
        submit("channel-password-join", in: app)
        expectGone(app.element("Channel password"))
        expectAppears(app.element(containingAll: ["Music", "you are here"]))

        tapWhenReady(app.row("channel-row", containing: "AFK"))
        expectAppears(app.element("denied-banner"))
        expectAppears(app.element(containing: "You cannot join AFK"))
    }

    func testTheListShowsPeopleOnlineAndLatencyOrOffline() {
        let app = launchApp("servers")

        expectAppears(app.element(containing: "5 online · 24 ms"))
        expectAppears(app.element(containing: "Offline"))
        expectAppears(app.element(containing: "nordlicht.example"))
    }

    func testAChangedCertificateWarnsStronglyAndNotTrustingItEndsInAnErrorWithRetry() {
        let app = launchApp("servers,prompt-changed")
        connect("Nordlicht", in: app)

        expectAppears(app.element("Server identity changed"))
        expectAppears(app.element(containing: "2C:F2:4D:BA"))
        expectAppears(app.element(containing: "9F:86:D0:81"))
        tapWhenReady(app.element("certificate-reject"))

        expectAppears(app.element("connect-error"))
        tapWhenReady(app.element("connect-retry"))
        expectAppears(app.element("Server identity changed"))
    }

    func testAWrongPasswordOffersToEnterItAndTryAgain() {
        let app = launchApp("servers,server-password")
        connect("Nordlicht", in: app)

        expectAppears(app.element("Enter password"))
        tapWhenReady(app.element("connect-retry"))
        expectAppears(app.element("Server password"))
        XCTAssertFalse(app.element("password-connect").isEnabled)
        typeText("open sesame", into: app.input("Password"))
        submit("password-connect", in: app)

        expectAppears(app.element("Channels"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
    }

    func testEditModeChangesAServerAndRemovesOneAfterAConfirmation() {
        let app = launchApp("servers")
        expectAppears(app.element("Connect to Tidepool"))

        tapWhenReady(app.element("Edit"))
        tapWhenReady(app.element("Edit Tidepool"))
        expectAppears(app.element("Edit server"))
        replaceText(in: app.input("Name"), with: "Tide")
        dismissKeyboard(in: app)
        let save = app.element("server-form-save")
        reveal(save, in: app)
        save.tapCenter()
        expectGone(app.element("Edit server"))
        expectGone(app.element(containing: "Tidepool"))
        expectAppears(app.element("Edit Tide"))

        tapWhenReady(app.element("Remove Nordlicht"))
        expectAppears(app.element("Remove server?"))
        tapWhenReady(app.element("server-remove-confirm"))
        expectGone(app.element(containing: "Nordlicht"))
        tapWhenReady(app.element("Done"))
        expectAppears(app.element("Connect to Tide"))
    }

    func testALinkShowsItsTargetAndConnectsOnlyAfterConfirmationAlsoWhenConnectedElsewhere() throws {
        let app = launchApp("servers")
        connect("Nordlicht", in: app)
        expectAppears(app.element("Channels"))
        expectAppears(app.row("channel-row", containing: "Lobby"))

        let url = try XCTUnwrap(URL(string: linkURL))
        XCUIDevice.shared.system.open(url)
        // The system may ask first whether the link may open in the app.
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.alerts.buttons["Open"]
        if allow.waitForExistence(timeout: 3) {
            allow.tap()
        }

        expectAppears(app.element("Open this server?"))
        expectAppears(app.element(containing: "new.example:40000"))
        expectAppears(app.element(containing: "Games / Chess"))
        expectAppears(app.element(containing: "Ben"))
        expectAppears(app.element(containing: "This server will be added to your list."))

        tapWhenReady(app.element("link-connect"))
        // One server at a time: the running connection is only ended after a confirmation.
        expectAppears(app.element("Switch server?"))
        tapWhenReady(app.element("switch-confirm"))
        // The question replaces the link sheet in place; a touch that lands while the sheet is
        // still settling is lost, so it is repeated once.
        if !app.element("Switch server?").waitForNonExistence(timeout: 3), app.element("switch-confirm").exists {
            app.element("switch-confirm").tapCenter()
        }

        expectGone(app.element("Switch server?"))
        expectAppears(app.element("Channels"))
        expectAppears(app.row("channel-row", containing: "Lobby"))
    }
}
