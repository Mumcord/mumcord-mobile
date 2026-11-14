// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import Foundation
import MumcordBridge
import UIKit

// Short builders for chat values and the conversation of mockup `07-channel-chat`, for the
// fake engine, the unit tests and the UI tests.

func span(_ text: String) -> Span {
    Span(text: text, bold: false, italic: false, underline: false, strikethrough: false, code: false, link: nil, mention: false)
}

func messageContent(_ text: String = "Hello") -> MessageContent {
    messageContent(blocks: [.paragraph(spans: [span(text)])])
}

func messageContent(blocks: [ContentBlock]) -> MessageContent {
    MessageContent(reply: nil, blocks: blocks, truncated: false)
}

func chatMessage(
    id: String = "m1",
    chat: ChatId = .channel(channelId: 0),
    senderName: String = "Alice",
    senderSession: UInt32? = nil,
    own: Bool = false,
    sentAtMs: Int64 = 0,
    content: MessageContent = messageContent()
) -> ChatMessage {
    ChatMessage(
        id: id,
        chat: chat,
        senderName: senderName,
        senderSession: senderSession,
        senderCertHash: nil,
        own: own,
        sentAtMs: sentAtMs,
        blocked: false,
        mentionsMe: false,
        content: content,
        quote: ReplyQuote(name: senderName, snippet: "")
    )
}

/// The chat of the Lobby as in mockup `07-channel-chat`, with the pictures the fake engine
/// answers with.
enum ChatMockup {
    static let lobby = ChatId.channel(channelId: Mockup.lobby)
    static let anna: UInt32 = 1
    static let mira: UInt32 = 4
    static let mapMessageId = "m2"
    static let mapImageId = "map"
    static let mapSize = (width: 400, height: 225)

    /// Anna's message that mentions the user; the reply of the mockup answers it.
    static func annaAsks(at day: Date = Date(), calendar: Calendar = .current) -> ChatMessage {
        var message = chatMessage(
            id: "m4",
            chat: lobby,
            senderName: "Anna",
            senderSession: anna,
            sentAtMs: time(18, 48, day, calendar),
            content: messageContent(blocks: [
                .paragraph(spans: [mention("@Lea"), span(" can you host the first round?")]),
            ])
        )
        message.mentionsMe = true
        message.quote = ReplyQuote(name: "Anna", snippet: "can you host the first round?")
        return message
    }

    /// The messages of the mockup, written in the evening of `day`.
    static func messages(at day: Date = Date(), calendar: Calendar = .current) -> [ChatMessage] {
        let asks = annaAsks(at: day, calendar: calendar)
        var answer = chatMessage(
            id: "m5",
            chat: lobby,
            senderName: "Lea",
            senderSession: Mockup.lea,
            own: true,
            sentAtMs: time(18, 49, day, calendar),
            content: messageContent("Sure, give me five minutes.")
        )
        answer.content.reply = asks.quote
        return [
            chatMessage(
                id: "m1",
                chat: lobby,
                senderName: "Anna",
                senderSession: anna,
                sentAtMs: time(18, 42, day, calendar),
                content: messageContent("Evening all! Ready for tonight's session?")
            ),
            chatMessage(
                id: mapMessageId,
                chat: lobby,
                senderName: "Ben",
                senderSession: Mockup.ben,
                sentAtMs: time(18, 44, day, calendar),
                content: messageContent(blocks: [
                    .paragraph(spans: [span("Always. Here is the map we picked:")]),
                    .image(image: .embedded(
                        imageId: mapImageId,
                        mime: "image/png",
                        width: UInt32(mapSize.width),
                        height: UInt32(mapSize.height),
                        byteLen: 1000,
                        animated: false
                    )),
                ])
            ),
            chatMessage(
                id: "m3",
                chat: lobby,
                senderName: "Mira",
                senderSession: mira,
                sentAtMs: time(18, 46, day, calendar),
                content: messageContent(blocks: [
                    .paragraph(spans: [span("Rules are here: "), link("nordlicht.example/rules", rulesLink)]),
                ])
            ),
            asks,
            answer,
        ]
    }

    static let rulesLink = SafeLink(url: "https://nordlicht.example/rules", host: "nordlicht.example", kind: .web, misleading: false)

    /// A link whose text names another address than the one it opens.
    static let misleadingLink = SafeLink(
        url: "https://nordlicht.evil.example/rules",
        host: "nordlicht.evil.example",
        kind: .web,
        misleading: true
    )

    static let mentionNames = [
        MentionCandidate(session: anna, name: "Anna"),
        MentionCandidate(session: Mockup.ben, name: "Ben"),
        MentionCandidate(session: mira, name: "Mira"),
        MentionCandidate(session: Mockup.jonas, name: "Jonas"),
    ]

    /// The pictures of the mockups are colour gradients; the fake engine sends the same ones.
    private static let gradients: [[UInt32]] = [
        [0x3B2F8FFF, 0xD0607FFF],
        [0x0E6F78FF, 0x7A3FA0FF],
        [0x7A3FA0FF, 0xE0905AFF],
        [0xD0607FFF, 0x3B2F8FFF],
        [0x2F6F4FFF, 0x3B2F8FFF],
        [0xE0905AFF, 0x7A3FA0FF],
    ]

    static let gifFavourites = gradients.indices.map {
        GifFavourite(url: "https://gifs.example/\($0).gif", addedAtMs: 0, lastUsedAtMs: 0)
    }

    /// The picture inside Ben's message, by the key the fake engine keeps it under.
    static func chatImages() -> [String: ImageData] {
        let key = FakeEngine.imageKey(messageId: mapMessageId, imageId: mapImageId)
        let bytes = gradientPNG([0x3B2F8FFF, 0x7A3FA0FF, 0xD0607FFF], width: mapSize.width, height: mapSize.height)
        return [key: ImageData(bytes: bytes, mime: "image/png", width: UInt32(mapSize.width), height: UInt32(mapSize.height), animated: false)]
    }

    /// The pictures of the GIF favourites, by address.
    static func remoteImages() -> [String: ImageData] {
        var images: [String: ImageData] = [:]
        for (index, favourite) in gifFavourites.enumerated() {
            let bytes = gradientPNG(gradients[index], width: 400, height: 400)
            images[favourite.url] = ImageData(bytes: bytes, mime: "image/png", width: 400, height: 400, animated: false)
        }
        return images
    }

    private static func mention(_ text: String) -> Span {
        var run = span(text)
        run.mention = true
        return run
    }

    private static func link(_ text: String, _ link: SafeLink) -> Span {
        var run = span(text)
        run.link = link
        return run
    }

    private static func time(_ hour: Int, _ minute: Int, _ day: Date, _ calendar: Calendar) -> Int64 {
        let date = calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day) ?? day
        return Int64(date.timeIntervalSince1970 * 1000)
    }
}

/// A picture of `width` x `height` pixels with the colours (`0xRRGGBBAA`) running from its top
/// left to its bottom right corner, as PNG.
func gradientPNG(_ colours: [UInt32], width: Int, height: Int) -> Data {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    let size = CGSize(width: width, height: height)
    let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
        let cgColours = colours.map { colour in
            UIColor(
                red: CGFloat((colour >> 24) & 0xff) / 255,
                green: CGFloat((colour >> 16) & 0xff) / 255,
                blue: CGFloat((colour >> 8) & 0xff) / 255,
                alpha: 1
            ).cgColor
        }
        guard let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: cgColours as CFArray, locations: nil) else { return }
        context.cgContext.drawLinearGradient(
            gradient,
            start: .zero,
            end: CGPoint(x: size.width, y: size.height),
            options: []
        )
    }
    return image.pngData() ?? Data()
}
#endif
