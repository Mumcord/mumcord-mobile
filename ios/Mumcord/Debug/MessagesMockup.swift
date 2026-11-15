// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// The notification permission without the system: it answers a request with "allowed".
final class FakeNotificationGateway: NotificationGateway, @unchecked Sendable {
    private let lock = NSLock()
    private var _granted: Bool
    private var _asked: Bool

    init(granted: Bool, asked: Bool) {
        _granted = granted
        _asked = asked
    }

    func granted() -> Bool {
        lock.withLock { _granted }
    }

    var asked: Bool {
        get { lock.withLock { _asked } }
        set { lock.withLock { _asked = newValue } }
    }

    func request(_ completion: @escaping @Sendable (Bool) -> Void) {
        lock.withLock { _granted = true }
        completion(true)
    }

    func refresh(_ completion: @escaping @Sendable () -> Void) {
        completion()
    }
}

/// The private conversations of mockup `09-messages`, for the fake engine.
enum MessagesMockup {
    static let anna = ChatId.person(certHash: "mock-anna")
    static let ben = ChatId.person(certHash: "mock-ben")
    static let mira = ChatId.person(certHash: "mock-mira")
    static let jonas = ChatId.person(certHash: "mock-jonas")

    static func fill(_ engine: FakeEngine, now: Date = Date(), calendar: Calendar = .current) {
        let yesterday = calendar.date(byAdding: .day, value: -1, to: now) ?? now
        let entries: [(ChatId, String, UInt32, String, Int64, UInt32)] = [
            (anna, "Anna", 1, "Joining Gaming later?", time(18, 51, now, calendar), 2),
            (ben, "Ben", Mockup.ben, "Thanks, got the map!", time(17, 20, now, calendar), 0),
            (mira, "Mira", 4, "Sent you the screenshot.", time(14, 3, now, calendar), 1),
            (jonas, "Jonas", Mockup.jonas, "See you tomorrow", time(21, 10, yesterday, calendar), 0),
        ]
        var conversations: [Conversation] = []
        var messages = engine.messages
        var unread = engine.unread
        for (index, entry) in entries.enumerated() {
            let (chat, name, session, text, sentAtMs, count) = entry
            let last = chatMessage(
                id: "pm\(index)",
                chat: chat,
                senderName: name,
                senderSession: session,
                sentAtMs: sentAtMs,
                content: messageContent(text)
            )
            conversations.append(
                Conversation(chat: chat, peerName: name, peerSession: session, last: last, unread: count)
            )
            messages[chat] = [last]
            if count > 0 { unread[chat] = count }
        }
        engine.conversations = conversations
        engine.messages = messages
        engine.unread = unread
        let held = chatMessage(
            id: "held1",
            chat: .session(session: 90),
            senderName: "Kai",
            senderSession: 90,
            sentAtMs: time(12, 30, now, calendar),
            content: messageContent("Hi, are you the one with the map?")
        )
        engine.requests = MessageRequests(
            incoming: [
                MessageRequest(
                    id: 1,
                    session: 90,
                    name: "Kai",
                    certHash: nil,
                    chatRequest: false,
                    note: nil,
                    messages: [held],
                    receivedAtMs: held.sentAtMs
                ),
            ],
            sent: []
        )
    }

    private static func time(_ hour: Int, _ minute: Int, _ day: Date, _ calendar: Calendar) -> Int64 {
        let date = calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day) ?? day
        return Int64(date.timeIntervalSince1970 * 1000)
    }
}
