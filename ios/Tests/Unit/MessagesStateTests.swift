// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import XCTest
@testable import Mumcord

private let annaChat = ChatId.person(certHash: "hash-anna")
private let goneChat = ChatId.person(certHash: "hash-gone")

private func connected(_ more: [BridgeEvent] = []) -> SessionState {
    ([.connectionChanged(state: .connected), .serverSynced(server: mockupServer())] + more)
        .reduce(SessionState()) { state, event in state.fold(event) }
}

private func conversation(
    _ chat: ChatId,
    name: String,
    session: UInt32?,
    last: ChatMessage? = nil,
    unread: UInt32 = 0
) -> Conversation {
    Conversation(
        chat: chat,
        peerName: name,
        peerSession: session,
        last: last ?? chatMessage(id: "last", chat: chat, senderName: name, sentAtMs: 5000, content: messageContent("See you")),
        unread: unread
    )
}

private func request(_ id: UInt64, name: String, chatRequest: Bool, note: String? = nil, messages: [ChatMessage] = []) -> MessageRequest {
    MessageRequest(
        id: id,
        session: 40,
        name: name,
        certHash: nil,
        chatRequest: chatRequest,
        note: note,
        messages: messages,
        receivedAtMs: 0
    )
}

private func state(
    session: SessionState = connected(),
    local: MessagesLocal = MessagesLocal(),
    conversations: [Conversation] = [],
    requests: MessageRequests = MessageRequests(incoming: [], sent: []),
    blocked: [BlockedPerson] = [],
    unread: [ChatId: Int] = [:],
    notificationsGranted: Bool = true
) -> MessagesUiState? {
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

final class MessagesStateTests: XCTestCase {
    func testThereIsNoStateWithoutAServer() {
        XCTAssertNil(state(session: SessionState()))
    }

    func testARowShowsTheLastMessageTheLiveUnreadCountAndWhoIsOnline() throws {
        let list = [
            conversation(annaChat, name: "Anna", session: ChatMockup.anna, unread: 1),
            conversation(goneChat, name: "Paula", session: nil),
        ]
        let ui = try XCTUnwrap(state(conversations: list, unread: [annaChat: 3]))
        XCTAssertEqual(ui.rows.map(\.name), ["Anna", "Paula"])
        XCTAssertEqual(ui.rows[0].preview, .text("See you"))
        XCTAssertEqual(ui.rows[0].unread, 3)
        XCTAssertTrue(ui.rows[0].online)
        XCTAssertEqual(ui.rows[0].session, ChatMockup.anna)
        XCTAssertFalse(ui.rows[1].online)
        XCTAssertEqual(ui.rows[1].unread, 0)
        XCTAssertTrue(ui.hasConversations)
    }

    func testAConversationWithOnlyOwnMessagesTakesTheNameFromTheServer() throws {
        let ui = try XCTUnwrap(state(conversations: [conversation(annaChat, name: "", session: ChatMockup.anna)]))
        XCTAssertEqual(ui.rows[0].name, "Anna")
    }

    func testAPictureWithoutWordsIsNamedAsAPicture() throws {
        let image = ImageRef.embedded(imageId: "i", mime: "image/png", width: 1, height: 1, byteLen: 1, animated: false)
        let picture = chatMessage(id: "p", chat: annaChat, content: messageContent(blocks: [.image(image: image)]))
        let ui = try XCTUnwrap(state(conversations: [conversation(annaChat, name: "Anna", session: nil, last: picture)]))
        XCTAssertEqual(ui.rows[0].preview, .picture)
    }

    func testTheSearchFiltersByNameAndOnlyWhileItIsOpen() throws {
        let list = [
            conversation(annaChat, name: "Anna", session: nil),
            conversation(goneChat, name: "Paula", session: nil),
        ]
        var local = MessagesLocal()
        local.query = "pau"
        XCTAssertEqual(try XCTUnwrap(state(local: local, conversations: list)).rows.count, 2)
        local.searching = true
        let ui = try XCTUnwrap(state(local: local, conversations: list))
        XCTAssertEqual(ui.rows.map(\.name), ["Paula"])
        XCTAssertTrue(ui.hasConversations)
    }

    func testTheBadgeCountsPrivateMessagesOnly() {
        let unread: [ChatId: Int] = [annaChat: 2, .session(session: 9): 1, .channel(channelId: Mockup.lobby): 7]
        XCTAssertEqual(messagesTabBadge(unread), 3)
    }

    func testARequestShowsItsNoteOrItsFirstMessage() throws {
        let held = chatMessage(id: "h", chat: .session(session: 40), content: messageContent("Hello there"))
        let requests = MessageRequests(
            incoming: [
                request(1, name: "Paula", chatRequest: true, note: "From the forum"),
                request(2, name: "Tom", chatRequest: false, messages: [held, held]),
                request(3, name: "Kim", chatRequest: true),
            ],
            sent: []
        )
        let ui = try XCTUnwrap(state(requests: requests))
        XCTAssertEqual(ui.requests.map(\.text), [.text("From the forum"), .text("Hello there"), .nothing])
        XCTAssertEqual(ui.requests[1].messageCount, 2)
        XCTAssertTrue(ui.requests[0].chatRequest)
    }

    func testThePickerListsEverybodyElseByNameAndFilters() throws {
        let server = try XCTUnwrap(connected().server)
        let everybody = pickerPeople(server, query: "")
        XCTAssertFalse(everybody.contains { $0.session == Mockup.lea })
        XCTAssertEqual(everybody.map(\.name), everybody.map(\.name).sorted())
        XCTAssertEqual(pickerPeople(server, query: " AN ").map(\.name), ["Anna"])
    }

    func testTheOpenConversationKnowsTheNameAndWhetherThePersonIsOnTheServer() throws {
        var local = MessagesLocal()
        local.open = OpenConversation(chat: annaChat)
        let listed = try XCTUnwrap(
            state(local: local, conversations: [conversation(annaChat, name: "Anna", session: ChatMockup.anna)])
        )
        XCTAssertEqual(listed.open?.name, "Anna")
        XCTAssertTrue(listed.openOnline)

        local.open = OpenConversation(chat: .session(session: Mockup.ben))
        let fresh = try XCTUnwrap(state(local: local))
        XCTAssertEqual(fresh.open?.name, "Ben")
        XCTAssertTrue(fresh.openOnline)

        let left = try XCTUnwrap(state(session: connected([.personRemoved(session: Mockup.ben)]), local: local))
        XCTAssertFalse(left.openOnline)
    }

    func testThePersonSheetFollowsBlockedAndTheChatRequests() throws {
        var anna = person(ChatMockup.anna, "Anna", channelId: Mockup.lobby)
        anna.supportsChatRequests = true
        anna.blocked = true
        let session = connected([.personChanged(person: anna)])
        let none = try XCTUnwrap(state(session: session))
        XCTAssertEqual(none.person(ChatMockup.anna), PersonMessaging(blocked: true, chatRequest: .offer))
        // Ben's app is another Mumble client: nothing to offer.
        XCTAssertEqual(none.person(Mockup.ben), PersonMessaging(blocked: false, chatRequest: .unsupported))

        let pending = MessageRequests(incoming: [], sent: [SentChatRequest(session: ChatMockup.anna, name: "Anna", accepted: false)])
        XCTAssertEqual(try XCTUnwrap(state(session: session, requests: pending)).person(ChatMockup.anna).chatRequest, .pending)
        let accepted = MessageRequests(incoming: [], sent: [SentChatRequest(session: ChatMockup.anna, name: "Anna", accepted: true)])
        XCTAssertEqual(try XCTUnwrap(state(session: session, requests: accepted)).person(ChatMockup.anna).chatRequest, .accepted)
        XCTAssertTrue(ChatRequestOffer.pending.shown)
        XCTAssertFalse(ChatRequestOffer.unsupported.shown)
    }

    func testTheSheetsAreAboutAPersonWhoIsStillThere() throws {
        var local = MessagesLocal()
        local.blockSession = Mockup.ben
        XCTAssertEqual(try XCTUnwrap(state(local: local)).sheet, .blockConfirm(session: Mockup.ben, name: "Ben", failed: false))
        XCTAssertNil(try XCTUnwrap(state(session: connected([.personRemoved(session: Mockup.ben)]), local: local)).sheet)

        local = MessagesLocal()
        local.requestSession = ChatMockup.anna
        local.requestNote = "Hi"
        XCTAssertEqual(
            try XCTUnwrap(state(local: local)).sheet,
            .chatRequest(session: ChatMockup.anna, name: "Anna", note: "Hi", sending: false, error: nil)
        )
    }

    func testNotificationsThatAreOffAreNamedAndBackKnowsWhatIsOpen() throws {
        XCTAssertTrue(try XCTUnwrap(state(notificationsGranted: false)).notificationsOff)
        XCTAssertFalse(try XCTUnwrap(state()).canGoBack)
        var local = MessagesLocal()
        local.blockedOpen = true
        XCTAssertTrue(try XCTUnwrap(state(local: local)).canGoBack)
    }
}
