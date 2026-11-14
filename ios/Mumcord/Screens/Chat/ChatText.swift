// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import SwiftUI

/// The looks of the runs of a paragraph.
struct ChatTextStyles {
    /// The font of a run that is bold, italic, fixed-width, or any mix of them.
    let font: (_ bold: Bool, _ italic: Bool, _ code: Bool) -> Font
    let link: Color
    let mention: Color
    /// Behind an `@` mention of the user.
    let mentionBackground: Color
    /// Behind inline code.
    let codeBackground: Color
    /// The real host that is shown after a link whose text does not name it.
    let host: Color
}

/// A paragraph ready to draw, and the links its runs stand for. A link run does not carry its
/// address: it carries `chatLinkURL(index)`, which only `chatLink(for:in:)` turns back into
/// the link, so nothing in a message can be opened by the text view itself.
struct ChatText {
    let text: AttributedString
    let links: [SafeLink]
}

/// The scheme of the placeholder a link run carries. No app answers to it.
let chatLinkScheme = "mumcord-link"

/// The placeholder for the link at `index` of a paragraph.
func chatLinkURL(_ index: Int) -> URL? {
    URL(string: "\(chatLinkScheme):\(index)")
}

/// The link of a paragraph that a tapped placeholder stands for. Nil for every other address,
/// whatever it is, and for a number that is not a link of this paragraph.
func chatLink(for url: URL, in links: [SafeLink]) -> SafeLink? {
    let prefix = chatLinkScheme + ":"
    let text = url.absoluteString
    guard text.hasPrefix(prefix) else { return nil }
    let digits = text.dropFirst(prefix.count)
    guard !digits.isEmpty, digits.allSatisfy({ $0.isASCII && $0.isNumber }),
          let index = Int(digits), links.indices.contains(index)
    else { return nil }
    return links[index]
}

/// The runs of a paragraph as styled plain text. Nothing in a run is interpreted: its
/// characters are appended as they are, so text that looks like markup stays text. A link
/// becomes a range with a placeholder that the view reports when tapped and does nothing else;
/// it is never opened from here. When the shown text does not name the host the link goes to,
/// the host is written after it.
func chatText(_ spans: [Span], styles: ChatTextStyles) -> ChatText {
    var text = AttributedString()
    var links: [SafeLink] = []
    for span in spans {
        // From a plain string: this initializer takes every character literally.
        var run = AttributedString(span.text)
        run.font = styles.font(span.bold, span.italic, span.code)
        if span.underline { run.swiftUI.underlineStyle = Text.LineStyle.single }
        if span.strikethrough { run.swiftUI.strikethroughStyle = Text.LineStyle.single }
        if span.code { run.backgroundColor = styles.codeBackground }
        if let link = span.link {
            run.link = chatLinkURL(links.count)
            run.foregroundColor = styles.link
            run.swiftUI.underlineStyle = Text.LineStyle.single
            links.append(link)
            text += run
            if link.showsHost(span.text) {
                var host = AttributedString(" (\(link.host))")
                host.foregroundColor = styles.host
                text += host
            }
        } else {
            if span.mention {
                run.font = styles.font(true, span.italic, span.code)
                run.foregroundColor = styles.mention
                run.backgroundColor = styles.mentionBackground
            }
            text += run
        }
    }
    return ChatText(text: text, links: links)
}

private extension SafeLink {
    /// True when the reader cannot tell from `text` where the link goes.
    func showsHost(_ text: String) -> Bool {
        misleading || text.range(of: host, options: .caseInsensitive) == nil
    }
}

extension Array where Element == Span {
    /// The pictures the links of a paragraph point to (image and GIF addresses), each once.
    func inlineMedia() -> [SafeLink] {
        var seen = Set<String>()
        return compactMap(\.link).filter { link in
            (link.kind == .image || link.kind == .gif) && seen.insert(link.url).inserted
        }
    }

    /// The links of a paragraph, each address once.
    func links() -> [SafeLink] {
        var seen = Set<String>()
        return compactMap(\.link).filter { seen.insert($0.url).inserted }
    }
}

extension MessageContent {
    /// The text of a message in one line, for the reply bar and screen readers.
    func plainText() -> String {
        blocks.compactMap { block -> String? in
            switch block {
            case .paragraph(let spans), .quote(let spans): spans.map(\.text).joined()
            case .code(let text): text
            case .image: nil
            }
        }
        .joined(separator: " ")
        .replacingOccurrences(of: "\n", with: " ")
        .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Every link of the message, each address once, for the actions of a screen reader.
    func links() -> [SafeLink] {
        var seen = Set<String>()
        return blocks.flatMap { block -> [SafeLink] in
            switch block {
            case .paragraph(let spans), .quote(let spans): spans.links()
            case .code, .image: []
            }
        }
        .filter { seen.insert($0.url).inserted }
    }
}

/// The address as the system browser may be given it: http or https with a host. The engine
/// checked it already; anything else is not handed on.
func browserURL(_ address: String) -> URL? {
    guard let url = URL(string: address), let scheme = url.scheme?.lowercased(),
          scheme == "http" || scheme == "https", url.host?.isEmpty == false
    else { return nil }
    return url
}
