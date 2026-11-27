// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import XCTest
@testable import Mumcord

private let annaChat = ChatId.person(certHash: "hash-anna")

private func annaConversation() -> Conversation {
    Conversation(
        chat: annaChat,
        peerName: "Anna",
        peerSession: ChatMockup.anna,
        last: chatMessage(id: "a1", chat: annaChat, senderName: "Anna", sentAtMs: 1000),
        unread: 0
    )
}

private func heldRequest(_ id: UInt64) -> MessageRequest {
    MessageRequest(
        id: id,
        session: Mockup.jonas,
        name: "Jonas",
        certHash: nil,
        chatRequest: false,
        note: nil,
        messages: [chatMessage(id: "held-\(id)", chat: .session(session: Mockup.jonas), senderName: "Jonas")],
        receivedAtMs: 0
    )
}

/// The Messages tab's actions through the controller, against the fake engine, with the real
/// session and chat stores in between.
@MainActor
final class MessagesControllerTests: XCTestCase {
    private let box = EngineBox()
    private var tabShown = 0
    private var sheetDismissed = 0

    private struct Connected {
        let store: SessionStore
        let chats: ChatStore
        let controller: MessagesController
    }

    /// Connected to the mockup server; the user is Lea in the Lobby.
    private func makeConnected(configure: @escaping @Sendable (FakeEngine) -> Void = { _ in }) async -> Connected {
        let store = makeStore(box, server: mockupServer(), servers: [savedServer(1, "Nordlicht")], configure: configure)
        // Before anything is sent: the chat store is the one reader of the chat events.
        let chats = ChatStore(session: store)
        let controller = MessagesController(store: store, chats: chats)
        controller.showMessagesTab = { [weak self] in self?.tabShown += 1 }
        controller.dismissConnectedSheet = { [weak self] in self?.sheetDismissed += 1 }
        await store.loadServers()
        await store.connectServer(1, password: nil, channelPath: [])
        await assertEventually { store.state.showsServer && store.state.server != nil }
        return Connected(store: store, chats: chats, controller: controller)
    }

    func testTheConversationsRequestsAndBlockedPeopleAreReadWhenTheServerIsShown() async {
        let connected = await makeConnected { engine in
            engine.conversations = [annaConversation()]
            engine.requests = MessageRequests(incoming: [heldRequest(1)], sent: [])
        }
        let controller = connected.controller
        await assertEventually { controller.conversations.count == 1 && controller.requests.incoming.count == 1 }
    }

    func testAPrivateMessageReadsTheListAgain() async {
        let connected = await makeConnected()
        let controller = connected.controller
        let engine = box.engine
        await assertEventually { engine.conversationReads >= 1 }
        XCTAssertTrue(controller.conversations.isEmpty)
        engine.conversations = [annaConversation()]
        engine.receive(chatMessage(id: "a2", chat: annaChat, senderName: "Anna"))
        await assertEventually { controller.conversations.count == 1 }
        await assertEventually { controller.unread[annaChat] == 1 }
    }

    func testSomebodyLeavingReadsTheListAgain() async {
        let connected = await makeConnected()
        let controller = connected.controller
        let engine = box.engine
        await assertEventually { engine.conversationReads >= 1 }
        engine.conversations = [annaConversation()]
        engine.leave(Mockup.jonas)
        await assertEventually { controller.conversations.count == 1 }
    }

    func testSendPrivateMessageShowsTheTabWithTheConversation() async {
        let connected = await makeConnected { $0.conversations = [annaConversation()] }
        let controller = connected.controller
        controller.messagePerson(ChatMockup.anna)
        await assertEventually { controller.local.open != nil }
        XCTAssertEqual(controller.local.open, OpenConversation(chat: annaChat, name: "Anna", session: ChatMockup.anna))
        XCTAssertEqual(tabShown, 1)

        // A person without a stored conversation gets the chat of their session.
        controller.messagePerson(Mockup.ben)
        await assertEventually { controller.local.open?.chat == .session(session: Mockup.ben) }
    }

    func testAChannelIsNeverOpenedAsAConversation() async {
        let connected = await makeConnected()
        connected.controller.openConversation(.channel(channelId: Mockup.lobby))
        XCTAssertNil(connected.controller.local.open)
    }

    func testBackClosesOneThingAtATime() async {
        let connected = await makeConnected()
        let controller = connected.controller
        controller.openSearch()
        controller.changeQuery("an")
        controller.openPicker()
        controller.openConversation(annaChat, name: "Anna")
        XCTAssertEqual(controller.local.page, .list)
        controller.back()
        XCTAssertNil(controller.local.open)
        XCTAssertTrue(controller.local.searching)
        controller.back()
        XCTAssertFalse(controller.local.searching)
        XCTAssertEqual(controller.local.query, "")
        controller.openBlocked()
        controller.back()
        XCTAssertFalse(controller.local.blockedOpen)
    }

    func testAcceptingTheLastRequestMakesAConversationAndLeavesThePage() async {
        let connected = await makeConnected { $0.requests = MessageRequests(incoming: [heldRequest(7)], sent: []) }
        let controller = connected.controller
        await assertEventually { controller.requests.incoming.count == 1 }
        controller.openRequests()
        controller.acceptRequest(7)
        await assertEventually { controller.requests.incoming.isEmpty && controller.local.page == .list }
        await assertEventually { controller.conversations.count == 1 }
        XCTAssertTrue(box.engine.requestAnswers.contains { $0.id == 7 && $0.accepted })
    }

    func testDecliningARequestTellsTheEngine() async {
        let connected = await makeConnected { $0.requests = MessageRequests(incoming: [heldRequest(8)], sent: []) }
        let controller = connected.controller
        await assertEventually { controller.requests.incoming.count == 1 }
        controller.declineRequest(8)
        await assertEventually { controller.requests.incoming.isEmpty }
        XCTAssertTrue(box.engine.requestAnswers.contains { $0.id == 8 && !$0.accepted })
        XCTAssertTrue(controller.conversations.isEmpty)
    }

    func testAChatRequestIsSentWithItsTrimmedNoteAndIsPendingAfterwards() async {
        let connected = await makeConnected()
        let controller = connected.controller
        controller.askChatRequest(ChatMockup.anna)
        XCTAssertEqual(sheetDismissed, 1)
        controller.changeRequestNote("  From the forum  ")
        controller.sendChatRequest()
        await assertEventually { controller.local.requestSession == nil }
        let engine = box.engine
        XCTAssertEqual(engine.chatRequestCalls.first?.session, ChatMockup.anna)
        XCTAssertEqual(engine.chatRequestCalls.first?.note, "From the forum")
        await assertEventually { controller.requests.sent.first?.accepted == false }
    }

    func testAnEmptyNoteIsLeftOutAndALongOneIsCut() async {
        let connected = await makeConnected()
        let controller = connected.controller
        controller.askChatRequest(ChatMockup.anna)
        controller.changeRequestNote(String(repeating: "x", count: 250))
        XCTAssertEqual(controller.local.requestNote.count, chatRequestNoteMax)
        controller.changeRequestNote("   ")
        controller.sendChatRequest()
        await assertEventually { controller.local.requestSession == nil }
        let call = box.engine.chatRequestCalls.first
        XCTAssertNotNil(call)
        XCTAssertNil(call?.note)
    }

    func testAChatRequestTheOtherAppCannotTakeSaysSo() async {
        let connected = await makeConnected { $0.chatRequestFailure = .InvalidInput(detail: "unsupported") }
        let controller = connected.controller
        controller.askChatRequest(Mockup.ben)
        controller.sendChatRequest()
        await assertEventually { controller.local.requestError == .refused }
        XCTAssertFalse(controller.local.requestSending)
        XCTAssertEqual(controller.local.requestSession, Mockup.ben)

        box.engine.chatRequestFailure = .NotConnected(detail: "gone")
        controller.sendChatRequest()
        await assertEventually { controller.local.requestError == .failed }
        controller.dismissSheet()
        XCTAssertNil(controller.local.requestSession)
        XCTAssertNil(controller.local.requestError)
    }

    func testBlockingAsksFirstAndThenBlocks() async {
        let connected = await makeConnected()
        let controller = connected.controller
        controller.askBlock(Mockup.ben)
        XCTAssertEqual(controller.local.blockSession, Mockup.ben)
        XCTAssertTrue(controller.blocked.isEmpty)
        controller.confirmBlock()
        await assertEventually { controller.local.blockSession == nil && controller.blocked.count == 1 }
        let store = connected.store
        await assertEventually { store.state.server?.people[Mockup.ben]?.blocked == true }
    }

    func testABlockThatCannotBeSavedKeepsTheQuestionOpen() async {
        let connected = await makeConnected { $0.blockFailure = .Io(detail: "disk") }
        let controller = connected.controller
        controller.askBlock(Mockup.ben)
        controller.confirmBlock()
        await assertEventually { controller.local.blockFailed }
        XCTAssertEqual(controller.local.blockSession, Mockup.ben)
    }

    func testUnblockingAPersonLiftsEveryBlockThatCoversThem() async {
        let connected = await makeConnected()
        let controller = connected.controller
        controller.askBlock(Mockup.ben)
        controller.confirmBlock()
        await assertEventually { controller.blocked.count == 1 }
        controller.unblockPerson(Mockup.ben)
        await assertEventually { controller.blocked.isEmpty }
        let store = connected.store
        await assertEventually { store.state.server?.people[Mockup.ben]?.blocked == false }
    }

    func testTheBlockedListUnblocksById() async {
        let connected = await makeConnected()
        let controller = connected.controller
        controller.askBlock(Mockup.jonas)
        controller.confirmBlock()
        await assertEventually { controller.blocked.count == 1 }
        controller.openBlocked()
        XCTAssertTrue(controller.local.blockedOpen)
        guard let id = controller.blocked.first?.id else { return XCTFail("nobody is blocked") }
        controller.unblock(id)
        await assertEventually { controller.blocked.isEmpty }
    }

    func testTheEndOfTheConnectionStartsAfresh() async {
        let connected = await makeConnected { $0.conversations = [annaConversation()] }
        let controller = connected.controller
        controller.openConversation(annaChat, name: "Anna")
        await assertEventually { controller.conversations.count == 1 }
        await connected.store.disconnect()
        await assertEventually { controller.local.open == nil && controller.conversations.isEmpty }
        XCTAssertEqual(controller.local, MessagesLocal())
    }

    func testTheEngineSettingKeepsShowSenderOnly() async {
        let connected = await makeConnected { engine in
            var settings = engine.storedMobileSettings
            settings.notifySenderOnly = true
            engine.storedMobileSettings = settings
        }
        let settings = EngineMessageNotificationSettings(store: connected.store)
        await assertEventually { settings.senderOnly }
        settings.setSenderOnly(false)
        let engine = box.engine
        await assertEventually { !engine.storedMobileSettings.notifySenderOnly }
    }
}
