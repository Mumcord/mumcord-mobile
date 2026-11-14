// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import Observation

/// What the user does in one chat: reading back, writing, replying, sending pictures and GIFs.
/// It is told which chat to show (`open`) and whether its screen is the one the user looks at
/// (`setShown`, `setResumed`), so the same controller serves the channel chat and a private
/// conversation.
///
/// An object of its own, apart from `AppModel`: only the chat views read it, so chat traffic
/// does not redraw the channel list.
@MainActor
@Observable
final class ChatController {
    private(set) var local = ChatLocal()
    /// Unread messages per chat; a chat without any is not in the map.
    private(set) var unread: [ChatId: Int] = [:]

    @ObservationIgnored private let chats: ChatStore
    /// The chat's screen is the one on top (its tab is selected).
    @ObservationIgnored private var shown = false
    /// The app is in front; a chat behind the lock screen is not being read.
    @ObservationIgnored private var resumed = false
    @ObservationIgnored private var pageTask: Task<Void, Never>?
    @ObservationIgnored private var mentionTask: Task<Void, Never>?
    @ObservationIgnored private var nextPendingId: Int64 = 1

    init(chats: ChatStore) {
        self.chats = chats
        let incoming = chats.incoming()
        Task { [weak self] in
            for await message in incoming {
                guard let self else { return }
                self.local = self.local.received(message)
                self.markSeen()
            }
        }
        let unreadStates = chats.unreadStates()
        Task { [weak self] in
            for await counts in unreadStates {
                guard let self else { return }
                self.unread = counts
                self.markSeen()
            }
        }
    }

    /// Shows `chat`, or none. Asking for the chat that is already open changes nothing.
    func open(_ chat: ChatId?) {
        if chat == local.chat { return }
        load(chat)
    }

    private func load(_ chat: ChatId?) {
        pageTask?.cancel()
        mentionTask?.cancel()
        // What was typed is kept: joining another channel must not throw a text away.
        var next = ChatLocal()
        next.chat = chat
        next.loading = chat != nil
        next.draft = local.draft
        next.cursor = local.cursor
        next.visible = local.visible
        local = next
        syncOpenChat()
        guard let chat else { return }
        pageTask = Task { [weak self, chats] in
            let page = await chats.page(chat, before: nil)
            guard let self, !Task.isCancelled, self.local.chat == chat else { return }
            switch page {
            case .ok(let page):
                self.local = self.local.loaded(page)
            case .failed:
                self.local.loading = false
                self.local.loadFailed = true
            }
            let limits = await chats.limits()
            guard !Task.isCancelled, self.local.chat == chat else { return }
            self.local.limits = limits
            self.markSeen()
        }
    }

    /// Reads the chat again after the first page could not be read.
    func reload() {
        guard let chat = local.chat, !local.loading else { return }
        load(chat)
    }

    /// Reads the next older page; called when the user gets close to the oldest message shown.
    func loadOlder() {
        guard let chat = local.chat, let oldest = local.messages.first else { return }
        if !local.hasMore || local.loading || local.loadingOlder { return }
        local.loadingOlder = true
        pageTask = Task { [weak self, chats] in
            let page = await chats.page(chat, before: oldest.id)
            guard let self, self.local.chat == chat else { return }
            switch page {
            case .ok(let page):
                self.local = self.local.prepended(page)
            case .failed:
                // Scrolling up again asks once more.
                self.local.loadingOlder = false
            }
        }
    }

    // MARK: Seeing

    /// The chat's screen is the one the user looks at (its tab is selected), or not.
    func setShown(_ shown: Bool) {
        self.shown = shown
        updateVisible()
    }

    /// The app came to the front or left it.
    func setResumed(_ front: Bool) {
        resumed = front
        updateVisible()
    }

    private func updateVisible() {
        let visible = shown && resumed
        if local.visible == visible { return }
        local.visible = visible
        syncOpenChat()
        markSeen()
    }

    /// The list reports whether the newest message is on screen.
    func setAtBottom(_ atBottom: Bool) {
        if local.atBottom == atBottom { return }
        local = local.scrolled(atBottom: atBottom)
        markSeen()
    }

    /// The "New messages" pill: jump to the newest message.
    func scrollToNewest() {
        local.scrollToken += 1
        local.newBelow = 0
    }

    private func syncOpenChat() {
        chats.setOpenChat(local.visible ? local.chat : nil)
    }

    /// Messages count as read once the newest one was on screen in front of the user.
    private func markSeen() {
        guard let chat = local.chat, local.visible, local.atBottom, !local.loading else { return }
        if (unread[chat] ?? 0) == 0 { return }
        Task { [chats] in await chats.markRead(chat) }
    }

    // MARK: Writing

    func changeDraft(_ text: String, cursor: Int) {
        local.draft = text
        local.cursor = cursor
        mentionTask?.cancel()
        guard let query = mentionQuery(text, cursor: cursor) else {
            if !local.mentions.isEmpty { local.mentions = [] }
            return
        }
        mentionTask = Task { [weak self, chats] in
            let candidates = await chats.mentionCandidates(query)
            guard let self, !Task.isCancelled, self.local.draft == text, self.local.cursor == cursor else { return }
            self.local.mentions = candidates
        }
    }

    func pickMention(_ candidate: MentionCandidate) {
        mentionTask?.cancel()
        let completed = insertMention(local.draft, cursor: local.cursor, name: candidate.name)
        local.draft = completed.text
        local.cursor = completed.cursor
        local.mentions = []
    }

    func startReply(_ message: ChatMessage) {
        local.reply = ReplyTarget(messageId: message.id, quote: message.quote)
    }

    func cancelReply() {
        local.reply = nil
    }

    /// Sends what was typed. The text leaves the field at once and stays visible until it is out.
    func send() {
        guard local.chat != nil, canSend(local.draft, limits: local.limits) else { return }
        mentionTask?.cancel()
        let text = local.draft.trimmingCharacters(in: .whitespacesAndNewlines)
        let reply = local.reply?.quote
        local.draft = ""
        local.cursor = 0
        local.reply = nil
        local.mentions = []
        enqueue(.text(text, reply: reply))
    }

    /// A picture from the photo library or the camera.
    func sendImage(bytes: Data, mime: String) {
        local.sheet = nil
        enqueue(.image(bytes: bytes, mime: mime))
    }

    /// The picture the user chose could not be read from the phone.
    func imageUnreadable() {
        local.sheet = nil
        local.notice = .imageUnreadable
    }

    func sendGif(_ url: String) {
        local.sheet = nil
        enqueue(.gif(url: url))
    }

    private func enqueue(_ content: PendingContent) {
        let send = PendingSend(id: nextPendingId, content: content)
        nextPendingId += 1
        local = local.sending(send)
        deliver(send)
    }

    /// Tries a message that did not get out once more.
    func retry(_ pendingId: Int64) {
        guard let index = local.pending.firstIndex(where: { $0.id == pendingId && $0.failure != nil }) else { return }
        local.pending[index].failure = nil
        deliver(local.pending[index])
    }

    /// Gives up on a message that did not get out.
    func discard(_ pendingId: Int64) {
        local.pending.removeAll { $0.id == pendingId && $0.failure != nil }
    }

    private func deliver(_ send: PendingSend) {
        guard let chat = local.chat else { return }
        Task { [weak self, chats] in
            let result: Outcome<ChatMessage>
            switch send.content {
            case .text(let text, let reply):
                result = await chats.send(chat, text: text, replyTo: reply)
            case .image(let bytes, let mime):
                result = await chats.sendImage(chat, bytes: bytes, mime: mime)
            case .gif(let url):
                result = await chats.sendGif(chat, url: url)
            }
            guard let self else { return }
            switch result {
            case .ok(let message):
                if self.local.chat == chat { self.local = self.local.sent(send.id, message) }
            case .failed(let error):
                self.local = self.local.sendFailed(send.id, error.sendFailure)
            }
        }
    }

    // MARK: Sheets, links, pictures

    func openImageSources() {
        local.sheet = .imageSource
    }

    func openGifs() {
        local.sheet = .gifs
        refreshGifs()
    }

    private func refreshGifs() {
        Task { [weak self, chats] in
            let favourites = await chats.gifFavourites()
            self?.local.gifs = favourites
        }
    }

    func removeGif(_ url: String) {
        local.gifs.removeAll { $0.url == url }
        Task { [weak self, chats] in
            await chats.removeGifFavourite(url)
            self?.refreshGifs()
        }
    }

    /// Keeps a GIF of the chat as a favourite and says whether that worked.
    func addGif(_ url: String) {
        Task { [weak self, chats] in
            let added = await chats.addGifFavourite(url)
            if case .ok = added {
                self?.local.notice = .gifSaved
            } else {
                self?.local.notice = .gifNotSaved
            }
        }
    }

    func dismissNotice() {
        local.notice = nil
    }

    /// A link was tapped. It goes to the system browser; one whose text names another address
    /// first shows where it really leads.
    func tapLink(_ link: SafeLink) {
        if link.misleading {
            local.linkWarning = link
        } else {
            local.urlToOpen = link.url
        }
    }

    /// The user read the warning and wants to open the link anyway.
    func confirmLink() {
        local.urlToOpen = local.linkWarning?.url
        local.linkWarning = nil
    }

    /// The address was handed to the system.
    func urlOpened() {
        local.urlToOpen = nil
    }

    func openViewer(_ source: ChatImageSource) {
        local.viewer = source
    }

    func closeViewer() {
        local.viewer = nil
    }

    /// Closes whichever sheet is open.
    func dismissSheet() {
        local.sheet = nil
        local.linkWarning = nil
    }
}

private extension ConnectionError {
    var sendFailure: SendFailure {
        switch self {
        case .invalidInput, .permissionDenied: .refused
        default: .notSent
        }
    }
}
