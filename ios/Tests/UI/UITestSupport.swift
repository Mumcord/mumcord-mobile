// SPDX-License-Identifier: AGPL-3.0-only

import UIKit
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

/// The launch arguments that show the app in one of its languages, whatever the simulator's
/// language is: the tests find views by their English texts.
func languageArguments(_ language: String = "en") -> [String] {
    ["-AppleLanguages", "(\(language))"]
}

extension XCTestCase {
    /// Starts the app against the fake engine with canned data (`AppDebugLaunch`). `locale` is
    /// the region whose formats the app uses for dates, such as "en_US"; the simulator's when nil.
    /// `textSize` is a Dynamic Type size such as "UICTContentSizeCategoryAccessibilityL".
    /// `language` is the language of the texts, English unless a test asks for another one.
    /// `accessibilityProbe` adds the element that lists what VoiceOver reaches (see
    /// `voiceOverElements`).
    func launchApp(
        _ options: String,
        setupDone: Bool = true,
        theme: String = "dark",
        identitiesPreset: String? = nil,
        locale: String? = nil,
        textSize: String? = nil,
        language: String = "en",
        accessibilityProbe: Bool = false
    ) -> XCUIApplication {
        let app = XCUIApplication()
        var arguments = ["-mumcordFakeEngine", options] + languageArguments(language)
        if setupDone { arguments += ["-mumcordSetupDone", "YES"] }
        arguments += ["-mumcordTheme", theme]
        if let identitiesPreset { arguments += ["-mumcordIdentities", identitiesPreset] }
        if let locale { arguments += ["-AppleLocale", locale] }
        if let textSize { arguments += ["-UIPreferredContentSizeCategoryName", textSize] }
        if accessibilityProbe { arguments += ["-mumcordAccessibilityProbe", "YES"] }
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

    /// Waits until two screenshots in a row are the same, so sheets and bars that slide or fade
    /// in have settled; a fixed wait only hopes so.
    func awaitStillScreen(file: StaticString = #filePath, line: UInt = #line) {
        let deadline = Date().addingTimeInterval(10)
        var before = XCUIScreen.main.screenshot().pngRepresentation
        while Date() < deadline {
            Thread.sleep(forTimeInterval: 0.2)
            let now = XCUIScreen.main.screenshot().pngRepresentation
            if now == before { return }
            before = now
        }
        XCTFail("the screen does not settle", file: file, line: line)
    }

    /// Waits until the element exists, lies wholly inside the app's window and keeps its frame
    /// across two reads in a row, so a tap lands where the element is going to stay. A bar that
    /// slides in still moves for a moment after it exists; on a slow machine that moment is long.
    /// `isHittable` cannot tell: it reports the app's SwiftUI controls as covered (see
    /// `tapCenter`), so the window check stands in for it.
    func awaitSteady(
        _ element: XCUIElement,
        in app: XCUIApplication,
        timeout: TimeInterval = 20,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        expectAppears(element, timeout: timeout, file: file, line: line)
        let deadline = Date().addingTimeInterval(timeout)
        var before = element.frame
        while Date() < deadline {
            Thread.sleep(forTimeInterval: 0.1)
            let now = element.frame
            if !now.isEmpty, now == before, app.frame.contains(now) { return }
            before = now
        }
        XCTFail("does not hold still: \(element)", file: file, line: line)
    }

    /// Opens the voice view with a tap on the voice bar, the way a person does. The tap waits
    /// until the bar has stopped moving in; a tap during its entrance can miss it (seen on GitHub's
    /// macOS runners). If the voice view still does not open, the tap is tried once more.
    func openVoiceViewThroughTheBar(in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let bar = app.element("voice-bar")
        expectAppears(bar, timeout: 20, file: file, line: line)
        let open = bar.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "Voice connected"))
            .firstMatch
        let voiceView = app.element("voice-view")
        for timeout: TimeInterval in [5, 10] {
            if voiceView.exists { return }
            awaitSteady(open, in: app, file: file, line: line)
            open.tapCenter()
            if voiceView.waitForExistence(timeout: timeout) { return }
        }
        XCTFail("the voice bar did not open the voice view", file: file, line: line)
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
        let screenshot = uprightScreenshot()
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

    /// The screen as the user sees it. A turned iPad's screenshot holds its pixels as the screen
    /// stands upright and only notes the turn, which the pixel reads and the attachments' PNG
    /// files ignore; it is drawn upright here. An upright screenshot is returned as it is.
    func uprightScreenshot() -> UIImage {
        let image = XCUIScreen.main.screenshot().image
        guard image.imageOrientation != .up else { return image }
        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        return UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
    }

    /// Like `keepScreenshot`, but upright also on a turned iPad (see `uprightScreenshot`).
    func keepUprightScreenshot(named name: String) {
        guard let png = uprightScreenshot().pngData() else {
            XCTFail("no screenshot of \(name)")
            return
        }
        let screenshot = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        screenshot.name = name + ".png"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    /// The colour of the screen at a point given as a fraction of its width and height.
    func screenColour(atNormalized point: CGPoint) -> ScreenColour? {
        let screenshot = XCUIScreen.main.screenshot().image
        guard let image = screenshot.cgImage else { return nil }
        return pixel(image, x: CGFloat(image.width) * point.x, y: CGFloat(image.height) * point.y)
    }

    /// Waits until the screen's colour at the point passes `check`; the colours of a theme are
    /// drawn a moment after it was chosen.
    func awaitScreenColour(
        atNormalized point: CGPoint,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ check: (ScreenColour) -> Bool
    ) {
        let deadline = Date().addingTimeInterval(10)
        var last: ScreenColour?
        while Date() < deadline {
            last = screenColour(atNormalized: point)
            if let last, check(last) { return }
            Thread.sleep(forTimeInterval: 0.2)
        }
        XCTFail("the screen colour is \(String(describing: last))", file: file, line: line)
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

/// One element VoiceOver reaches, as the app's accessibility probe lists it.
struct VoiceOverElement: Equatable, CustomStringConvertible {
    let label: String
    let value: String
    let heading: Bool
    let button: Bool
    let selected: Bool
    /// Inside a sheet or another view that is modal for VoiceOver.
    let modal: Bool
    /// Where it is on the screen, in points.
    let frame: CGRect

    var description: String {
        let flags = [heading ? "heading" : nil, button ? "button" : nil, selected ? "selected" : nil, modal ? "modal" : nil]
        return "\(label) [\(value)] \(flags.compactMap { $0 }.joined(separator: ",")) \(frame)"
    }
}

extension XCTestCase {
    /// What VoiceOver reaches on the screen, in its order, read from the app's accessibility probe
    /// (an app started with `accessibilityProbe: true`). The probe updates a few times a second;
    /// this waits until two reads in a row agree, so a screen that is still being laid out is
    /// not read halfway.
    func voiceOverElements(in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) -> [VoiceOverElement] {
        let probe = app.element("accessibility-probe")
        expectAppears(probe, file: file, line: line)
        var before = ""
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            Thread.sleep(forTimeInterval: 0.4)
            let now = probe.value as? String ?? ""
            if !now.isEmpty, now == before { break }
            before = now
        }
        return before.split(separator: "\n").map { line in
            // The label may hold a "|" itself: the flags come first, the value and the frame last.
            var parts = line.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
            let box = (parts.count > 3 ? parts.removeLast() : "").split(separator: ",").compactMap { Double($0) }
            let flags = parts.isEmpty ? "" : parts.removeFirst()
            let value = parts.count > 1 ? parts.removeLast() : ""
            parts = [flags, parts.joined(separator: "|"), value]
            return VoiceOverElement(
                label: parts.count > 1 ? parts[1] : "",
                value: parts.count > 2 ? parts[2] : "",
                heading: flags.contains("H"),
                button: flags.contains("B"),
                selected: flags.contains("S"),
                modal: flags.contains("M"),
                frame: box.count == 4 ? CGRect(x: box[0], y: box[1], width: box[2], height: box[3]) : .zero
            )
        }
    }
}
