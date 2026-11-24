// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// Edit profile, the themes screen and the colours the app is drawn in (FR-5.80, FR-5.81),
/// against the fake engine, as the Android app's `ProfileThemesUiFlowTest`.
final class ProfileThemesFlowUITests: XCTestCase {
    /// Left of the content, where only the screen's background is drawn.
    private let background = CGPoint(x: 0.01, y: 0.5)

    /// The simulator's own appearance before a test changed it; given back afterwards.
    private var phoneBefore: XCUIDevice.Appearance?

    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        if let phoneBefore { XCUIDevice.shared.appearance = phoneBefore }
        super.tearDown()
    }

    /// Switches the simulator's own dark mode, as the user does in the phone's settings.
    private func setPhone(_ appearance: XCUIDevice.Appearance) {
        if phoneBefore == nil { phoneBefore = XCUIDevice.shared.appearance }
        XCUIDevice.shared.appearance = appearance
    }

    /// The app connected to Nordlicht, on the More tab; the phone's appearance forced to dark,
    /// or with `phone` "system" the simulator's own.
    private func openMore(_ options: String = "", phone: String = "dark") -> XCUIApplication {
        let app = launchApp("servers,voice-server" + options, theme: phone)
        expectAppears(app.element(containing: "5 online · 24 ms"))
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.row("person-row", containing: "Jonas"))
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "More")).firstMatch)
        expectAppears(app.element("more-disconnect"))
        return app
    }

    private func openThemes(_ options: String = "", phone: String = "dark") -> XCUIApplication {
        let app = openMore(options, phone: phone)
        tapWhenReady(app.element("more-themes"))
        expectAppears(app.element("themes-mode-dark"))
        return app
    }

    private func longPress(_ element: XCUIElement) {
        expectAppears(element)
        element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 1)
    }

    private func isSelected(_ element: XCUIElement) -> XCTNSPredicateExpectation {
        XCTNSPredicateExpectation(predicate: NSPredicate(format: "isSelected == true"), object: element)
    }

    private func expectSelected(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(XCTWaiter().wait(for: [isSelected(element)], timeout: 10), .completed, "not selected: \(element)", file: file, line: line)
    }

    private func isDark(_ colour: ScreenColour) -> Bool { colour.r + colour.g + colour.b < 200 }

    private func isRed(_ colour: ScreenColour) -> Bool { colour.r > colour.g + 80 && colour.r > colour.b + 50 }

    /// Whether any pixel the element covers on screen passes `check`.
    private func anyPixel(of element: XCUIElement, _ check: (ScreenColour) -> Bool) -> Bool {
        let screenshot = XCUIScreen.main.screenshot().image
        guard let image = screenshot.cgImage else { return false }
        let scale = CGFloat(image.width) / screenshot.size.width
        let frame = element.frame
        let area = CGRect(x: frame.minX * scale, y: frame.minY * scale, width: frame.width * scale, height: frame.height * scale).integral
        guard let cropped = image.cropping(to: area) else { return false }
        let width = cropped.width
        let height = cropped.height
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        guard let context = CGContext(
            data: &bytes,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return false }
        context.draw(cropped, in: CGRect(x: 0, y: 0, width: width, height: height))
        return stride(from: 0, to: bytes.count, by: 4).contains { index in
            check(ScreenColour(r: Int(bytes[index]), g: Int(bytes[index + 1]), b: Int(bytes[index + 2])))
        }
    }

    // MARK: Profile

    func testTheCommentIsEditedAndThePictureRemoved() {
        let app = openMore(",profile,profile-avatar")
        expectAppears(app.element(containing: "Playing late, always up for a chat."))
        tapWhenReady(app.element("more-edit-profile"))
        expectAppears(app.element("Your name is set per server"))
        let save = app.buttons["profile-save"]
        expectAppears(save)
        XCTAssertFalse(save.isEnabled, "nothing to save yet")
        expectAppears(app.element("61 of 5000 characters"))

        replaceText(in: app.element("Comment"), with: "Up for a chat")
        expectAppears(app.element("13 of 5000 characters"))
        tapWhenReady(app.element("profile-photo"))
        tapWhenReady(app.element("avatar-remove"))
        expectGone(app.element("avatar-remove"))
        XCTAssertTrue(save.isEnabled)
        save.tapCenter()

        expectAppears(app.element("more-edit-profile"))
        expectAppears(app.element(containing: "Up for a chat"))
        // The picture is gone on the server: the sheet no longer offers to remove one.
        tapWhenReady(app.element("more-edit-profile"))
        tapWhenReady(app.element("profile-photo"))
        expectAppears(app.element("avatar-library"))
        XCTAssertFalse(app.element("avatar-remove").exists)
    }

    func testACommentOverTheLimitCannotBeSaved() {
        let app = openMore(",profile-short-limit")
        tapWhenReady(app.element("more-edit-profile"))
        let save = app.buttons["profile-save"]
        let field = app.element("Comment")
        typeText("Hello there", into: field)
        let fits = app.element("11 of 20 characters")
        expectAppears(fits)
        XCTAssertFalse(anyPixel(of: fits, isRed), "the counter is not red while the comment fits")
        XCTAssertTrue(save.isEnabled)

        replaceText(in: field, with: "Hello there, everyone else")
        let over = app.element("26 of 20 characters")
        expectAppears(over)
        expectAppears(app.element(containing: "Can't save: your comment is too long for this server"))
        XCTAssertTrue(anyPixel(of: over, isRed), "the counter is red")
        XCTAssertFalse(save.isEnabled)
    }

    func testAFailedSaveSaysSoAndGoingBackDropsTheComment() {
        let app = openMore(",profile-save-fails")
        tapWhenReady(app.element("more-edit-profile"))
        let save = app.buttons["profile-save"]
        typeText("Hello there", into: app.element("Comment"))
        XCTAssertTrue(save.isEnabled)
        save.tapCenter()
        expectAppears(app.element("Your profile could not be saved. Please try again."))
        XCTAssertTrue(app.element("profile-save").exists, "the editor stays open")
        // Going back drops the comment that was not saved.
        tapWhenReady(app.element("Back"))
        expectAppears(app.element("more-edit-profile"))
        XCTAssertFalse(app.element(containing: "Hello there").exists)
    }

    // MARK: Themes

    /// With System the app follows the phone's own dark mode, live, also after Dark or Light was
    /// chosen and System chosen again.
    func testSystemFollowsALightAndADarkPhoneAndComesBackToItAfterDark() {
        setPhone(.light)
        let app = openThemes(phone: "system")
        expectSelected(app.element("themes-mode-system"))
        awaitScreenColour(atNormalized: background) { !self.isDark($0) }

        setPhone(.dark)
        awaitScreenColour(atNormalized: background, isDark)

        tapWhenReady(app.element("themes-mode-light"))
        expectSelected(app.element("themes-mode-light"))
        awaitScreenColour(atNormalized: background) { !self.isDark($0) }

        tapWhenReady(app.element("themes-mode-dark"))
        expectSelected(app.element("themes-mode-dark"))
        setPhone(.light)
        // Dark stays dark whatever the phone says; a moment for a wrong change to show.
        Thread.sleep(forTimeInterval: 1)
        awaitScreenColour(atNormalized: background, isDark)

        tapWhenReady(app.element("themes-mode-system"))
        expectSelected(app.element("themes-mode-system"))
        awaitScreenColour(atNormalized: background) { !self.isDark($0) }
        setPhone(.dark)
        awaitScreenColour(atNormalized: background, isDark)
    }

    func testTheAppearanceIsChosenAndAPastedThemeApplied() {
        let app = openThemes()
        expectSelected(app.element("themes-mode-system"))
        expectSelected(app.element("themes-tile-preset:mumcord"))
        awaitScreenColour(atNormalized: background, isDark)

        tapWhenReady(app.element("themes-mode-light"))
        expectSelected(app.element("themes-mode-light"))
        awaitScreenColour(atNormalized: background) { !self.isDark($0) }

        tapWhenReady(app.element("themes-mode-dark"))
        expectSelected(app.element("themes-mode-dark"))
        awaitScreenColour(atNormalized: background, isDark)
        // Mumcord's background is violet, Deep Ocean's blue-green.
        awaitScreenColour(atNormalized: background) { $0.r > $0.g }
        tapWhenReady(app.element("themes-tile-preset:ocean"))
        expectSelected(app.element("themes-tile-preset:ocean"))
        awaitScreenColour(atNormalized: background) { $0.g > $0.r && self.isDark($0) }

        tapWhenReady(app.element("themes-paste"))
        let field = app.element("Theme message or code")
        typeText("not a theme", into: field)
        expectAppears(app.element(containing: "This isn't a valid Mumcord theme."))
        XCTAssertFalse(app.element("themes-apply").exists)
        replaceText(in: field, with: "Mumcord theme mct1.fake")
        expectAppears(app.element("themes-preview"))
        expectAppears(app.element(containing: "Imported"))
        let apply = app.element("themes-apply")
        reveal(apply, in: app)
        apply.tapCenter()

        expectGone(app.element("themes-preview"))
        expectAppears(app.element("YOUR THEMES"))
        expectSelected(app.element("themes-tile-custom:1"))
        expectAppears(app.element("Imported, theme, selected"))
    }

    func testAThemeIsSharedAndAnOwnOneDeleted() {
        let app = openThemes(",own-theme")
        expectAppears(app.element("From the desktop, theme"))

        // A built-in theme can be shared, not deleted.
        longPress(app.element("themes-tile-preset:forest"))
        expectAppears(app.element("themes-share"))
        XCTAssertFalse(app.element("themes-delete").exists)
        tapWhenReady(app.element("themes-share"))
        expectGone(app.element("themes-share"))
        // The system's share sheet.
        expectAppears(app.otherElements["ActivityListView"], timeout: 15)
        app.otherElements["ActivityListView"].swipeDown(velocity: .fast)
        expectGone(app.otherElements["ActivityListView"])

        longPress(app.element("themes-tile-custom:1"))
        tapWhenReady(app.element("themes-delete"))
        expectAppears(app.element("Delete “From the desktop”?"))
        tapWhenReady(app.element("themes-delete-confirm"))
        expectGone(app.element("themes-tile-custom:1"))
        XCTAssertFalse(app.element("YOUR THEMES").exists)
    }

    func testAServerIsShownInTheThemePickedInItsForm() {
        let app = launchApp("servers,voice-server", theme: "dark")
        expectAppears(app.element("Connect to Nordlicht"))
        tapWhenReady(app.element("Edit"))
        tapWhenReady(app.element("Edit Nordlicht"))
        expectAppears(app.element("Edit server"))
        let themeRow = app.element("server-form-theme")
        reveal(themeRow, in: app)
        expectAppears(app.element(containing: "Use app theme"))
        themeRow.tapCenter()
        expectAppears(app.element("Theme for Nordlicht"))
        tapWhenReady(app.element("server-theme-option-preset:ocean"))
        expectGone(app.element("Theme for Nordlicht"))
        expectAppears(app.element(containing: "Deep Ocean"))
        let save = app.element("server-form-save")
        reveal(save, in: app)
        save.tapCenter()
        expectGone(app.element("Edit server"))
        tapWhenReady(app.element("Done"))

        // In the server list the app theme is shown, in Nordlicht its own.
        awaitScreenColour(atNormalized: background) { $0.r > $0.g }
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.row("person-row", containing: "Jonas"))
        awaitScreenColour(atNormalized: background) { $0.g > $0.r }
        tapWhenReady(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "More")).firstMatch)
        tapWhenReady(app.element("more-themes"))
        expectAppears(app.element("Nordlicht is shown in its own theme, Deep Ocean. You can change that in the server's settings."))
    }
}
