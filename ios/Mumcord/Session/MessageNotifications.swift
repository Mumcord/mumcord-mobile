// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// The settings of message notifications. The one place they are read and written.
protocol MessageNotificationSettings: AnyObject, Sendable {
    /// "Show sender only": notifications name who wrote and leave the message out (FR-5.55).
    var senderOnly: Bool { get }

    /// The choice of the moment and every later one, in order.
    func senderOnlyStates() -> AsyncStream<Bool>

    func setSenderOnly(_ senderOnly: Bool)
}

/// Kept in the engine's phone settings (`notifySenderOnly`), next to all other settings.
///
/// The engine reports changed settings as an event that the state does not follow; this switch
/// is the only thing that changes the setting, so the value read at the start and the user's
/// own changes are all there is to know.
final class EngineMessageNotificationSettings: MessageNotificationSettings, @unchecked Sendable {
    private let store: SessionStore
    private let choice = Observed(false)
    private let lock = NSLock()
    // The user's choice is newer than what the engine is still being asked for.
    private var chosen = false

    init(store: SessionStore) {
        self.store = store
        Task.detached { [weak self] in
            guard let settings = await store.mobileSettings(), let self else { return }
            if !self.lock.withLock({ self.chosen }) {
                self.choice.set(settings.notifySenderOnly)
            }
        }
    }

    var senderOnly: Bool { choice.value }

    func senderOnlyStates() -> AsyncStream<Bool> { choice.states() }

    func setSenderOnly(_ senderOnly: Bool) {
        lock.withLock { chosen = true }
        choice.set(senderOnly)
        // A failure is logged by the store; the engine then keeps the earlier choice.
        Task.detached { [store] in
            _ = await store.updateMobileSettings { $0.notifySenderOnly = senderOnly }
        }
    }
}

/// Kept for the life of the process only: for screens drawn without an engine behind them, and tests.
final class InMemoryMessageNotificationSettings: MessageNotificationSettings, @unchecked Sendable {
    private let choice: Observed<Bool>

    init(senderOnly: Bool = false) {
        choice = Observed(senderOnly)
    }

    var senderOnly: Bool { choice.value }

    func senderOnlyStates() -> AsyncStream<Bool> { choice.states() }

    func setSenderOnly(_ senderOnly: Bool) {
        choice.set(senderOnly)
    }
}

private let keyChannel = "c:"
private let keyPerson = "p:"
private let keySession = "s:"

extension ChatId {
    /// The name of a chat's notification, which also tells a tap which chat to open.
    var notificationKey: String {
        switch self {
        case .channel(let channelId): "\(keyChannel)\(channelId)"
        case .person(let certHash): "\(keyPerson)\(certHash)"
        case .session(let session): "\(keySession)\(session)"
        }
    }
}

/// The chat a notification key names, or nil for anything else.
func chatFromNotificationKey(_ key: String) -> ChatId? {
    if key.hasPrefix(keyChannel) {
        return UInt32(key.dropFirst(keyChannel.count)).map { .channel(channelId: $0) }
    }
    if key.hasPrefix(keyPerson) {
        let hash = String(key.dropFirst(keyPerson.count))
        return hash.isEmpty ? nil : .person(certHash: hash)
    }
    if key.hasPrefix(keySession) {
        return UInt32(key.dropFirst(keySession.count)).map { .session(session: $0) }
    }
    return nil
}

/// What one conversation's notification says.
struct MessageNotice: Equatable {
    /// One message. `text` is nil when only the sender may be shown, or it has no words.
    struct Line: Equatable {
        let sender: String
        let text: String?
        let atMillis: Int64
    }

    /// Names the conversation: a later notice with the same key replaces this one.
    let key: String
    /// The channel's name when `group` is set; a private conversation is named by the sender
    /// of its lines.
    let title: String
    /// Several people write here (a channel); otherwise a conversation of two.
    let group: Bool
    /// The messages, oldest first.
    let lines: [Line]
}

/// Shows and removes the notifications for messages.
protocol MessageNotifier: AnyObject, Sendable {
    func show(_ notice: MessageNotice)

    /// Removes the notification of one conversation, if there is one.
    func cancel(_ key: String)

    /// Removes all message notifications, also those an earlier run of the app left behind.
    func cancelAll()
}

/// System notifications for private messages and mentions, only while connected (FR-5.54):
///
/// - The engine reports a message that is worth one; it leaves out own messages, blocked
///   people, messages held back by the private message rule and the chat that is on screen. A
///   chat that is not on screen gets its notification also while the app is in front.
/// - One notification per conversation, with its latest messages; with "Show sender only" the
///   text is left out.
/// - A conversation's notification goes away when the chat is opened or read, and all of them
///   when the connection ends. What an earlier run of the app left behind is removed at once.
///
/// It follows the session by itself once it has been created. Do Not Disturb and the focus
/// modes are the system's business: it holds notifications back by itself, and the app's badges
/// count either way.
final class MessageNotificationCenter: @unchecked Sendable {
    /// How many messages of a conversation its notification lists.
    static let maxLines = 6

    private let store: SessionStore
    private let notifier: MessageNotifier
    private let settings: MessageNotificationSettings
    private let permitted: @Sendable () -> Bool
    private let now: @Sendable () -> Int64
    private let lock = NSLock()
    /// The lines of every notification that is shown, by its key.
    private var shown: [String: [MessageNotice.Line]] = [:]
    private var lastUnread: [ChatId: Int] = [:]

    /// - Parameters:
    ///   - permitted: whether the phone lets the app show notifications right now.
    ///   - now: the time of the moment in milliseconds; a seam for tests.
    init(
        store: SessionStore,
        chats: ChatStore,
        notifier: MessageNotifier,
        settings: MessageNotificationSettings,
        permitted: @escaping @Sendable () -> Bool,
        now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.store = store
        self.notifier = notifier
        self.settings = settings
        self.permitted = permitted
        self.now = now
        let states = store.states()
        Task.detached { [weak self] in
            var connected: Bool?
            for await state in states {
                guard state.showsServer != connected else { continue }
                connected = state.showsServer
                if !state.showsServer { self?.clearAll() }
            }
        }
        let notifications = chats.notifications()
        Task.detached { [weak self] in
            for await notification in notifications {
                self?.notify(notification)
            }
        }
        let unreadStates = chats.unreadStates()
        Task.detached { [weak self] in
            for await unread in unreadStates {
                self?.onUnread(unread)
            }
        }
        let openChats = chats.openChatStates()
        Task.detached { [weak self] in
            for await chat in openChats {
                if let chat { self?.clear(chat) }
            }
        }
    }

    private func notify(_ notification: ChatNotification) {
        let session = store.state
        // Nothing after the connection ended, even for a message that was still on its way.
        guard session.showsServer, let server = session.server, permitted() else { return }
        let chat = notification.chat
        let key = chat.notificationKey
        let text = notification.preview.isEmpty || settings.senderOnly ? nil : notification.preview
        let line = MessageNotice.Line(sender: notification.senderName, text: text, atMillis: now())
        var channelName: String?
        if case .channel(let channelId) = chat {
            channelName = server.channels[channelId]?.name ?? ""
        }
        lock.lock()
        defer { lock.unlock() }
        let lines = Array(((shown[key] ?? []) + [line]).suffix(Self.maxLines))
        shown[key] = lines
        notifier.show(
            MessageNotice(
                key: key,
                title: channelName ?? notification.senderName,
                group: notification.kind == .mention,
                lines: lines
            )
        )
    }

    /// A chat whose unread count went back to nothing was read, here or on the chat's screen.
    private func onUnread(_ unread: [ChatId: Int]) {
        lock.lock()
        defer { lock.unlock() }
        for chat in lastUnread.keys where unread[chat] == nil {
            clearLocked(chat)
        }
        lastUnread = unread
    }

    private func clear(_ chat: ChatId) {
        lock.lock()
        defer { lock.unlock() }
        clearLocked(chat)
    }

    /// Call with the lock held.
    private func clearLocked(_ chat: ChatId) {
        let key = chat.notificationKey
        if shown.removeValue(forKey: key) != nil {
            notifier.cancel(key)
        }
    }

    private func clearAll() {
        lock.lock()
        defer { lock.unlock() }
        shown = [:]
        lastUnread = [:]
        notifier.cancelAll()
    }
}
