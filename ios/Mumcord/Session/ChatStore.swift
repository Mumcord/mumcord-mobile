// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// Whether images and GIFs from the web may be loaded right now. The one place that decides;
/// the "Load images and GIFs" setting plugs in here.
typealias MediaPolicy = @Sendable () -> Bool

/// The chat of the connection: unread counts, arriving messages and the chat commands. It has
/// its own streams, apart from `SessionStore.state`, so chat traffic never redraws the channel
/// list. Channel chats and private conversations both go through it.
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

    init(session: SessionStore, mediaPolicy: @escaping MediaPolicy = { true }) {
        self.session = session
        self.mediaPolicy = mediaPolicy
        let events = session.chatEvents
        Task.detached { [weak self] in
            for await event in events {
                switch event {
                case .chatMessageReceived(let message):
                    self?.publish(message)
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
                    await self.readUnread()
                } else {
                    self.changeUnread { _ in [:] }
                }
            }
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

    private func removeObserver(_ id: UUID) {
        lock.lock()
        unreadObservers[id] = nil
        incomingObservers[id] = nil
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

    // MARK: Commands

    func page(_ chat: ChatId, before: String?) async -> Outcome<ChatPage> {
        await session.call("chat page") { try await $0.chatPage(chat: chat, before: before, limit: Self.pageSize) }
    }

    func send(_ chat: ChatId, text: String, replyTo: ReplyQuote?) async -> Outcome<ChatMessage> {
        await session.call("send message") { try await $0.sendMessage(chat: chat, text: text, replyTo: replyTo) }
    }

    func sendImage(_ chat: ChatId, bytes: Data, mime: String) async -> Outcome<ChatMessage> {
        await session.call("send image") { try await $0.sendImage(chat: chat, bytes: bytes, mime: mime) }
    }

    func sendGif(_ chat: ChatId, url: String) async -> Outcome<ChatMessage> {
        await session.call("send gif") { try await $0.sendGifFavourite(chat: chat, url: url) }
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

    /// Tells the engine which chat is on screen, or that none is.
    func setOpenChat(_ chat: ChatId?) {
        session.setOpenChat(chat)
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
