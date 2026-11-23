// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import Observation

/// What the user does with private messages: the conversation list, message requests, starting
/// a conversation, chat requests, blocking, and the "Show sender only" switch. The messages of
/// the open conversation are the business of a `ChatController` that follows `local.open`.
///
/// An object of its own, apart from `AppModel`, like the chat: the conversation list changes
/// with every private message, which must not redraw the channel list.
@MainActor
@Observable
final class MessagesController {
    private(set) var local = MessagesLocal()
    /// The stored private conversations of the connected server, newest first.
    private(set) var conversations: [Conversation] = []
    private(set) var requests = MessageRequests(incoming: [], sent: [])
    private(set) var blocked: [BlockedPerson] = []
    /// Unread messages per chat; a chat without any is not in the map.
    private(set) var unread: [ChatId: Int] = [:]

    /// Brings the Messages tab in front of whatever is open (a sheet, the full voice view).
    /// Set by the model that owns the tabs.
    @ObservationIgnored var showMessagesTab: () -> Void = {}
    /// Closes the sheet of the connected screens (the person sheet) before a question of this
    /// controller is shown: there is never more than one sheet. Set by the model.
    @ObservationIgnored var dismissConnectedSheet: () -> Void = {}

    @ObservationIgnored private let store: SessionStore
    @ObservationIgnored private let chats: ChatStore
    @ObservationIgnored private let settings: MessageNotificationSettings

    init(store: SessionStore, chats: ChatStore, settings: MessageNotificationSettings) {
        self.store = store
        self.chats = chats
        self.settings = settings
        local.senderOnly = settings.senderOnly
        let states = store.states()
        Task { [weak self] in
            var shown = false
            var people: Set<UInt32>?
            for await state in states {
                guard let self else { return }
                if state.showsServer != shown {
                    shown = state.showsServer
                    if !shown {
                        var fresh = MessagesLocal()
                        fresh.senderOnly = self.local.senderOnly
                        self.local = fresh
                    }
                }
                // Who is online is part of the conversation list, which has no event of its own.
                let now = Set((state.server?.people ?? [:]).keys)
                if let before = people, before != now {
                    chats.refreshConversations()
                }
                people = now
            }
        }
        let conversationStates = chats.conversationStates()
        Task { [weak self] in
            for await list in conversationStates {
                self?.conversations = list
            }
        }
        let requestStates = chats.requestStates()
        Task { [weak self] in
            for await requests in requestStates {
                guard let self else { return }
                self.requests = requests
                // The last request was answered: there is nothing left to look at on that page.
                if requests.incoming.isEmpty, self.local.page == .requests {
                    self.local.page = .list
                }
            }
        }
        let blockedStates = chats.blockedStates()
        Task { [weak self] in
            for await people in blockedStates {
                self?.blocked = people
            }
        }
        let unreadStates = chats.unreadStates()
        Task { [weak self] in
            for await counts in unreadStates {
                self?.unread = counts
            }
        }
        // The setting is read from the engine, which can take longer than drawing the switch.
        let senderOnlyStates = settings.senderOnlyStates()
        Task { [weak self] in
            for await senderOnly in senderOnlyStates {
                self?.local.senderOnly = senderOnly
            }
        }
    }

    /// The Messages tab and the blocking screens as a value, or nil while no server is shown.
    func uiState(session: SessionState, notificationsGranted: Bool) -> MessagesUiState? {
        messagesUiState(
            session: session,
            local: local,
            conversations: conversations,
            requests: requests,
            blocked: blocked,
            unread: unread,
            notificationsGranted: notificationsGranted
        )
    }

    // MARK: The list and its pages

    func openRequests() {
        local.page = .requests
    }

    func openPicker() {
        local.page = .picker
        local.pickerQuery = ""
    }

    func changePickerQuery(_ query: String) {
        local.pickerQuery = query
    }

    func openSearch() {
        local.searching = true
    }

    func closeSearch() {
        local.searching = false
        local.query = ""
    }

    func changeQuery(_ query: String) {
        local.query = query
    }

    /// One step back: out of the conversation, a page, the search or the blocked list.
    func back() {
        if local.open != nil {
            local.open = nil
        } else if local.page != .list {
            local.page = .list
            local.pickerQuery = ""
        } else if local.searching {
            closeSearch()
        } else {
            local.blockedOpen = false
        }
    }

    // MARK: Conversations

    /// Opens a conversation of the list, or the one a notification is about.
    func openConversation(_ chat: ChatId, name: String = "", session: UInt32? = nil) {
        if chat.isChannel { return }
        local.open = OpenConversation(chat: chat, name: name, session: session)
        local.page = .list
        local.pickerQuery = ""
    }

    /// "Send private message": shows the Messages tab with the conversation with that person.
    func messagePerson(_ session: UInt32) {
        guard let name = store.state.server?.people[session]?.name else { return }
        Task { [weak self, chats, store] in
            switch await chats.chatWith(session) {
            case .ok(let chat):
                guard let self else { return }
                self.showMessagesTab()
                self.openConversation(chat, name: name, session: session)
            case .failed:
                // The person left in this very moment; the lists show that already.
                store.reportFailure()
            }
        }
    }

    // MARK: Message requests

    func acceptRequest(_ id: UInt64) {
        Task { [chats, store] in
            if case .failed = await chats.acceptRequest(id) { store.reportFailure() }
        }
    }

    /// Declines a request. The other person is never told.
    func declineRequest(_ id: UInt64) {
        Task { [chats, store] in
            if case .failed = await chats.declineRequest(id) { store.reportFailure() }
        }
    }

    // MARK: Chat requests

    /// Opens the sheet for a chat request to a person; nothing is sent before "Send".
    func askChatRequest(_ session: UInt32) {
        dismissConnectedSheet()
        local.requestSession = session
        local.requestNote = ""
        local.requestSending = false
        local.requestError = nil
    }

    func changeRequestNote(_ note: String) {
        local.requestNote = String(note.prefix(chatRequestNoteMax))
    }

    func sendChatRequest() {
        guard let session = local.requestSession, !local.requestSending else { return }
        let note = local.requestNote.trimmingCharacters(in: .whitespacesAndNewlines)
        local.requestSending = true
        local.requestError = nil
        Task { [weak self, chats] in
            let sent = await chats.sendChatRequest(session, note: note.isEmpty ? nil : note)
            guard let self, self.local.requestSession == session else { return }
            self.local.requestSending = false
            switch sent {
            case .ok:
                self.local.requestSession = nil
                self.local.requestNote = ""
            case .failed(let error):
                self.local.requestError = error == .invalidInput ? .refused : .failed
            }
        }
    }

    // MARK: Blocking

    /// Asks before blocking: the sheet says what a block does.
    func askBlock(_ session: UInt32) {
        dismissConnectedSheet()
        local.blockSession = session
        local.blockFailed = false
    }

    func confirmBlock() {
        guard let session = local.blockSession else { return }
        Task { [weak self, chats] in
            if case .failed = await chats.block(session) {
                if let self, self.local.blockSession == session { self.local.blockFailed = true }
                return
            }
            await chats.readBlocked()
            if let self, self.local.blockSession == session { self.local.blockSession = nil }
            // Their conversation is no longer one to show unread messages of.
            chats.refreshConversations()
        }
    }

    /// Lifts every block that covers a person on the server. The list is read first: which
    /// person an entry covers changes as people come and go.
    func unblockPerson(_ session: UInt32) {
        Task { [chats, store] in
            guard case .ok(let blocked) = await chats.readBlocked() else {
                store.reportFailure()
                return
            }
            for entry in blocked where entry.session == session {
                if case .failed = await chats.unblock(entry.id) { store.reportFailure() }
            }
            await chats.readBlocked()
        }
    }

    /// Lifts a block of the blocked list.
    func unblock(_ id: Int64) {
        Task { [chats, store] in
            if case .failed = await chats.unblock(id) { store.reportFailure() }
            await chats.readBlocked()
        }
    }

    func openBlocked() {
        local.blockedOpen = true
        Task { [chats] in await chats.readBlocked() }
    }

    /// Closes whichever sheet is open.
    func dismissSheet() {
        local.blockSession = nil
        local.blockFailed = false
        local.requestSession = nil
        local.requestNote = ""
        local.requestError = nil
    }

    // MARK: Notifications

    func setSenderOnly(_ senderOnly: Bool) {
        settings.setSenderOnly(senderOnly)
    }
}
