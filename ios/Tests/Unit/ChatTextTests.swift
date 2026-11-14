// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import SwiftUI
import XCTest

@testable import Mumcord

/// One run of a rendered paragraph, with the attributes the tests look at.
private struct ShownRun {
    let text: String
    let link: URL?
    let foreground: Color?
    let background: Color?
    let underlined: Bool
    let struckThrough: Bool
}

/// Remembers which fonts a paragraph asked for; fonts themselves cannot be told apart reliably.
private final class FontRequests {
    var flags: [[Bool]] = []
}

/// The runs of a message paragraph as styled text. The engine sends no markup at all, so the
/// one thing that must never happen here is that a run's characters are read as markup or as an
/// address the system would open.
final class ChatTextTests: XCTestCase {
    private let styles = ChatTextStyles(
        font: { _, _, _ in .body },
        link: .blue,
        mention: .red,
        mentionBackground: .yellow,
        codeBackground: .gray,
        host: .green
    )

    private func render(_ spans: Span...) -> ChatText {
        chatText(spans, styles: styles)
    }

    private func styled(_ text: String, _ change: (inout Span) -> Void) -> Span {
        var run = span(text)
        change(&run)
        return run
    }

    private func linked(_ text: String, _ link: SafeLink) -> Span {
        styled(text) { $0.link = link }
    }

    private func safeLink(_ url: String, _ host: String, misleading: Bool = false, kind: LinkKind = .web) -> SafeLink {
        SafeLink(url: url, host: host, kind: kind, misleading: misleading)
    }

    private func plain(_ text: ChatText) -> String {
        String(text.text.characters)
    }

    private func runs(_ text: ChatText) -> [ShownRun] {
        text.text.runs.map { run in
            ShownRun(
                text: String(text.text[run.range].characters),
                link: run.link,
                foreground: run.swiftUI.foregroundColor,
                background: run.swiftUI.backgroundColor,
                underlined: run.swiftUI.underlineStyle != nil,
                struckThrough: run.swiftUI.strikethroughStyle != nil
            )
        }
    }

    private func linkRuns(_ text: ChatText) -> [ShownRun] {
        runs(text).filter { $0.link != nil }
    }

    /// Compares scalar by scalar: `==` on strings would also accept another spelling of the
    /// same characters.
    private func assertSameCharacters(_ shown: String, _ expected: String, _ message: String, line: UInt = #line) {
        XCTAssertEqual(Array(shown.unicodeScalars), Array(expected.unicodeScalars), message, line: line)
    }

    // MARK: Plain text and styles

    func testRunsAreJoinedInOrderAsPlainText() {
        let text = render(span("Hello "), span("world\nnext line"))
        XCTAssertEqual(plain(text), "Hello world\nnext line")
        XCTAssertTrue(text.links.isEmpty)
    }

    func testAnEmptyParagraphIsEmptyText() {
        let text = chatText([], styles: styles)
        XCTAssertEqual(plain(text), "")
        XCTAssertTrue(text.links.isEmpty)
    }

    func testEveryRunAsksForTheFontOfItsStyle() {
        let requests = FontRequests()
        let recording = ChatTextStyles(
            font: { bold, italic, code in
                requests.flags.append([bold, italic, code])
                return .body
            },
            link: .blue,
            mention: .red,
            mentionBackground: .yellow,
            codeBackground: .gray,
            host: .green
        )
        _ = chatText(
            [
                styled("bold") { $0.bold = true },
                styled("italic") { $0.italic = true },
                span("plain"),
                styled("code") { $0.code = true },
                styled("all") {
                    $0.bold = true
                    $0.italic = true
                    $0.code = true
                },
            ],
            styles: recording
        )
        XCTAssertEqual(requests.flags, [
            [true, false, false],
            [false, true, false],
            [false, false, false],
            [false, false, true],
            [true, true, true],
        ])
    }

    func testUnderlineStrikethroughAndCodeBecomeAttributesOfTheirRun() throws {
        let text = render(
            span("plain "),
            styled("under") { $0.underline = true },
            styled("struck") { $0.strikethrough = true },
            styled("both") {
                $0.underline = true
                $0.strikethrough = true
            },
            styled("code") { $0.code = true }
        )
        XCTAssertEqual(plain(text), "plain understruckbothcode")
        let shown = runs(text)
        let byText = Dictionary(shown.map { ($0.text, $0) }, uniquingKeysWith: { first, _ in first })

        let plainRun = try XCTUnwrap(byText["plain "])
        XCTAssertFalse(plainRun.underlined)
        XCTAssertFalse(plainRun.struckThrough)
        XCTAssertNil(plainRun.background)
        XCTAssertNil(plainRun.foreground)

        let under = try XCTUnwrap(byText["under"])
        XCTAssertTrue(under.underlined)
        XCTAssertFalse(under.struckThrough)

        let struck = try XCTUnwrap(byText["struck"])
        XCTAssertFalse(struck.underlined)
        XCTAssertTrue(struck.struckThrough)

        let both = try XCTUnwrap(byText["both"])
        XCTAssertTrue(both.underlined)
        XCTAssertTrue(both.struckThrough)

        let code = try XCTUnwrap(byText["code"])
        XCTAssertEqual(code.background, Color.gray)
        XCTAssertFalse(code.underlined)
        XCTAssertFalse(code.struckThrough)
        XCTAssertTrue(shown.allSatisfy { $0.link == nil })
    }

    func testAMentionOfTheUserIsMarkedOverExactlyItsCharacters() throws {
        let text = render(styled("@Lea") { $0.mention = true }, span(" can you host?"))
        XCTAssertEqual(plain(text), "@Lea can you host?")
        let shown = runs(text)
        XCTAssertEqual(shown.map(\.text), ["@Lea", " can you host?"])
        let mention = try XCTUnwrap(shown.first)
        XCTAssertEqual(mention.background, Color.yellow)
        XCTAssertEqual(mention.foreground, Color.red)
        XCTAssertNil(mention.link)
        let rest = try XCTUnwrap(shown.last)
        XCTAssertNil(rest.background)
        XCTAssertNil(rest.foreground)
    }

    func testAMentionAsksForABoldFont() {
        let requests = FontRequests()
        let recording = ChatTextStyles(
            font: { bold, italic, code in
                requests.flags.append([bold, italic, code])
                return .body
            },
            link: .blue,
            mention: .red,
            mentionBackground: .yellow,
            codeBackground: .gray,
            host: .green
        )
        _ = chatText([styled("@Lea") { $0.mention = true }], styles: recording)
        XCTAssertEqual(requests.flags.last, [true, false, false])
    }

    // MARK: Links

    func testALinkRunCarriesAPlaceholderAndNeverItsAddress() throws {
        let target = safeLink("https://nordlicht.example/rules", "nordlicht.example")
        let text = render(span("Rules: "), linked("nordlicht.example/rules", target))
        XCTAssertEqual(plain(text), "Rules: nordlicht.example/rules")
        XCTAssertEqual(text.links, [target])

        let shown = runs(text)
        XCTAssertEqual(shown.map(\.text), ["Rules: ", "nordlicht.example/rules"])
        let link = try XCTUnwrap(shown.last)
        let placeholder = try XCTUnwrap(chatLinkURL(0))
        XCTAssertEqual(link.link, placeholder)
        XCTAssertEqual(link.link?.absoluteString, "mumcord-link:0")
        XCTAssertEqual(link.link?.scheme, chatLinkScheme)
        XCTAssertEqual(link.foreground, Color.blue)
        XCTAssertTrue(link.underlined)
        XCTAssertNil(shown.first?.link)

        // Only the paragraph's own list turns the placeholder back into the link.
        XCTAssertEqual(chatLink(for: placeholder, in: text.links), target)
    }

    func testNoLinkIsEverHandedToTheSystemByTheTextItself() throws {
        let first = safeLink("https://a.example/", "a.example")
        let second = safeLink("https://b.example/x", "b.example")
        let text = render(
            linked("a", first),
            span(" and "),
            linked("https://b.example/x", second),
            span(" javascript:alert(1) https://c.example www.d.example mumble://e")
        )
        XCTAssertEqual(text.links, [first, second])

        let links = linkRuns(text)
        XCTAssertEqual(links.map(\.text), ["a", "https://b.example/x"], "addresses in plain text stay text")
        XCTAssertEqual(links.map(\.link), [chatLinkURL(0), chatLinkURL(1)])

        for run in runs(text) {
            guard let carried = run.link else { continue }
            XCTAssertEqual(carried.scheme, chatLinkScheme)
            XCTAssertNil(carried.host)
            for address in ["a.example", "b.example", "c.example", "d.example", "http", "javascript", "mumble"] {
                XCTAssertFalse(carried.absoluteString.contains(address))
            }
        }

        let resolved = try links.map { run in try XCTUnwrap(chatLink(for: try XCTUnwrap(run.link), in: text.links)) }
        XCTAssertEqual(resolved, [first, second])
    }

    func testThePlaceholderOfALinkIsItsPositionInTheParagraph() throws {
        XCTAssertEqual(try XCTUnwrap(chatLinkURL(0)).absoluteString, "mumcord-link:0")
        XCTAssertEqual(try XCTUnwrap(chatLinkURL(12)).absoluteString, "mumcord-link:12")
        XCTAssertEqual(chatLinkScheme, "mumcord-link")
    }

    func testATappedPlaceholderOpensTheLinkItStandsFor() throws {
        let links = [
            safeLink("https://a.example/", "a.example"),
            safeLink("https://b.example/", "b.example"),
            safeLink("https://c.example/", "c.example"),
        ]
        for (index, link) in links.enumerated() {
            let placeholder = try XCTUnwrap(chatLinkURL(index))
            XCTAssertEqual(chatLink(for: placeholder, in: links), link)
        }
    }

    func testAnythingButAPlaceholderOfTheParagraphOpensNothing() throws {
        let links = [
            safeLink("https://a.example/", "a.example"),
            safeLink("https://b.example/", "b.example"),
        ]
        XCTAssertNil(chatLink(for: try XCTUnwrap(chatLinkURL(2)), in: links), "one past the last link")
        XCTAssertNil(chatLink(for: try XCTUnwrap(chatLinkURL(0)), in: []))

        let refused = [
            "mumcord-link:2",
            "mumcord-link:99",
            "mumcord-link:-1",
            "mumcord-link:+1",
            "mumcord-link:abc",
            "mumcord-link:",
            "mumcord-link:0x1",
            "mumcord-link:1/../0",
            "mumcord-link:0/",
            "mumcord-link:/0",
            "mumcord-link://0",
            "mumcord-link:0?x=1",
            "mumcord-link:0#a",
            "mumcord-link:1.0",
            "mumcord-link:1e0",
            "mumcord-link:99999999999999999999999999",
            "mumcord-link:https://a.example/",
            "MUMCORD-LINK:0",
            "mumcord-links:0",
            "mumcord:0",
            "http://a.example/",
            "https://a.example/",
            "http://0",
            "https://mumcord-link:0",
            "mumble://a.example",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "tel:112",
            "0",
        ]
        for address in refused {
            // What is not even an address cannot reach the handler at all.
            guard let url = URL(string: address) else { continue }
            XCTAssertNil(chatLink(for: url, in: links), address)
        }
    }

    func testALinkWhoseTextDoesNotNameItsHostShowsTheRealHost() throws {
        let target = safeLink("https://files.example/a", "files.example")
        let text = render(linked("click here", target))
        XCTAssertEqual(plain(text), "click here (files.example)")

        let shown = runs(text)
        XCTAssertEqual(shown.map(\.text), ["click here", " (files.example)"])
        XCTAssertEqual(shown.first?.link, chatLinkURL(0), "the added host is not part of the tap target")
        let host = try XCTUnwrap(shown.last)
        XCTAssertNil(host.link)
        XCTAssertEqual(host.foreground, Color.green)
        XCTAssertFalse(host.underlined)
        XCTAssertEqual(text.links, [target])
    }

    func testAMisleadingLinkAlwaysShowsTheRealHost() throws {
        let target = safeLink("https://bank.example.evil.test/login", "bank.example.evil.test", misleading: true)
        let text = render(linked("https://bank.example/login", target))
        XCTAssertEqual(plain(text), "https://bank.example/login (bank.example.evil.test)")
        XCTAssertEqual(linkRuns(text).map(\.text), ["https://bank.example/login"])
        XCTAssertNil(try XCTUnwrap(runs(text).last).link)
    }

    func testAMisleadingLinkShowsTheHostEvenWhenItsTextNamesIt() {
        let target = safeLink("https://nordlicht.example/rules", "nordlicht.example", misleading: true)
        let text = render(linked("nordlicht.example/rules", target))
        XCTAssertEqual(plain(text), "nordlicht.example/rules (nordlicht.example)")
        XCTAssertEqual(linkRuns(text).map(\.text), ["nordlicht.example/rules"])
    }

    func testALinkThatNamesItsHostIsShownAsWritten() {
        let target = safeLink("https://nordlicht.example/rules", "nordlicht.example")
        let text = render(linked("NORDLICHT.example/rules", target))
        XCTAssertEqual(plain(text), "NORDLICHT.example/rules")
        XCTAssertEqual(linkRuns(text).map(\.text), ["NORDLICHT.example/rules"])
    }

    // MARK: Text that looks like markup

    /// Text that looks like markup, in every flavour a renderer might interpret.
    private let adversarial = [
        "<script>alert('x')</script>",
        "<img src=x onerror=alert(1)>",
        "<a href=\"javascript:alert(1)\">click</a>",
        "<b>bold</b> <i>i</i> <u>u</u> <font color=red size=99>big</font>",
        "&lt;script&gt; &amp; &#x3C;svg/onload=alert(1)&#x3E; &nbsp;",
        "**bold** _italic_ `code` ~~strike~~ # heading",
        "[text](https://evil.example) ![img](https://evil.example/x.png)",
        "<iframe src=\"https://evil.example\"></iframe><style>*{display:none}</style>",
        "%s %d %1$s {0} ${name} \\n \\u202e",
        "<!-- comment --><![CDATA[x]]><?xml version=\"1.0\"?>",
        "intent://evil#Intent;scheme=http;end content://x file:///etc/passwd tel:112",
        "@everyone @Lea <@1234> :smile: \u{0000} \u{FFFD}",
    ]

    /// What the Markdown and HTML readers of the system would turn into styles or links.
    private let markup = [
        "**x**",
        "*x*",
        "__x__",
        "[a](http://b)",
        "<b>x</b>",
        "&amp;",
        "`code`",
        "# h",
        "https://evil.example",
        "<https://evil.example>",
        "www.evil.example",
        "mailto:lea@evil.example",
        "mumcord-link:0",
        "> quote\n- item\n1. item",
        "line one  \nline two\\\nline three",
        "^[x](abc: 1) \\*x\\*",
    ]

    func testTextThatLooksLikeMarkupIsShownCharacterForCharacter() {
        for (index, payload) in (adversarial + markup).enumerated() {
            let text = render(span(payload))
            XCTAssertEqual(plain(text), payload, "payload \(index)")
            assertSameCharacters(plain(text), payload, "payload \(index)")
            XCTAssertTrue(text.links.isEmpty, "payload \(index)")
            for run in runs(text) {
                XCTAssertNil(run.link, "payload \(index)")
                XCTAssertNil(run.foreground, "an unstyled run adds no visible style: payload \(index)")
                XCTAssertNil(run.background, "payload \(index)")
                XCTAssertFalse(run.underlined, "payload \(index)")
                XCTAssertFalse(run.struckThrough, "payload \(index)")
            }
        }
    }

    func testMarkupInALinksTextOrInAMentionStaysText() throws {
        let target = safeLink("https://safe.example/", "safe.example")
        for (index, payload) in (adversarial + markup).enumerated() {
            let linkedText = render(linked(payload, target))
            let links = linkRuns(linkedText)
            XCTAssertEqual(links.count, 1, "payload \(index)")
            let link = try XCTUnwrap(links.first)
            assertSameCharacters(link.text, payload, "payload \(index)")
            XCTAssertEqual(link.link, chatLinkURL(0), "payload \(index)")
            XCTAssertEqual(linkedText.links, [target], "payload \(index)")
            assertSameCharacters(plain(linkedText), payload + " (safe.example)", "payload \(index)")

            let mention = render(styled(payload) { $0.mention = true })
            assertSameCharacters(plain(mention), payload, "payload \(index)")
            XCTAssertTrue(mention.links.isEmpty, "payload \(index)")
            XCTAssertTrue(linkRuns(mention).isEmpty, "payload \(index)")
        }
    }

    func testMarkupInStyledRunsStaysText() {
        for (index, payload) in (adversarial + markup).enumerated() {
            let text = render(
                styled(payload) { $0.bold = true },
                styled(payload) { $0.code = true },
                styled(payload) { $0.underline = true }
            )
            assertSameCharacters(plain(text), payload + payload + payload, "payload \(index)")
            XCTAssertTrue(text.links.isEmpty, "payload \(index)")
            XCTAssertTrue(linkRuns(text).isEmpty, "payload \(index)")
        }
    }

    func testTheUiAddsNoInvisibleOrDirectionChangingCharacters() {
        let text = render(
            span("plain "),
            linked("link", safeLink("https://x.example/", "x.example")),
            styled("@Lea") { $0.mention = true },
            styled("code") { $0.code = true }
        )
        XCTAssertEqual(plain(text), "plain link (x.example)@Leacode")
        let invisible: [ClosedRange<UInt32>] = [0x200B...0x200F, 0x202A...0x202E, 0x2060...0x2069, 0xFEFF...0xFEFF]
        for scalar in plain(text).unicodeScalars {
            XCTAssertFalse(invisible.contains { $0.contains(scalar.value) })
        }
    }

    // MARK: Pictures, links and the one-line text of a message

    func testOnlyImageAndGifLinksAreShownInline() {
        let gif = safeLink("https://gifs.example/a.gif", "gifs.example", kind: .gif)
        let image = safeLink("https://img.example/a.png", "img.example", kind: .image)
        let web = safeLink("https://web.example/", "web.example")
        let spans = [linked("a", gif), linked("b", web), linked("c", image), linked("again", gif), span("text")]
        XCTAssertEqual(spans.inlineMedia(), [gif, image])
        XCTAssertEqual(spans.links(), [gif, web, image])
        XCTAssertTrue([span("text")].inlineMedia().isEmpty)
        XCTAssertTrue([span("text")].links().isEmpty)
    }

    func testTheLinksOfAMessageAreListedOnce() {
        let first = safeLink("https://a.example/", "a.example")
        let second = safeLink("https://b.example/", "b.example")
        let third = safeLink("https://c.example/", "c.example")
        let picture = safeLink("https://img.example/a.png", "img.example", kind: .image)
        let content = messageContent(blocks: [
            .paragraph(spans: [linked("a", first), span(" "), linked("b", second), linked("a again", first)]),
            .code(text: "https://d.example/"),
            .image(image: .remote(link: picture)),
            .quote(spans: [linked("b", second), linked("c", third)]),
        ])
        XCTAssertEqual(content.links(), [first, second, third])
        XCTAssertTrue(messageContent("https://plain.example").links().isEmpty)
    }

    func testTheOneLineTextOfAMessageLeavesPicturesOut() {
        let picture = safeLink("https://img.example/a.png", "img.example", kind: .image)
        let content = messageContent(blocks: [
            .paragraph(spans: [span("Look"), span(" here\nplease")]),
            .image(image: .remote(link: picture)),
            .code(text: "val x = 1"),
            .quote(spans: [span("quoted")]),
        ])
        XCTAssertEqual(content.plainText(), "Look here please val x = 1 quoted")
        XCTAssertEqual(messageContent(blocks: []).plainText(), "")
    }

    // MARK: The system browser

    func testOnlyWebAddressesWithAHostAreHandedToTheBrowser() throws {
        for address in ["http://example.com/a", "https://example.com", "https://nordlicht.example/rules?x=1#top", "HTTPS://example.com/x"] {
            let url = try XCTUnwrap(browserURL(address), address)
            XCTAssertEqual(url.absoluteString, address)
        }

        let refused = [
            "",
            "example.com",
            "//example.com/a",
            "/rules",
            "https://",
            "https:///rules",
            "http:example.com",
            "ftp://example.com/a",
            "mumble://example.com",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "tel:112",
            "mailto:lea@example.com",
            "data:text/html,x",
            "mumcord-link:0",
        ]
        for address in refused {
            XCTAssertNil(browserURL(address), address)
        }
    }
}
