// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// Consecutive messages of one sender within this time share one avatar and name, as on desktop.
let groupGapMs: Int64 = 5 * 60 * 1000

/// The counter appears once this share of the text limit is used.
private let counterFrom = 0.9

/// The message a reply is being written to.
struct ReplyTarget: Equatable {
    let messageId: String
    let quote: ReplyQuote
}

/// What a message that is on its way, or did not get out, consists of.
enum PendingContent: Equatable {
    case text(String, reply: ReplyQuote?)
    /// A picture from the photo library or the camera, in its original encoding.
    case image(bytes: Data, mime: String)
    case gif(url: String)
}

enum SendFailure: Equatable {
    /// It did not get out; trying again may work.
    case notSent
    /// The server or the size rules refuse it; trying again cannot work.
    case refused
}

/// An own message that is being sent (`failure` nil) or could not be sent.
struct PendingSend: Equatable, Identifiable {
    let id: Int64
    let content: PendingContent
    var failure: SendFailure?
}

/// An image of a message, as the image loader is asked for it.
enum ChatImageSource: Hashable {
    /// Sent inside the message; `width` and `height` are known before it is loaded.
    case embedded(chat: ChatId, messageId: String, imageId: String, width: Int, height: Int)
    /// On the web. `gif` marks one that can be kept as a GIF favourite.
    case remote(url: String, gif: Bool)
}

enum ChatSheetKind: Equatable {
    case gifs
    case imageSource
}

enum ChatNotice: Equatable {
    case gifSaved
    case gifNotSaved
    case imageUnreadable
}

/// Everything the chat screen remembers about the chat that is open. It is replaced when
/// another chat opens, except for what the user typed.
struct ChatLocal: Equatable {
    var chat: ChatId?
    /// Oldest first, every id once.
    var messages: [ChatMessage] = []
    var hasMore = false
    /// An older page was read, so the list says where the history begins.
    var pagedOlder = false
    /// The first page is being read.
    var loading = false
    var loadingOlder = false
    var loadFailed = false
    var draft = ""
    /// Where the cursor is in `draft`, in UTF-16 units; the `@` autocomplete looks at the word
    /// before it.
    var cursor = 0
    var reply: ReplyTarget?
    var mentions: [MentionCandidate] = []
    var pending: [PendingSend] = []
    var limits: ChatLimits?
    /// The newest message is on screen.
    var atBottom = true
    /// The chat is on screen: its tab is selected and the app is in front.
    var visible = false
    /// Messages that arrived while the user was reading further up.
    var newBelow = 0
    /// Counts up whenever the list has to jump to the newest message.
    var scrollToken = 0
    var sheet: ChatSheetKind?
    var gifs: [GifFavourite] = []
    /// A link whose text names another address, waiting for the user's decision.
    var linkWarning: SafeLink?
    /// An address to hand to the system browser.
    var urlToOpen: String?
    var viewer: ChatImageSource?
    var notice: ChatNotice?
}

extension ChatLocal {
    /// The first page of a chat arrived.
    func loaded(_ page: ChatPage) -> ChatLocal {
        var next = self
        next.messages = merge(page.messages, messages)
        next.hasMore = page.hasMore
        next.loading = false
        next.loadFailed = false
        next.scrollToken += 1
        return next
    }

    /// An older page arrived; it goes in front of what is there.
    func prepended(_ page: ChatPage) -> ChatLocal {
        var next = self
        next.messages = merge(page.messages, messages)
        next.hasMore = page.hasMore
        next.loadingOlder = false
        next.pagedOlder = true
        return next
    }

    /// A message of somebody else arrived. It is appended; the list follows it only when the
    /// user is looking at the newest message, otherwise it is counted for the "New messages"
    /// pill.
    func received(_ message: ChatMessage) -> ChatLocal {
        if message.chat != chat || messages.contains(where: { $0.id == message.id }) { return self }
        let following = atBottom && visible
        var next = self
        next.messages.append(message)
        if following {
            next.scrollToken += 1
        } else if !message.blocked {
            next.newBelow += 1
        }
        return next
    }

    /// The user wants an own message sent: it is shown at once and the list jumps to it.
    func sending(_ send: PendingSend) -> ChatLocal {
        var next = self
        next.pending.append(send)
        next.scrollToken += 1
        next.newBelow = 0
        return next
    }

    /// An own message got out: it takes the place of its pending row.
    func sent(_ pendingId: Int64, _ message: ChatMessage) -> ChatLocal {
        var next = self
        next.pending.removeAll { $0.id == pendingId }
        next.messages = merge(messages, [message])
        next.scrollToken += 1
        return next
    }

    func sendFailed(_ pendingId: Int64, _ failure: SendFailure) -> ChatLocal {
        var next = self
        next.pending = pending.map { send in
            var changed = send
            if send.id == pendingId { changed.failure = failure }
            return changed
        }
        return next
    }

    /// The user scrolled; reaching the newest message puts the pill away.
    func scrolled(atBottom: Bool) -> ChatLocal {
        var next = self
        next.atBottom = atBottom
        if atBottom { next.newBelow = 0 }
        return next
    }
}

/// `older` followed by `newer`, every id once; the first occurrence wins.
private func merge(_ older: [ChatMessage], _ newer: [ChatMessage]) -> [ChatMessage] {
    var seen = Set<String>()
    return (older + newer).filter { seen.insert($0.id).inserted }
}

// MARK: Rows

/// A day of the calendar, without a time.
struct ChatDay: Hashable {
    let year: Int
    let month: Int
    let day: Int

    init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    /// The day `date` falls on in the calendar's time zone.
    init(_ date: Date, calendar: Calendar) {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        self.init(year: parts.year ?? 0, month: parts.month ?? 0, day: parts.day ?? 0)
    }

    /// The day before this one.
    func previous(calendar: Calendar) -> ChatDay? {
        guard let start = date(calendar: calendar),
              let before = calendar.date(byAdding: .day, value: -1, to: start)
        else { return nil }
        return ChatDay(before, calendar: calendar)
    }

    /// Noon of this day, which no daylight saving change moves to another day.
    func date(calendar: Calendar) -> Date? {
        calendar.date(from: DateComponents(year: year, month: month, day: day, hour: 12))
    }
}

/// One row of the chat list. `id` is stable, so the list keeps its place when rows are added.
enum ChatRow: Equatable, Identifiable {
    case day(ChatDay)
    /// `showHeader` is false for a message that continues the group of the one above it.
    case message(ChatMessage, showHeader: Bool)
    case pending(PendingSend, showHeader: Bool)
    case hint(UnsupportedHint)

    var id: String {
        switch self {
        case .day(let day): "day-\(day.year)-\(day.month)-\(day.day)"
        case .message(let message, _): "message-\(message.id)"
        case .pending(let send, _): "pending-\(send.id)"
        case .hint(let hint): "hint-\(hint.id)"
        }
    }
}

/// The rows of a chat, oldest first: a separator before the first message of each day, the
/// messages (those of blocked people are left out), the own messages still on their way, and
/// the calm hints for things this app cannot show.
func chatRows(
    messages: [ChatMessage],
    pending: [PendingSend],
    hints: [UnsupportedHint],
    calendar: Calendar
) -> [ChatRow] {
    var rows: [ChatRow] = []
    var previous: ChatMessage?
    for message in messages where !message.blocked {
        let day = message.day(calendar)
        let newDay = previous.map { $0.day(calendar) != day } ?? true
        if newDay { rows.append(.day(day)) }
        var grouped = false
        if let previous, !newDay {
            grouped = previous.sameSender(message) && message.sentAtMs - previous.sentAtMs <= groupGapMs
        }
        rows.append(.message(message, showHeader: !grouped))
        previous = message
    }
    for (index, send) in pending.enumerated() {
        rows.append(.pending(send, showHeader: index == 0 && previous?.own != true))
    }
    rows += hints.map(ChatRow.hint)
    return rows
}

private extension ChatMessage {
    func day(_ calendar: Calendar) -> ChatDay {
        ChatDay(Date(timeIntervalSince1970: Double(sentAtMs) / 1000), calendar: calendar)
    }

    func sameSender(_ other: ChatMessage) -> Bool {
        own == other.own && senderName == other.senderName && senderCertHash == other.senderCertHash
    }
}

/// How a day separator names its day.
enum DayLabel: Equatable {
    case today
    case yesterday
    case date(ChatDay)
}

func dayLabel(_ day: ChatDay, today: ChatDay, calendar: Calendar) -> DayLabel {
    if day == today { return .today }
    if day == today.previous(calendar: calendar) { return .yesterday }
    return .date(day)
}

// MARK: Writing

private let atSign: UInt16 = 0x40

private func isWhitespace(_ unit: UInt16) -> Bool {
    Unicode.Scalar(unit).map { $0.properties.isWhitespace } ?? false
}

/// What was typed after the `@` the cursor is in, or nil when the cursor is not in a mention.
/// A mention starts at the beginning of the text or after a blank, so mail addresses do not
/// open the list. `cursor` counts UTF-16 units.
func mentionQuery(_ text: String, cursor: Int) -> String? {
    let units = Array(text.utf16)
    let end = min(max(cursor, 0), units.count)
    var start = end
    while start > 0, !isWhitespace(units[start - 1]), units[start - 1] != atSign {
        start -= 1
    }
    if start == 0 || units[start - 1] != atSign { return nil }
    let at = start - 1
    if at > 0, !isWhitespace(units[at - 1]) { return nil }
    return String(decoding: units[start..<end], as: UTF16.self)
}

/// `text` with the mention under the cursor completed to `@name ` and the new cursor position.
func insertMention(_ text: String, cursor: Int, name: String) -> (text: String, cursor: Int) {
    let units = Array(text.utf16)
    let end = min(max(cursor, 0), units.count)
    guard let query = mentionQuery(text, cursor: end) else { return (text, end) }
    let at = end - query.utf16.count - 1
    let completed = String(decoding: units[..<at], as: UTF16.self) + "@" + name + " "
    return (completed + String(decoding: units[end...], as: UTF16.self), completed.utf16.count)
}

/// Where the cursor is after the user changed the field from `old` to `new`: behind the part
/// that changed. The field does not tell where its cursor is, but an edit happens at the cursor.
func cursorAfterEdit(old: String, new: String) -> Int {
    let before = Array(old.utf16)
    let after = Array(new.utf16)
    var prefix = 0
    while prefix < before.count, prefix < after.count, before[prefix] == after[prefix] {
        prefix += 1
    }
    var suffix = 0
    while suffix < before.count - prefix, suffix < after.count - prefix,
          before[before.count - 1 - suffix] == after[after.count - 1 - suffix] {
        suffix += 1
    }
    return after.count - suffix
}

/// How many characters are left, shown once the text gets close to the server's limit;
/// negative when it is over. Nil while there is room, or when the server has no limit.
func charactersLeft(_ text: String, limits: ChatLimits?) -> Int? {
    guard let limit = limits.map({ Int($0.text) }), limit > 0 else { return nil }
    let length = text.unicodeScalars.count
    return Double(length) >= Double(limit) * counterFrom ? limit - length : nil
}

func canSend(_ text: String, limits: ChatLimits?) -> Bool {
    if text.allSatisfy(\.isWhitespace) { return false }
    let limit = limits.map { Int($0.text) } ?? 0
    return limit <= 0 || text.unicodeScalars.count <= limit
}

// MARK: The screen

/// The sheet over the chat. At most one at a time.
enum ChatSheet: Equatable {
    case gifs([GifFavourite])
    case imageSource
    case linkWarning(SafeLink)
}

/// The chat screen as a value.
struct ChatUiState: Equatable {
    let chat: ChatId
    /// The channel's name; untrusted text.
    let title: String
    let people: Int
    let serverName: String
    /// The user's own name, for the messages that are still on their way.
    let selfName: String
    let rows: [ChatRow]
    let loading: Bool
    let loadingOlder: Bool
    let loadFailed: Bool
    /// Older messages can still be read from the phone's history.
    let hasMore: Bool
    /// The oldest message kept on the phone is in the list, after reading back.
    let historyStart: Bool
    let draft: String
    let charactersLeft: Int?
    let canSend: Bool
    let reply: ReplyTarget?
    let mentions: [MentionCandidate]
    let newMessages: Int
    let scrollToken: Int
    let sheet: ChatSheet?
    let viewer: ChatImageSource?
    let urlToOpen: String?
    let notice: ChatNotice?

    /// Nothing was ever written here, and nothing is on its way.
    var empty: Bool { !loading && !loadFailed && rows.isEmpty }
}

/// The chat of the channel the user is in, or nil while no server is shown or the user's
/// channel is unknown.
func currentChannelChat(_ session: SessionState) -> ChatId? {
    guard session.showsServer, let channelId = session.server?.selfChannelId else { return nil }
    return .channel(channelId: channelId)
}

/// The Chat tab: the chat of the channel the user is in. Nil while no server is shown or the
/// user's channel is unknown.
func chatUiState(session: SessionState, local: ChatLocal, serverName: String, calendar: Calendar) -> ChatUiState? {
    guard let server = session.server, let chat = currentChannelChat(session), local.chat == chat,
          let channelId = server.selfChannelId
    else { return nil }
    let sheet: ChatSheet?
    if let link = local.linkWarning {
        sheet = .linkWarning(link)
    } else {
        switch local.sheet {
        case .gifs: sheet = .gifs(local.gifs)
        case .imageSource: sheet = .imageSource
        case nil: sheet = nil
        }
    }
    return ChatUiState(
        chat: chat,
        title: server.channels[channelId]?.name ?? "",
        people: server.people.values.filter { $0.channelId == channelId }.count,
        serverName: serverName,
        selfName: server.people[server.selfSession]?.name ?? "",
        rows: chatRows(messages: local.messages, pending: local.pending, hints: session.hints, calendar: calendar),
        loading: local.loading,
        loadingOlder: local.loadingOlder,
        loadFailed: local.loadFailed,
        hasMore: local.hasMore,
        historyStart: local.pagedOlder && !local.hasMore,
        draft: local.draft,
        charactersLeft: charactersLeft(local.draft, limits: local.limits),
        canSend: canSend(local.draft, limits: local.limits),
        reply: local.reply,
        mentions: local.mentions,
        newMessages: local.newBelow,
        scrollToken: local.scrollToken,
        sheet: sheet,
        viewer: local.viewer,
        urlToOpen: local.urlToOpen,
        notice: local.notice
    )
}

/// The number on the Chat tab: unread messages of the current channel's chat, and nothing while
/// that tab is open.
func chatTabBadge(unread: [ChatId: Int], session: SessionState, chatTabOpen: Bool) -> Int {
    if chatTabOpen { return 0 }
    guard let chat = currentChannelChat(session) else { return 0 }
    return unread[chat] ?? 0
}
