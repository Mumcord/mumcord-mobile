// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import Foundation
import MumcordBridge

/// The Lobby chats of the tablet mockups `21-tablet-channels-chat` and `22-tablet-stream`, for
/// the fake engine of the iPad screenshots. Every message carries the quote the bridge makes of
/// it, which the chat strip under a stream shows.
enum TabletMockup {
    private static let sunsetMessageId = "t2"
    private static let sunsetImageId = "sunset"
    private static let sunsetSize = (width: 400, height: 300)

    /// The conversation of mockup 21, written in the evening of `day`.
    static func conversation(at day: Date = Date(), calendar: Calendar = .current) -> [ChatMessage] {
        var sunset = message(sunsetMessageId, "Ben", Mockup.ben, 18, 4, "Always. Look at the sunset from my hike today:", day, calendar)
        sunset.content = messageContent(blocks: [
            .paragraph(spans: [span("Always. Look at the sunset from my hike today:")]),
            .image(image: .embedded(
                imageId: sunsetImageId,
                mime: "image/png",
                width: UInt32(sunsetSize.width),
                height: UInt32(sunsetSize.height),
                byteLen: 1000,
                animated: false
            )),
        ])
        var mention = span("@Lea")
        mention.mention = true
        var map = message("t3", "Mira", ChatMockup.mira, 18, 7, "can you pick the first map? I will share my screen in Gaming in a minute.", day, calendar)
        map.content = messageContent(blocks: [
            .paragraph(spans: [mention, span(" can you pick the first map? I will share my screen in Gaming in a minute.")]),
        ])
        map.mentionsMe = true
        var answer = message("t4", "Lea", Mockup.lea, 18, 8, "Sure, give me two minutes.", day, calendar)
        answer.own = true
        return [
            message("t1", "Anna", ChatMockup.anna, 18, 2, "Hey everyone, ready for game night?", day, calendar),
            sunset,
            map,
            answer,
        ]
    }

    /// The newest messages of mockup 22, shown in the strip under the stream.
    static func duringTheStream(at day: Date = Date(), calendar: Calendar = .current) -> [ChatMessage] {
        [
            message("u1", "Anna", ChatMockup.anna, 18, 20, "Wow, that boss fight looks hard", day, calendar),
            message("u2", "Ben", Mockup.ben, 18, 21, "Nice, the stream is crisp", day, calendar),
        ]
    }

    /// The picture inside Ben's message, a colour gradient like the mockup's.
    static func chatImages() -> [String: ImageData] {
        let key = FakeEngine.imageKey(messageId: sunsetMessageId, imageId: sunsetImageId)
        let bytes = gradientPNG([0x4A3A9AFF, 0x7A3FA0FF, 0xD0607FFF], width: sunsetSize.width, height: sunsetSize.height)
        return [key: ImageData(
            bytes: bytes,
            mime: "image/png",
            width: UInt32(sunsetSize.width),
            height: UInt32(sunsetSize.height),
            animated: false
        )]
    }

    private static func message(
        _ id: String,
        _ name: String,
        _ session: UInt32,
        _ hour: Int,
        _ minute: Int,
        _ text: String,
        _ day: Date,
        _ calendar: Calendar
    ) -> ChatMessage {
        let date = calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day) ?? day
        var message = chatMessage(
            id: id,
            chat: ChatMockup.lobby,
            senderName: name,
            senderSession: session,
            sentAtMs: Int64(date.timeIntervalSince1970 * 1000),
            content: messageContent(text)
        )
        message.quote = ReplyQuote(name: name, snippet: text)
        return message
    }
}
#endif
