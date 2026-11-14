// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI
import UIKit

private let mentionTint = 0.28
private let quoteEdge: CGFloat = 3
private let holdSeconds = 0.45

private extension View {
    /// Holding the view starts `action`, with the short tap of the phone that confirms a hold.
    /// It runs next to the other gestures, so it never keeps the list from scrolling or a link
    /// from being tapped.
    func onHold(_ action: @escaping () -> Void) -> some View {
        simultaneousGesture(
            LongPressGesture(minimumDuration: holdSeconds).onEnded { _ in
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                action()
            }
        )
    }
}

/// One message of somebody (or an own one that is out). Holding its text starts a reply.
/// A screen reader gets the message as one stop: who, when and what, with replying, opening
/// its pictures and its links as actions.
struct MessageItem: View {
    let message: ChatMessage
    let showHeader: Bool
    let actions: ChatController
    let loader: ChatImageLoader
    let clock: ChatClock

    var body: some View {
        let highlighted = message.mentionsMe && !message.own
        let time = clock.time(message.sentAtMs)
        MumcordMessage(
            // The name is untrusted text and shown as plain characters.
            author: message.senderName,
            time: time,
            initials: message.senderName.initials(1),
            colorSlot: avatarSlot(message.senderName),
            showHeader: showHeader,
            highlighted: highlighted
        ) {
            MessageBody(message: message, actions: actions, loader: loader)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(message.spokenText(time: time))
        .accessibilityValue(highlighted ? Strings.chatMentionsYou : "")
        .accessibilityActions {
            // The titles are given as plain characters: a host name is untrusted text.
            Button(action: { actions.startReply(message) }, label: { Text(verbatim: Strings.chatReplyAction) })
            ForEach(message.imageSources(), id: \.self) { source in
                Button(action: { actions.openViewer(source) }, label: { Text(verbatim: Strings.chatImageOpenAction) })
                if case .remote(let url, true) = source {
                    Button(action: { actions.addGif(url) }, label: { Text(verbatim: Strings.chatGifAddAction) })
                }
            }
            // A link whose text names another address still asks first.
            ForEach(message.content.links(), id: \.url) { link in
                Button(action: { actions.tapLink(link) }, label: { Text(verbatim: Strings.linkWarningOpen + ": " + link.host) })
            }
        }
        .accessibilityIdentifier("chat-message")
    }
}

extension ChatMessage {
    /// The pictures of the message in their order: those sent inside it and those its links
    /// point to.
    func imageSources() -> [ChatImageSource] {
        content.blocks.flatMap { block -> [ChatImageSource] in
            switch block {
            case .paragraph(let spans):
                spans.inlineMedia().map { ChatImageSource.remote(url: $0.url, gif: $0.kind == .gif) }
            case .image(let image):
                [imageSource(image)]
            case .quote, .code:
                []
            }
        }
    }

    func imageSource(_ image: ImageRef) -> ChatImageSource {
        switch image {
        case .embedded(let imageId, _, let width, let height, _, _):
            .embedded(chat: chat, messageId: id, imageId: imageId, width: Int(width), height: Int(height))
        case .remote(let link):
            .remote(url: link.url, gif: link.kind == .gif)
        }
    }

    /// The message as a screen reader says it: who, when, what it answers, its text and a word
    /// for each picture. All of it is untrusted text and only ever spoken.
    func spokenText(time: String) -> String {
        var parts = [senderName, time]
        if let reply = content.reply {
            parts.append(Strings.chatReplyingTo(reply.name))
        }
        let text = content.plainText()
        if !text.isEmpty { parts.append(text) }
        for source in imageSources() {
            if case .remote(_, true) = source {
                parts.append(Strings.chatGifWord)
            } else {
                parts.append(Strings.chatImageWord)
            }
        }
        if content.truncated { parts.append(Strings.chatTruncated) }
        return parts.joined(separator: ", ")
    }
}

private struct MessageBody: View {
    let message: ChatMessage
    let actions: ChatController
    let loader: ChatImageLoader
    @Environment(\.mumcordTheme) private var theme
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    var body: some View {
        let colors = theme.colors
        let reply = { actions.startReply(message) }
        if let quote = message.content.reply {
            // Both are untrusted text and shown as plain characters.
            MumcordReplyQuote(name: quote.name, text: quote.snippet.isEmpty ? Strings.chatImageWord : quote.snippet)
                .onHold(reply)
        }
        ForEach(Array(message.content.blocks.enumerated()), id: \.offset) { _, block in
            switch block {
            case .paragraph(let spans):
                if spans.contains(where: { !$0.text.isEmpty }) {
                    ChatParagraph(spans: spans, onLink: actions.tapLink)
                        .onHold(reply)
                }
                ForEach(spans.inlineMedia(), id: \.url) { link in
                    InlineImage(source: .remote(url: link.url, gif: link.kind == .gif), actions: actions, loader: loader)
                }
            case .quote(let spans):
                ChatParagraph(spans: spans, onLink: actions.tapLink, color: colors.textSecondary)
                    .padding(.leading, MumcordSpace.s3 + quoteEdge)
                    .overlay(alignment: .leading) { colors.borderStrong.frame(width: quoteEdge) }
                    .padding(.vertical, 2)
                    .onHold(reply)
            case .code(let text):
                ScrollView(.horizontal, showsIndicators: false) {
                    // Shown exactly as it was sent.
                    Text(verbatim: text)
                        .font(.system(size: TextToken.small.size * scale, design: .monospaced))
                        .foregroundStyle(colors.text)
                        .padding(.horizontal, MumcordSpace.s3)
                        .padding(.vertical, MumcordSpace.s2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(colors.bgElevated, in: RoundedRectangle(cornerRadius: MumcordRadius.md))
                .padding(.vertical, 2)
                .onHold(reply)
            case .image(let image):
                InlineImage(source: message.imageSource(image), actions: actions, loader: loader)
            }
        }
        if message.content.truncated {
            ParagraphText(Strings.chatTruncated, style: .caption, color: colors.textMuted)
                .padding(.top, 2)
        }
    }
}

/// A picture of a message. A tap opens it large; holding a GIF keeps it as a favourite.
private struct InlineImage: View {
    let source: ChatImageSource
    let actions: ChatController
    let loader: ChatImageLoader

    var body: some View {
        ChatInlineImage(source: source, loader: loader, onOpen: { actions.openViewer(source) }, onLongPress: addFavourite)
            .padding(.top, 6)
    }

    /// Set for a GIF that can be kept as a favourite.
    private var addFavourite: (() -> Void)? {
        guard case .remote(let url, true) = source else { return nil }
        return {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            actions.addGif(url)
        }
    }
}

/// A paragraph of runs as styled plain text. A tap on a link is reported to `onLink` and opens
/// nothing by itself.
private struct ChatParagraph: View {
    let spans: [Span]
    let onLink: (SafeLink) -> Void
    var color: Color?
    @Environment(\.mumcordTheme) private var theme
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    var body: some View {
        let colors = theme.colors
        let content = chatText(spans, styles: styles)
        ParagraphText(content.text, style: .body, color: color ?? colors.text)
            .tint(colors.accentText)
            // The only way a tap on a run leads anywhere: the placeholder of the run is looked
            // up among the links of this paragraph. Everything else ends here, handled.
            .environment(\.openURL, OpenURLAction { url in
                if let link = chatLink(for: url, in: content.links) { onLink(link) }
                return .handled
            })
    }

    private var styles: ChatTextStyles {
        let colors = theme.colors
        let scale = scale
        return ChatTextStyles(
            font: { bold, italic, code in chatFont(bold: bold, italic: italic, code: code, scale: scale) },
            link: colors.accentText,
            mention: colors.accentText,
            mentionBackground: colors.accent.opacity(mentionTint),
            codeBackground: colors.bgElevated,
            host: colors.textMuted
        )
    }
}

/// The font of a run of message text at the Dynamic Type factor `scale`.
private func chatFont(bold: Bool, italic: Bool, code: Bool, scale: CGFloat) -> Font {
    if code {
        var font = Font.system(size: TextToken.small.size * scale, design: .monospaced)
        if bold { font = font.bold() }
        if italic { font = font.italic() }
        return font
    }
    let font = MumcordFonts.font(bold ? TextToken.body.with(weight: 700) : .body, scale: scale)
    return Font(italic ? MumcordFonts.slanted(font) : font)
}

/// An own message on its way, or one that did not get out, with what can be done about it.
struct PendingItem: View {
    let send: PendingSend
    let showHeader: Bool
    let selfName: String
    let actions: ChatController
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        MumcordMessage(
            author: selfName,
            time: "",
            initials: selfName.initials(1),
            colorSlot: avatarSlot(selfName),
            showHeader: showHeader
        ) {
            if case .text(_, let reply?) = send.content {
                MumcordReplyQuote(name: reply.name, text: reply.snippet.isEmpty ? Strings.chatImageWord : reply.snippet)
            }
            ParagraphText(text, style: .body, color: colors.textSecondary)
            if let failure = send.failure {
                ParagraphText(failureText(failure), style: .caption, color: colors.danger)
                HStack(spacing: MumcordSpace.s2) {
                    if failure == .notSent {
                        MumcordButton(text: Strings.chatRetry, style: .secondary, small: true) { actions.retry(send.id) }
                            .accessibilityIdentifier("chat-retry")
                    }
                    MumcordButton(text: Strings.chatDiscard, style: .ghost, small: true) { actions.discard(send.id) }
                }
                .padding(.top, MumcordSpace.s1)
            } else {
                ParagraphText(Strings.chatSending, style: .caption, color: colors.textMuted)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat-pending")
    }

    /// What is being sent; the typed text is shown as plain characters.
    private var text: String {
        switch send.content {
        case .text(let text, _): text
        case .image: Strings.chatImageWord
        case .gif: Strings.chatGifWord
        }
    }

    private func failureText(_ failure: SendFailure) -> String {
        if failure == .notSent { return Strings.chatNotSent }
        if case .image = send.content { return Strings.chatImageRefused }
        return Strings.chatRefused
    }
}
