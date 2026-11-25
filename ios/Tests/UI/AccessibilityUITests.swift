// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

/// VoiceOver and large text in the running app against the fake engine (AC-5.33). What VoiceOver
/// reaches comes from the app's accessibility probe (`voiceOverElements`), which also knows the
/// headings and the sheets that are modal; XCUITest alone lists every view and no traits.
///
/// Every screen checked here reads its title first, as a heading; has no element that says
/// nothing and no button without a name; and passes Xcode's accessibility audit with these
/// checks: element descriptions, hit regions (44 pt), traits, actions and parent and child
/// elements. Left out, because they report what is right in this app as wrong:
///
/// - contrast: it measures the drawn pixels over the film grain and the glows of the backdrop and
///   reports white text on the dark screen; the colour pairs are checked numerically instead
///   (`DesignContrastTests`).
/// - Dynamic Type and clipped text: they look for the system's text styles, which the app does
///   not use (its own fonts scale with `@ScaledMetric`), and count the fixed line height of
///   single lines as clipping. Large text is checked by the screenshots at the largest size the
///   app follows (`ios-large-text-*`) and by the flow at that size below.
/// - element detection: it reports the initials of avatars and server tiles, which VoiceOver
///   skips on purpose because the row beside them says the name.
final class AccessibilityUITests: XCTestCase {
    /// The largest text size the app follows (`largestTextSize`, 235 %).
    static let largestTextSize = "UICTContentSizeCategoryAccessibilityL"

    private let auditTypes = XCUIAccessibilityAuditType.all
        .subtracting([.contrast, .dynamicType, .textClipped, .elementDetection])

    override func setUp() {
        continueAfterFailure = false
    }

    // MARK: Helpers

    /// Checks the screen as described above and returns what VoiceOver reaches on it.
    @discardableResult
    private func expectScreen(
        _ app: XCUIApplication,
        title: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws -> [VoiceOverElement] {
        let elements = voiceOverElements(in: app, file: file, line: line)
        let list = elements.map(\.description).joined(separator: "\n")
        XCTAssertEqual(elements.first?.label, title, "the title is read first:\n\(list)", file: file, line: line)
        XCTAssertEqual(elements.first?.heading, true, "the title is a heading:\n\(list)", file: file, line: line)
        let silent = elements.filter { $0.label.isEmpty && $0.value.isEmpty }
        XCTAssertEqual(silent, [], "elements that say nothing:\n\(list)", file: file, line: line)
        let unnamed = elements.filter { $0.button && $0.label.isEmpty }
        XCTAssertEqual(unnamed, [], "buttons without a name:\n\(list)", file: file, line: line)
        try app.performAccessibilityAudit(for: auditTypes)
        return elements
    }

    /// Checks an open sheet: its title is read first, and VoiceOver stays inside it.
    private func expectModalSheet(_ app: XCUIApplication, title: String, file: StaticString = #filePath, line: UInt = #line) {
        let elements = voiceOverElements(in: app, file: file, line: line)
        let list = elements.map(\.description).joined(separator: "\n")
        XCTAssertEqual(elements.first?.label, title, "the sheet's title is read first:\n\(list)", file: file, line: line)
        XCTAssertEqual(elements.first?.heading, true, "the sheet's title is a heading:\n\(list)", file: file, line: line)
        XCTAssertTrue(elements.allSatisfy(\.modal), "VoiceOver reaches the screen behind the sheet:\n\(list)", file: file, line: line)
    }

    private func launch(_ options: String, textSize: String? = nil, setupDone: Bool = true) -> XCUIApplication {
        let app = launchApp(options, setupDone: setupDone, textSize: textSize, accessibilityProbe: true)
        return app
    }

    /// Connected to Nordlicht through the server list.
    private func connect(_ options: String, textSize: String? = nil) -> XCUIApplication {
        let app = launch("servers," + options, textSize: textSize)
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.row("channel-row", containing: "Lobby"), timeout: 20)
        return app
    }

    /// The tab's label carries the unread count, so it is found by how it begins.
    private func tab(_ app: XCUIApplication, _ name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
    }

    private func element(_ label: String, in elements: [VoiceOverElement]) -> VoiceOverElement? {
        elements.first { $0.label == label }
    }

    // MARK: Screens

    func testTheScreensWithoutAConnectionReadTheirTitleFirst() throws {
        let app = launch("servers")
        expectAppears(app.element(containing: "5 online · 24 ms"))
        let list = try expectScreen(app, title: "Servers")
        XCTAssertEqual(element("Connect to Nordlicht", in: list)?.button, true)
        XCTAssertEqual(element("FAVOURITES", in: list)?.heading, true, "section headers are headings")

        tapWhenReady(app.element("server-add"))
        expectAppears(app.input("Address"))
        try expectScreen(app, title: "Add server")
        tapWhenReady(app.element("Back"))

        tapWhenReady(app.element("server-browse-public"))
        expectAppears(app.element("public-servers"))
        try expectScreen(app, title: "Public servers")
        tapWhenReady(app.element("Back to the server list"))

        tapWhenReady(app.element("server-settings"))
        expectAppears(app.element("more-settings"))
        try expectScreen(app, title: "More")
        tapWhenReady(app.element("more-identities"))
        expectAppears(app.element("identities-screen"))
        try expectScreen(app, title: "Identities")
        tapWhenReady(app.element("Back"))
        tapWhenReady(app.element("more-themes"))
        expectAppears(app.element("themes-mode-system"))
        try expectScreen(app, title: "Themes")
    }

    func testTheConnectedScreensReadTheirTitleFirst() throws {
        let app = connect("voice-server,chat,messages,activity")
        let channels = try expectScreen(app, title: "Nordlicht")
        XCTAssertEqual(element("Channel Lobby, 5 people, you are here", in: channels)?.selected, true)
        // The voice bar comes after the channels and before the tabs, as on the screen.
        let names = channels.map(\.label)
        if let lobby = names.firstIndex(of: "Channel AFK, 0 people"),
           let leave = names.firstIndex(of: "Leave server"),
           let tabs = names.firstIndex(of: "Channels") {
            XCTAssertLessThan(lobby, leave)
            XCTAssertLessThan(leave, tabs)
        } else {
            XCTFail("channels, voice bar or tabs missing:\n\(names.joined(separator: "\n"))")
        }

        openVoiceViewThroughTheBar(in: app)
        try expectScreen(app, title: "Lobby")
        tapWhenReady(app.element("voice-collapse"))

        tapWhenReady(app.element("Chat"))
        expectAppears(app.element("chat-screen"))
        try expectScreen(app, title: "# Lobby")

        tapWhenReady(tab(app, "Messages"))
        expectAppears(app.element("messages-screen"))
        try expectScreen(app, title: "Messages")

        tapWhenReady(tab(app, "More"))
        expectAppears(app.element("more-settings"))
        try expectScreen(app, title: "More")
        tapWhenReady(app.element("more-activity"))
        expectAppears(app.element("Back to More"))
        try expectScreen(app, title: "Activity")
        tapWhenReady(app.element("Back to More"))
        tapWhenReady(app.element("more-settings"))
        expectAppears(app.element("settings-talk-mode"))
        try expectScreen(app, title: "Settings")
    }

    func testSheetsAreModalAndReadTheirTitleFirst() {
        let app = connect("voice-server,admin")
        tapWhenReady(app.row("person-row", containing: "Jonas"))
        expectAppears(app.element("person-sheet"))
        expectModalSheet(app, title: "Jonas")
        let sheet = voiceOverElements(in: app)
        XCTAssertNil(element("Leave server", in: sheet), "the voice bar behind the sheet")
        XCTAssertEqual(element("Volume for you", in: sheet)?.value, "100%", "the volume says its own value")

        let ban = app.element("moderation-ban")
        reveal(ban, in: app)
        ban.tapCenter()
        expectAppears(app.element(containing: "Ban Jonas"))
        expectModalSheet(app, title: "Ban Jonas")
    }

    func testMuteAndDeafenSayTheirStateInTheVoiceBarAndTheVoiceView() {
        let app = connect("voice-server")
        tapWhenReady(app.element("Mute microphone"))
        expectAppears(app.element("Unmute microphone"))
        var elements = voiceOverElements(in: app)
        XCTAssertEqual(element("Unmute microphone", in: elements)?.value, "Muted")
        XCTAssertEqual(element("Deafen", in: elements)?.value, "")

        tapWhenReady(app.element("Deafen"))
        expectAppears(app.element("Undeafen"))
        elements = voiceOverElements(in: app)
        XCTAssertEqual(element("Undeafen", in: elements)?.value, "Deafened")

        openVoiceViewThroughTheBar(in: app)
        elements = voiceOverElements(in: app)
        XCTAssertEqual(element("Unmute microphone", in: elements)?.value, "Muted")
        XCTAssertEqual(element("Undeafen", in: elements)?.value, "Deafened")
        XCTAssertEqual(element("Ben", in: elements)?.value, "Talking", "a talking person says so")
    }

    func testStreamsAndTheOwnCameraAreLabelled() throws {
        let app = connect("streams,camera-ask")
        let tile = app.element("stream-tile")
        expectAppears(tile)
        tile.tapCenter()
        expectAppears(app.element("stream-full-screen"))
        let full = voiceOverElements(in: app)
        let list = full.map(\.description).joined(separator: "\n")
        XCTAssertEqual(full.first { $0.heading }?.label, "Mira's screen", "the stream's title is a heading:\n\(list)")
        XCTAssertEqual(full.filter { $0.label.isEmpty && $0.value.isEmpty }, [], "elements that say nothing:\n\(list)")
        try app.performAccessibilityAudit(for: auditTypes)

        tapWhenReady(app.element("stream-collapse"))
        expectAppears(app.element("stream-mini-window"))
        try app.performAccessibilityAudit(for: auditTypes)
    }

    // MARK: Large text

    /// AC-5.33 at the largest text size the app follows: connect, join, mute and send a message,
    /// with every screen on the way passing the audit.
    func testAtTheLargestTextSizeConnectJoinMuteAndSendAMessage() throws {
        let app = launch("servers,voice-server,chat", textSize: Self.largestTextSize)
        expectAppears(app.element(containing: "5 online · 24 ms"))
        try expectScreen(app, title: "Servers")
        tapWhenReady(app.element("Connect to Nordlicht"))
        expectAppears(app.row("channel-row", containing: "Lobby"), timeout: 20)
        try expectScreen(app, title: "Nordlicht")

        tapWhenReady(app.row("channel-row", containing: "Gaming"))
        expectAppears(app.element(containingAll: ["Gaming", "you are here"]))

        tapWhenReady(app.element("Mute microphone"))
        expectAppears(app.element("Unmute microphone"))

        tapWhenReady(app.element("Chat"))
        expectAppears(app.element("chat-screen"))
        try expectScreen(app, title: "# Gaming")
        typeText("Hello from large text", into: app.descendants(matching: .any).matching(identifier: "chat-input").firstMatch)
        tapWhenReady(app.element("chat-send"))
        expectAppears(app.row("chat-message", containing: "Hello from large text"))
    }
}
