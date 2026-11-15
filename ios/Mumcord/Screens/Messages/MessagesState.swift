// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// What the Messages tab shows while no conversation is open.
enum MessagesPage: Equatable {
    case list
    case requests
    case picker
}

/// The conversation that is open. `name` and `session` are what was known when it was opened
/// (from the person sheet, the people picker or a request); the conversation list knows better
/// once it has the conversation.
struct OpenConversation: Equatable {
    let chat: ChatId
    var name = ""
    var session: UInt32?
}

/// Why a chat request did not get out.
enum ChatRequestError: Equatable {
    /// The other person's app cannot receive chat requests.
    case refused
    /// Something else went wrong; trying again may work.
    case failed
}

/// What the user did on the Messages tab and with blocking; everything else follows from the engine.
struct MessagesLocal: Equatable {
    var page = MessagesPage.list
    var open: OpenConversation?
    var searching = false
    var query = ""
    var pickerQuery = ""
    /// The person the "Block?" question is about.
    var blockSession: UInt32?
    var blockFailed = false
    /// The person the chat request being written goes to.
    var requestSession: UInt32?
    var requestNote = ""
    var requestSending = false
    var requestError: ChatRequestError?
    /// The list of blocked people is open on the More tab.
    var blockedOpen = false
    /// Notifications name the sender and leave the message out.
    var senderOnly = false
}

/// The last message of a conversation as the list shows it.
enum ConversationPreview: Equatable {
    case text(String)
    /// A picture without words.
    case picture
    case nothing
}

/// One line of the conversation list. `name` is untrusted text and may be empty.
struct ConversationRow: Equatable, Identifiable {
    let chat: ChatId
    let name: String
    let session: UInt32?
    let preview: ConversationPreview
    let sentAtMs: Int64
    let unread: Int
    let online: Bool

    var id: ChatId { chat }
}

/// One held request. `text` is the note of a chat request or the first held message.
struct RequestRow: Equatable, Identifiable {
    let id: UInt64
    let name: String
    /// A chat request of another Mumcord user; otherwise held messages.
    let chatRequest: Bool
    let text: ConversationPreview
    let messageCount: Int
}

/// A person on the server who can be written to.
struct PickerPerson: Equatable, Identifiable {
    let session: UInt32
    let name: String

    var id: UInt32 { session }
}

struct BlockedRow: Equatable, Identifiable {
    let id: Int64
    let name: String
    let serverLabel: String
}

/// Whether the person sheet offers a chat request.
enum ChatRequestOffer: Equatable {
    /// None was sent yet.
    case offer
    /// One is on its way; a decline is never told, so it stays like this.
    case pending
    /// The other person accepted, or wrote first: nothing to ask for.
    case accepted
    /// Their app does not understand chat requests (another Mumble client): nothing to offer.
    case unsupported

    /// The person sheet has a row for it.
    var shown: Bool { self == .offer || self == .pending }
}

/// What the person sheet shows about messages and blocking for one person.
struct PersonMessaging: Equatable {
    /// Blocked by the user, on this connection or an earlier one: the sheet offers "Unblock".
    let blocked: Bool
    let chatRequest: ChatRequestOffer
}

/// A question in a bottom sheet that belongs to messages and blocking.
enum MessagesSheet: Equatable {
    case blockConfirm(session: UInt32, name: String, failed: Bool)
    case chatRequest(session: UInt32, name: String, note: String, sending: Bool, error: ChatRequestError?)
}

struct MessagesUiState: Equatable {
    let page: MessagesPage
    /// The open conversation with the other person's name and whether they are on the server.
    let open: OpenConversation?
    let openOnline: Bool
    /// The conversations that match the search.
    let rows: [ConversationRow]
    /// There are conversations at all, whatever the search hides.
    let hasConversations: Bool
    let searching: Bool
    let query: String
    let requests: [RequestRow]
    let pickerQuery: String
    let pickerPeople: [PickerPerson]
    /// The phone may not show notifications for the app.
    let notificationsOff: Bool
    /// The number on the Messages tab.
    let badge: Int
    let sheet: MessagesSheet?
    let blocked: [BlockedRow]
    let blockedOpen: Bool
    let senderOnly: Bool
    fileprivate let people: [UInt32: Person]
    fileprivate let sentRequests: [UInt32: Bool]

    /// Something on the Messages or More tab that going back closes before it leaves the tab.
    var canGoBack: Bool {
        open != nil || page != .list || searching || blockedOpen
    }

    func person(_ session: UInt32) -> PersonMessaging {
        let person = people[session]
        let chatRequest: ChatRequestOffer
        if person?.supportsChatRequests != true {
            chatRequest = .unsupported
        } else if let accepted = sentRequests[session] {
            chatRequest = accepted ? .accepted : .pending
        } else {
            chatRequest = .offer
        }
        return PersonMessaging(blocked: person?.blocked == true, chatRequest: chatRequest)
    }
}

/// The Messages tab and the blocking screens as a value, or nil while no server is shown.
func messagesUiState(
    session: SessionState,
    local: MessagesLocal,
    conversations: [Conversation],
    requests: MessageRequests,
    blocked: [BlockedPerson],
    unread: [ChatId: Int],
    notificationsGranted: Bool
) -> MessagesUiState? {
    guard session.showsServer, let server = session.server else { return nil }
    let all = conversations.map { conversationRow($0, server: server, unread: unread) }
    let needle = local.query.trimmingCharacters(in: .whitespacesAndNewlines)
    var open = local.open
    if let opened = local.open {
        let listed = all.first { $0.chat == opened.chat }
        var sessionNow = listed?.session ?? opened.session
        if sessionNow == nil, case .session(let session) = opened.chat {
            sessionNow = session
        }
        if let name = listed?.name, !name.isEmpty {
            open?.name = name
        } else if opened.name.isEmpty {
            open?.name = sessionNow.flatMap { server.people[$0] }?.name ?? ""
        }
        open?.session = sessionNow.flatMap { server.people[$0] == nil ? nil : $0 }
    }
    var sentRequests: [UInt32: Bool] = [:]
    for sent in requests.sent {
        sentRequests[sent.session] = sent.accepted
    }
    return MessagesUiState(
        page: local.page,
        open: open,
        openOnline: open?.session != nil,
        rows: local.searching && !needle.isEmpty
            ? all.filter { $0.name.localizedCaseInsensitiveContains(needle) }
            : all,
        hasConversations: !all.isEmpty,
        searching: local.searching,
        query: local.query,
        requests: requests.incoming.map(requestRow),
        pickerQuery: local.pickerQuery,
        pickerPeople: pickerPeople(server, query: local.pickerQuery),
        notificationsOff: !notificationsGranted,
        badge: messagesTabBadge(unread),
        sheet: messagesSheet(server, local),
        blocked: blocked.map { BlockedRow(id: $0.id, name: $0.name, serverLabel: $0.serverLabel) },
        blockedOpen: local.blockedOpen,
        senderOnly: local.senderOnly,
        people: server.people,
        sentRequests: sentRequests
    )
}

private func requestRow(_ request: MessageRequest) -> RequestRow {
    let text: ConversationPreview
    if let note = request.note, !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        text = .text(note)
    } else {
        text = request.messages.first.map(preview) ?? .nothing
    }
    return RequestRow(
        id: request.id,
        name: request.name,
        chatRequest: request.chatRequest,
        text: text,
        messageCount: request.messages.count
    )
}

private func messagesSheet(_ server: ServerModel, _ local: MessagesLocal) -> MessagesSheet? {
    if let person = local.blockSession.flatMap({ server.people[$0] }) {
        return .blockConfirm(session: person.session, name: person.name, failed: local.blockFailed)
    }
    if let person = local.requestSession.flatMap({ server.people[$0] }) {
        return .chatRequest(
            session: person.session,
            name: person.name,
            note: local.requestNote,
            sending: local.requestSending,
            error: local.requestError
        )
    }
    return nil
}

private func conversationRow(_ conversation: Conversation, server: ServerModel, unread: [ChatId: Int]) -> ConversationRow {
    let person = conversation.peerSession.flatMap { server.people[$0] }
    return ConversationRow(
        chat: conversation.chat,
        // Empty when only own messages are stored; the person may still be on the server.
        name: conversation.peerName.isEmpty ? person?.name ?? "" : conversation.peerName,
        session: person?.session,
        preview: preview(conversation.last),
        sentAtMs: conversation.last.sentAtMs,
        // The live count: the one in the list is as old as the list.
        unread: unread[conversation.chat] ?? 0,
        online: person != nil
    )
}

private func preview(_ message: ChatMessage) -> ConversationPreview {
    let text = message.content.plainText()
    if !text.isEmpty { return .text(text) }
    let hasPicture = message.content.blocks.contains { block in
        if case .image = block { return true }
        return false
    }
    return hasPicture ? .picture : .nothing
}

/// Everybody else on the server by name; a non-blank `query` filters by name, ignoring case.
func pickerPeople(_ server: ServerModel, query: String) -> [PickerPerson] {
    let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
    return server.people.values
        .filter { $0.session != server.selfSession && (needle.isEmpty || $0.name.localizedCaseInsensitiveContains(needle)) }
        .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        .map { PickerPerson(session: $0.session, name: $0.name) }
}

/// The number on the Messages tab: unread private messages of all conversations.
func messagesTabBadge(_ unread: [ChatId: Int]) -> Int {
    unread.reduce(0) { sum, entry in entry.key.isChannel ? sum : sum + entry.value }
}

/// The longest note of a chat request, in characters (the engine's limit).
let chatRequestNoteMax = 200
