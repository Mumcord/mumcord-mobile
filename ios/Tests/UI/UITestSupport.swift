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
    /// Starts the app against the fake engine with canned data (`AppDebugLaunch`).
    func launchApp(_ options: String, setupDone: Bool = true, theme: String = "dark") -> XCUIApplication {
        let app = XCUIApplication()
        var arguments = ["-mumcordFakeEngine", options]
        if setupDone { arguments += ["-mumcordSetupDone", "YES"] }
        arguments += ["-mumcordTheme", theme]
        app.launchArguments = arguments
        app.launch()
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
    /// effort: a keyboard without such a key (the number pad) stays.
    func dismissKeyboard(in app: XCUIApplication) {
        let keys = ["Done", "Return", "return", "Go", "Search", "search", "Next"]
        var attempts = 0
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
