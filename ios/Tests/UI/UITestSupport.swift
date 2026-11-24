// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

// The UI tests run in a process of their own and know nothing of the app's code: they find
// views by the accessibility identifiers the app sets (the string values of the Android test
// tags) and by the English texts and VoiceOver labels of the screens.

extension XCUIApplication {
    /// The element with this accessibility identifier or exactly this label, of any type: the
    /// type of a SwiftUI view is not always the one a test would expect.
    func element(_ name: String) -> XCUIElement {
        descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == %@ OR label == %@", name, name))
            .firstMatch
    }

    /// The first element whose label contains the text. Rows combine their texts into one label.
    func element(containing text: String) -> XCUIElement {
        descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", text))
            .firstMatch
    }

    /// The first element with this identifier whose label contains the text, e.g. one row of a list.
    func row(_ identifier: String, containing text: String) -> XCUIElement {
        descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == %@ AND label CONTAINS %@", identifier, text))
            .firstMatch
    }

    /// The first element whose label contains all of the texts.
    func element(containingAll texts: [String]) -> XCUIElement {
        let predicates = texts.map { NSPredicate(format: "label CONTAINS %@", $0) }
        return descendants(matching: .any)
            .matching(NSCompoundPredicate(andPredicateWithSubpredicates: predicates))
            .firstMatch
    }

    /// The first text field, secure or not.
    var anyInput: XCUIElement {
        let plain = textFields.firstMatch
        return plain.exists ? plain : secureTextFields.firstMatch
    }

    /// The text field with this label, identifier or placeholder, secure or not.
    func input(_ name: String) -> XCUIElement {
        let predicate = NSPredicate(format: "label == %@ OR identifier == %@ OR placeholderValue == %@", name, name, name)
        let plain = textFields.matching(predicate).firstMatch
        return plain.exists ? plain : secureTextFields.matching(predicate).firstMatch
    }
}

extension XCTestCase {
    /// Starts the app against the fake engine with canned data (`AppDebugLaunch`). `locale` is
    /// the region whose formats the app uses for dates, such as "en_US"; the simulator's when nil.
    func launchApp(
        _ options: String,
        setupDone: Bool = true,
        theme: String = "dark",
        identitiesPreset: String? = nil,
        locale: String? = nil
    ) -> XCUIApplication {
        let app = XCUIApplication()
        var arguments = ["-mumcordFakeEngine", options]
        if setupDone { arguments += ["-mumcordSetupDone", "YES"] }
        arguments += ["-mumcordTheme", theme]
        if let identitiesPreset { arguments += ["-mumcordIdentities", identitiesPreset] }
        if let locale { arguments += ["-AppleLocale", locale] }
        app.launchArguments = arguments
        app.launch()
        // A cold simulator takes its time; nothing is tapped before the app is on screen.
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 120), "the app did not come on screen")
        return app
    }

    func expectAppears(
        _ element: XCUIElement,
        timeout: TimeInterval = 10,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "not found: \(element)", file: file, line: line)
    }

    func expectGone(
        _ element: XCUIElement,
        timeout: TimeInterval = 10,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: element)
        XCTAssertEqual(XCTWaiter().wait(for: [gone], timeout: timeout), .completed, "still there: \(element)", file: file, line: line)
    }

    /// Waits for the element and taps it.
    func tapWhenReady(
        _ element: XCUIElement,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        expectAppears(element, file: file, line: line)
        element.tapCenter()
    }

    /// Scrolls the screen until the element can be tapped, e.g. a button below the keyboard.
    func reveal(_ element: XCUIElement, in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        expectAppears(element, file: file, line: line)
        var swipes = 0
        while isBelowTheVisibleArea(element, in: app) && swipes < 6 {
            app.swipeUp()
            swipes += 1
        }
    }

    /// Waits for the field, taps it so it has the focus, and types.
    func typeText(_ text: String, into field: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        expectAppears(field, file: file, line: line)
        field.tapCenter()
        field.typeText(text)
    }

    /// Like `typeText`, but removes what the field holds first. Deleting a few keys too many is
    /// harmless, which covers a field that reports its placeholder as its value.
    func replaceText(in field: XCUIElement, with text: String, file: StaticString = #filePath, line: UInt = #line) {
        expectAppears(field, file: file, line: line)
        field.tapCenter()
        let current = (field.value as? String) ?? ""
        if !current.isEmpty {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count + 2))
        }
        field.typeText(text)
    }

    /// Puts the keyboard away with its own return key, which does not change what was typed. Best
    /// effort: a keyboard without such a key (the number pad) or with a key that acts (Go, Next)
    /// stays.
    func dismissKeyboard(in app: XCUIApplication) {
        let keys = ["Done", "done", "Return", "return", "Search", "search"]
        var attempts = 0
        // A fresh simulator explains swipe typing on top of the keys first.
        let tutorial = app.keyboards.buttons["Continue"]
        if tutorial.waitForExistence(timeout: 1) {
            tutorial.tap()
        }
        while app.keyboards.firstMatch.exists && attempts < 6 {
            guard let key = keys.map({ app.keyboards.buttons[$0] }).first(where: { $0.exists }) else { return }
            key.tap()
            attempts += 1
            // The keyboard needs a moment to slide away; Next only moves it to the next field.
            _ = app.keyboards.firstMatch.waitForNonExistence(timeout: 1)
        }
    }

    /// Kept in the result bundle also for a passing test, so CI can publish the picture.
    func keepScreenshot(of app: XCUIApplication, named name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}

extension XCTestCase {
    /// True while the element lies below the screen or under the keyboard.
    func isBelowTheVisibleArea(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        let keyboard = app.keyboards.firstMatch
        let limit = keyboard.exists ? keyboard.frame.minY : app.frame.maxY
        return element.frame.isEmpty || element.frame.maxY > limit
    }
}

extension XCUIElement {
    /// Taps the middle of the element with a real touch. `tap()` first asks the accessibility
    /// hit test whether the element is hittable, which reports the app's SwiftUI controls as
    /// covered although touches reach them.
    func tapCenter() {
        coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }
}

/// One pixel of a screenshot, 0 to 255 per channel.
struct ScreenColour {
    let r: Int
    let g: Int
    let b: Int
}

extension XCTestCase {
    /// Waits until the stream picture letterboxed in the element passes `check`, which gets the
    /// colours of the 16:9 picture on its middle line at a quarter and at three quarters of its
    /// width. Proves that the picture is drawn, where a fixed wait only hopes so.
    func awaitStreamPicture(
        in element: XCUIElement,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ check: (_ left: ScreenColour, _ right: ScreenColour) -> Bool
    ) {
        expectAppears(element, file: file, line: line)
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            if streamPictureShows(in: element.frame, check) { return }
            Thread.sleep(forTimeInterval: 0.3)
        }
        XCTFail("the stream picture is not drawn", file: file, line: line)
    }

    func streamPictureShows(in frame: CGRect, _ check: (_ left: ScreenColour, _ right: ScreenColour) -> Bool) -> Bool {
        let screenshot = XCUIScreen.main.screenshot().image
        guard let image = screenshot.cgImage, frame.width > 0, frame.height > 0 else { return false }
        let scale = CGFloat(image.width) / screenshot.size.width
        let width = min(frame.width, frame.height * 16 / 9)
        let left = frame.minX + (frame.width - width) / 2
        let y = frame.midY
        guard let first = pixel(image, x: (left + width * 0.25) * scale, y: y * scale),
              let second = pixel(image, x: (left + width * 0.75) * scale, y: y * scale)
        else { return false }
        return check(first, second)
    }

    private func pixel(_ image: CGImage, x: CGFloat, y: CGFloat) -> ScreenColour? {
        var bytes = [UInt8](repeating: 0, count: 4)
        guard let context = CGContext(
            data: &bytes,
            width: 1,
            height: 1,
            bitsPerComponent: 8,
            bytesPerRow: 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        // Draws the image so that the wanted pixel lands on the context's only one.
        context.draw(image, in: CGRect(
            x: -x.rounded(.down),
            y: y.rounded(.down) - CGFloat(image.height) + 1,
            width: CGFloat(image.width),
            height: CGFloat(image.height)
        ))
        return ScreenColour(r: Int(bytes[0]), g: Int(bytes[1]), b: Int(bytes[2]))
    }
}
