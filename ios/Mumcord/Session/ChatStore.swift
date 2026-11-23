// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// Whether images and GIFs from the web may be loaded right now. The one place that decides;
/// the "Load images and GIFs" setting plugs in here.
typealias MediaPolicy = @Sendable () -> Bool

/// The chat of the connection: unread counts, arriving messages and the chat commands. It has
/// its own streams, apart from `SessionStore.state`, so chat traffic never redraws the channel
/// list. Channel chats and private conversations both go through it, and so do the list of
/// private conversations, the message requests and the blocked people.
///
/// There is one per `SessionStore`: it is the only reader of the store's chat events.
final class ChatStore: @unchecked Sendable {
    static let pageSize: UInt32 = 50
    static let mentionLimit: UInt32 = 8
    private static let incomingBuffer = 256

    private let session: SessionStore
    private let mediaPolicy: MediaPolicy
    private let lock = NSLock()
    private var counts: [ChatId: Int] = [:]
    private var unreadObservers: [UUID: AsyncStream<[ChatId: Int]>.Continuation] = [:]
    private var incomingObservers: [UUID: AsyncStream<ChatMessage>.Continuation] = [:]
    private var notificationObservers: [UUID: AsyncStream<ChatNotification>.Continuation] = [:]
    private let conversationList = Observed<[Conversation]>([])
    private let requestList = Observed(noRequests)
    private let blockedList = Observed<[BlockedPerson]>([])
    private let onScreen = Observed<ChatId?>(nil)
    // Several screens can show a chat (the Chat tab, a private conversation), but only one is
    // on screen at a time. Each keeps its own entry, so one that goes away cannot take the
    // open chat of the other with it. The entry that was set last is the one on screen.
    private var openChats: [(owner: UUID, chat: ChatId)] = []
    // The engine has no event for a changed conversation list; it is read again when asked
    // for here. One waiting element: several reasons in a row cost one read.
    private let conversationReads: AsyncStream<Void>.Continuation

    init(session: SessionStore, mediaPolicy: @escaping MediaPolicy = { true }) {
        self.session = session
        self.mediaPolicy = mediaPolicy
        let (reads, readQueue) = AsyncStream<Void>.makeStream(bufferingPolicy: .bufferingNewest(1))
        conversationReads = readQueue
        Task.detached { [weak self] in
            for await _ in reads {
                guard let self else { return }
                await self.readConversations()
            }
        }
        let events = session.chatEvents
        Task.detached { [weak self] in
            for await event in events {
                switch event {
                case .chatMessageReceived(let message):
                    self?.publish(message)
                    if !message.chat.isChannel { self?.refreshConversations() }
                case .chatNotification(let notification):
                    self?.publish(notification)
                case .messageRequestsChanged(let requests):
                    self?.requestList.set(requests)
                    // An accepted request turns its held messages into a conversation.
                    self?.refreshConversations()
                case .blockedPeopleChanged(let people):
                    self?.blockedList.set(people)
                case .chatUnreadChanged(let chat, let count):
                    self?.changeUnread { $0.withUnread(chat, Int(count)) }
                default:
                    break
                }
            }
        }
        // The counts that were there before this connection was looked at (history of an
        // earlier run); later changes arrive as events.
        let states = session.states()
        Task.detached { [weak self] in
            var shown = false
            for await state in states {
                guard state.showsServer != shown else { continue }
                shown = state.showsServer
                guard let self else { return }
                if shown {
                    await self.readAtStart()
                } else {
                    self.changeUnread { _ in [:] }
                    self.conversationList.set([])
                    self.requestList.set(noRequests)
                    self.blockedList.set([])
                }
            }
        }
    }

    /// What was there before this connection was looked at; later changes arrive as events.
    private func readAtStart() async {
        refreshConversations()
        if case .ok(let held) = await session.call("message requests", { try await $0.messageRequests() }) {
            requestList.update { $0.incoming.isEmpty && $0.sent.isEmpty ? held : $0 }
        }
        if case .ok(let blocked) = await session.call("blocked people", { try await $0.blockedPeople() }) {
            blockedList.update { $0.isEmpty ? blocked : $0 }
        }
        await readUnread()
    }

    private func readConversations() async {
        guard session.state.showsServer else { return }
        let list = await session.call("private conversations") { try await $0.privateConversations() }
        // A read that was still on its way when the connection ended shows nothing.
        if case .ok(let list) = list, session.state.showsServer {
            conversationList.set(list)
        }
    }

    private func readUnread() async {
        guard case .ok(let snapshot) = await session.call("chat unread", { $0.chatUnreadSnapshot() }) else { return }
        changeUnread { current in
            var merged: [ChatId: Int] = [:]
            for entry in snapshot where entry.count > 0 {
                merged[entry.chat] = Int(entry.count)
            }
            // An event that arrived in between is newer than the snapshot.
            return merged.merging(current) { _, newer in newer }
        }
    }

    // MARK: Unread counts and arriving messages

    /// Unread messages per chat; a chat without any is not in the map.
    var unread: [ChatId: Int] {
        lock.withLock { counts }
    }

    /// The current unread counts and every later change, in order.
    func unreadStates() -> AsyncStream<[ChatId: Int]> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            unreadObservers[id] = continuation
            continuation.yield(counts)
            lock.unlock()
            continuation.onTermination = { [weak self] _ in self?.removeObserver(id) }
        }
    }

    /// Messages of others as they arrive. Own messages are returned by the send commands.
    func incoming() -> AsyncStream<ChatMessage> {
        let id = UUID()
        return AsyncStream(bufferingPolicy: .bufferingNewest(Self.incomingBuffer)) { continuation in
            lock.lock()
            incomingObservers[id] = continuation
            lock.unlock()
            continuation.onTermination = { [weak self] _ in self?.removeObserver(id) }
        }
    }

    /// Messages worth a system notification: private messages and mentions in a chat that is
    /// not on screen.
    func notifications() -> AsyncStream<ChatNotification> {
        let id = UUID()
        return AsyncStream(bufferingPolicy: .bufferingNewest(Self.incomingBuffer)) { continuation in
            lock.lock()
            notificationObservers[id] = continuation
            lock.unlock()
            continuation.onTermination = { [weak self] _ in self?.removeObserver(id) }
        }
    }

    private func removeObserver(_ id: UUID) {
        lock.lock()
        unreadObservers[id] = nil
        incomingObservers[id] = nil
        notificationObservers[id] = nil
        lock.unlock()
    }

    private func changeUnread(_ change: ([ChatId: Int]) -> [ChatId: Int]) {
        lock.lock()
        defer { lock.unlock() }
        let next = change(counts)
        guard next != counts else { return }
        counts = next
        // Sent while the lock is held, so observers see the counts in order; it never waits.
        for observer in unreadObservers.values {
            observer.yield(next)
        }
    }

    private func publish(_ message: ChatMessage) {
        lock.lock()
        defer { lock.unlock() }
        for observer in incomingObservers.values {
            observer.yield(message)
        }
    }

    private func publish(_ notification: ChatNotification) {
        lock.lock()
        defer { lock.unlock() }
        for observer in notificationObservers.values {
            observer.yield(notification)
        }
    }

    // MARK: Commands

    func page(_ chat: ChatId, before: String?) async -> Outcome<ChatPage> {
        await session.call("chat page") { try await $0.chatPage(chat: chat, before: before, limit: Self.pageSize) }
    }

    func send(_ chat: ChatId, text: String, replyTo: ReplyQuote?) async -> Outcome<ChatMessage> {
        sent(to: chat, await session.call("send message") { try await $0.sendMessage(chat: chat, text: text, replyTo: replyTo) })
    }

    func sendImage(_ chat: ChatId, bytes: Data, mime: String) async -> Outcome<ChatMessage> {
        sent(to: chat, await session.call("send image") { try await $0.sendImage(chat: chat, bytes: bytes, mime: mime) })
    }

    func sendGif(_ chat: ChatId, url: String) async -> Outcome<ChatMessage> {
        sent(to: chat, await session.call("send gif") { try await $0.sendGifFavourite(chat: chat, url: url) })
    }

    /// A private message that got out is the conversation's newest one.
    private func sent(to chat: ChatId, _ outcome: Outcome<ChatMessage>) -> Outcome<ChatMessage> {
        if case .ok = outcome, !chat.isChannel { refreshConversations() }
        return outcome
    }

    /// The bytes of an image that was sent inside a message.
    func image(_ chat: ChatId, messageId: String, imageId: String) async -> ImageData? {
        let outcome = await session.call("chat image") {
            try await $0.chatImage(chat: chat, messageId: messageId, imageId: imageId)
        }
        return outcome.value
    }

    /// An image or GIF from the web, or nil when it cannot or may not be loaded.
    func remoteImage(_ url: String) async -> ImageData? {
        let allowed = mediaPolicy()
        let outcome = await session.call("remote image") { try await $0.loadRemoteImage(url: url, loadMedia: allowed) }
        return outcome.value ?? nil
    }

    func mentionCandidates(_ query: String) async -> [MentionCandidate] {
        let outcome = await session.call("mention candidates") {
            $0.mentionCandidates(query: query, limit: Self.mentionLimit)
        }
        return outcome.value ?? []
    }

    func limits() async -> ChatLimits? {
        await session.call("chat limits") { try await $0.chatLimits() }.value
    }

    func markRead(_ chat: ChatId) async {
        // A failure is logged by the store; the count then simply stays.
        _ = await session.call("mark chat read") { try await $0.markChatRead(chat: chat) }
    }

    /// `owner` (a screen that shows chats) has `chat` on screen in front of the user, or none.
    /// The engine and `openChat` get the one chat that is on screen.
    func setOpenChat(owner: UUID, chat: ChatId?) {
        lock.lock()
        openChats.removeAll { $0.owner == owner }
        if let chat { openChats.append((owner, chat)) }
        let open = openChats.last?.chat
        // Told while the lock is held, so two screens cannot report in the wrong order.
        onScreen.set(open)
        session.setOpenChat(open)
        lock.unlock()
    }

    /// The chat that is on screen in front of the user, or nil.
    var openChat: ChatId? { onScreen.value }

    /// The chat on screen now and every later change, in order.
    func openChatStates() -> AsyncStream<ChatId?> { onScreen.states() }

    // MARK: Private conversations, requests, blocking

    /// The stored private conversations of the connected server, newest first.
    var conversations: [Conversation] { conversationList.value }

    func conversationStates() -> AsyncStream<[Conversation]> { conversationList.states() }

    /// The held message requests and the chat requests the user sent; they last for the connection.
    var requests: MessageRequests { requestList.value }

    func requestStates() -> AsyncStream<MessageRequests> { requestList.states() }

    /// The people the user blocked with the identity of this connection.
    var blocked: [BlockedPerson] { blockedList.value }

    func blockedStates() -> AsyncStream<[BlockedPerson]> { blockedList.states() }

    /// Reads the list of private conversations again, soon.
    func refreshConversations() {
        conversationReads.yield()
    }

    /// The private chat with a person on the server, for opening it from the people list.
    func chatWith(_ session: UInt32) async -> Outcome<ChatId> {
        await self.session.call("chat with") { try $0.chatWith(session: session) }
    }

    /// Accepts a message request: its held messages become messages of the conversation.
    func acceptRequest(_ id: UInt64) async -> Outcome<Void> {
        await session.call("accept message request") { try await $0.acceptMessageRequest(id: id) }
    }

    /// Declines a message request. The other person is never told.
    func declineRequest(_ id: UInt64) async -> Outcome<Void> {
        await session.call("decline message request") { try await $0.declineMessageRequest(id: id) }
    }

    /// Asks another Mumcord user for a chat; fails with `.invalidInput` when their app cannot take it.
    func sendChatRequest(_ session: UInt32, note: String?) async -> Outcome<Void> {
        await self.session.call("send chat request") { try await $0.sendChatRequest(session: session, note: note) }
    }

    /// The blocked people as the engine has them right now.
    @discardableResult
    func readBlocked() async -> Outcome<[BlockedPerson]> {
        let outcome = await session.call("blocked people") { try await $0.blockedPeople() }
        if case .ok(let people) = outcome { blockedList.set(people) }
        return outcome
    }

    /// Blocks a person on the server. Nobody is told.
    func block(_ session: UInt32) async -> Outcome<Void> {
        await self.session.call("block person") { try await $0.blockPerson(session: session) }
    }

    /// Lifts a block by the id of its list entry.
    func unblock(_ id: Int64) async -> Outcome<Void> {
        await session.call("unblock person") { try await $0.unblockPerson(id: id) }
    }

    func gifFavourites() async -> [GifFavourite] {
        await session.call("gif favourites") { try await $0.gifFavourites() }.value ?? []
    }

    func addGifFavourite(_ url: String) async -> Outcome<Void> {
        await session.call("add gif favourite") { try await $0.addGifFavourite(url: url) }
    }

    func removeGifFavourite(_ url: String) async {
        // A failure is logged by the store; the next reading of the favourites shows the truth.
        _ = await session.call("remove gif favourite") { try await $0.removeGifFavourite(url: url) }
    }
}

private let noRequests = MessageRequests(incoming: [], sent: [])

/// A value that can be read from any thread and followed as a stream: the value of the moment
/// first, then every later one, in order.
final class Observed<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Value
    private var observers: [UUID: AsyncStream<Value>.Continuation] = [:]

    init(_ value: Value) {
        current = value
    }

    var value: Value {
        lock.withLock { current }
    }

    func set(_ value: Value) {
        update { _ in value }
    }

    func update(_ change: (Value) -> Value) {
        lock.lock()
        defer { lock.unlock() }
        current = change(current)
        // Sent while the lock is held, so observers see the values in order; it never waits.
        for observer in observers.values {
            observer.yield(current)
        }
    }

    func states() -> AsyncStream<Value> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            observers[id] = continuation
            continuation.yield(current)
            lock.unlock()
            continuation.onTermination = { [weak self] _ in self?.remove(id) }
        }
    }

    private func remove(_ id: UUID) {
        lock.lock()
        observers[id] = nil
        lock.unlock()
    }
}

extension ChatId {
    /// A channel's chat, not a private conversation.
    var isChannel: Bool {
        if case .channel = self { return true }
        return false
    }
}

extension Outcome {
    /// The result of a command that worked, or nil.
    var value: Value? {
        if case .ok(let value) = self { return value }
        return nil
    }
}

extension Dictionary where Key == ChatId, Value == Int {
    /// The unread counts with `chat` set to `count`; a chat without unread messages is left out.
    func withUnread(_ chat: ChatId, _ count: Int) -> [ChatId: Int] {
        var next = self
        next[chat] = count > 0 ? count : nil
        return next
    }
}
