// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import XCTest
@testable import Mumcord

private let annaChat = ChatId.person(certHash: "hash-anna")
private let lobbyChat = ChatId.channel(channelId: Mockup.lobby)

/// Remembers what was shown and removed instead of telling the system.
private final class FakeNotifier: MessageNotifier, @unchecked Sendable {
    private let lock = NSLock()
    private var _shown: [MessageNotice] = []
    private var _cancelled: [String] = []
    private var _cancelledAll = 0

    var shown: [MessageNotice] { lock.withLock { _shown } }
    var cancelled: [String] { lock.withLock { _cancelled } }
    var cancelledAll: Int { lock.withLock { _cancelledAll } }

    func show(_ notice: MessageNotice) {
        lock.withLock { _shown.append(notice) }
    }

    func cancel(_ key: String) {
        lock.withLock { _cancelled.append(key) }
    }

    func cancelAll() {
        lock.withLock { _cancelledAll += 1 }
    }
}

private final class Permission: @unchecked Sendable {
    private let lock = NSLock()
    private var _granted = true

    var granted: Bool {
        get { lock.withLock { _granted } }
        set { lock.withLock { _granted = newValue } }
    }
}

private func privateMessage(_ id: String, preview: String = "Joining later?") -> ChatNotification {
    ChatNotification(chat: annaChat, kind: .privateMessage, senderName: "Anna", preview: preview, messageId: id)
}

final class MessageNotificationCenterTests: XCTestCase {
    private let box = EngineBox()
    private let notifier = FakeNotifier()
    private let permission = Permission()
    private let settings = InMemoryMessageNotificationSettings()
    private var chats: ChatStore!
    private var center: MessageNotificationCenter!

    /// The store with the notification centre on it, before any connection.
    private func makeCentre() -> SessionStore {
        let store = makeStore(box, server: mockupServer(), servers: [savedServer(1, "Nordlicht")])
        let chats = ChatStore(session: store)
        self.chats = chats
        let permission = self.permission
        center = MessageNotificationCenter(
            store: store,
            chats: chats,
            notifier: notifier,
            settings: settings,
            permitted: { permission.granted },
            now: { 42 }
        )
        return store
    }

    private func makeConnected() async -> SessionStore {
        let store = makeCentre()
        await store.loadServers()
        await store.connectServer(1, password: nil, channelPath: [])
        await assertEventually { store.state.showsServer && store.state.server != nil }
        return store
    }

    func testWhatAnEarlierRunLeftBehindIsRemovedAtOnce() async {
        _ = makeCentre()
        let notifier = self.notifier
        await assertEventually { notifier.cancelledAll == 1 }
    }

    func testAPrivateMessageIsShownWithSenderAndText() async {
        _ = await makeConnected()
        let notifier = self.notifier
        box.engine.notify(privateMessage("m1"))
        await assertEventually { notifier.shown.count == 1 }
        XCTAssertEqual(
            notifier.shown.first,
            MessageNotice(
                key: annaChat.notificationKey,
                title: "Anna",
                group: false,
                lines: [MessageNotice.Line(sender: "Anna", text: "Joining later?", atMillis: 42)]
            )
        )
    }

    func testOneNotificationPerConversationKeepsItsLatestLines() async {
        _ = await makeConnected()
        let notifier = self.notifier
        let count = MessageNotificationCenter.maxLines + 2
        for index in 0..<count {
            box.engine.notify(privateMessage("m\(index)", preview: "text \(index)"))
        }
        await assertEventually { notifier.shown.count == count }
        let last = notifier.shown.last
        XCTAssertEqual(last?.key, annaChat.notificationKey)
        XCTAssertEqual(last?.lines.count, MessageNotificationCenter.maxLines)
        XCTAssertEqual(last?.lines.last?.text, "text \(count - 1)")
        XCTAssertEqual(last?.lines.first?.text, "text 2")
    }

    func testAMentionIsNamedByItsChannel() async {
        _ = await makeConnected()
        let notifier = self.notifier
        box.engine.notify(
            ChatNotification(chat: lobbyChat, kind: .mention, senderName: "Ben", preview: "look here", messageId: "m1")
        )
        await assertEventually { notifier.shown.count == 1 }
        XCTAssertEqual(notifier.shown.first?.title, "Lobby")
        XCTAssertEqual(notifier.shown.first?.group, true)
        XCTAssertEqual(notifier.shown.first?.lines.first?.sender, "Ben")
    }

    func testShowSenderOnlyLeavesTheTextOut() async {
        _ = await makeConnected()
        let notifier = self.notifier
        settings.setSenderOnly(true)
        box.engine.notify(privateMessage("m1"))
        await assertEventually { notifier.shown.count == 1 }
        XCTAssertNil(notifier.shown.first?.lines.first?.text)
        XCTAssertEqual(notifier.shown.first?.title, "Anna")
    }

    func testAPictureWithoutWordsHasNoText() async {
        _ = await makeConnected()
        let notifier = self.notifier
        box.engine.notify(privateMessage("m1", preview: ""))
        await assertEventually { notifier.shown.count == 1 }
        XCTAssertNil(notifier.shown.first?.lines.first?.text)
    }

    func testNothingIsShownWithoutThePermission() async {
        _ = await makeConnected()
        let notifier = self.notifier
        permission.granted = false
        box.engine.notify(privateMessage("m1"))
        let appeared = await eventually(timeout: 0.3) { !notifier.shown.isEmpty }
        XCTAssertFalse(appeared)
        permission.granted = true
        box.engine.notify(privateMessage("m2"))
        await assertEventually { notifier.shown.count == 1 }
        XCTAssertEqual(notifier.shown.first?.lines.count, 1)
    }

    func testOpeningTheChatRemovesItsNotification() async {
        _ = await makeConnected()
        let notifier = self.notifier
        box.engine.notify(privateMessage("m1"))
        await assertEventually { notifier.shown.count == 1 }
        chats.setOpenChat(owner: UUID(), chat: annaChat)
        await assertEventually { notifier.cancelled == [annaChat.notificationKey] }
        // What follows starts a new notification instead of adding to the removed one.
        box.engine.notify(privateMessage("m2"))
        await assertEventually { notifier.shown.count == 2 }
        XCTAssertEqual(notifier.shown.last?.lines.count, 1)
    }

    func testReadingTheChatRemovesItsNotification() async {
        _ = await makeConnected()
        let notifier = self.notifier
        let engine = box.engine
        engine.receive(chatMessage(id: "m1", chat: annaChat, senderName: "Anna"))
        engine.notify(privateMessage("m1"))
        await assertEventually { notifier.shown.count == 1 }
        await chats.markRead(annaChat)
        await assertEventually { notifier.cancelled == [annaChat.notificationKey] }
    }

    func testTheEndOfTheConnectionRemovesEverythingAndShowsNothingMore() async {
        let store = await makeConnected()
        let notifier = self.notifier
        let engine = box.engine
        engine.notify(privateMessage("m1"))
        await assertEventually { notifier.shown.count == 1 }
        let before = notifier.cancelledAll
        await store.disconnect()
        await assertEventually { notifier.cancelledAll == before + 1 }
        engine.notify(privateMessage("m2"))
        let appeared = await eventually(timeout: 0.3) { notifier.shown.count > 1 }
        XCTAssertFalse(appeared)
    }

    func testAKeyNamesItsChatAndNothingElse() {
        for chat in [annaChat, lobbyChat, ChatId.session(session: 7)] {
            XCTAssertEqual(chatFromNotificationKey(chat.notificationKey), chat)
        }
        XCTAssertNil(chatFromNotificationKey("p:"))
        XCTAssertNil(chatFromNotificationKey("c:x"))
        XCTAssertNil(chatFromNotificationKey("something"))
    }
}

/// Two screens that show chats, and the one chat the engine is told about.
final class ChatStoreOpenChatTests: XCTestCase {
    private let box = EngineBox()

    func testEachScreenKeepsItsOwnOpenChatAndTheLastOneSetIsOnScreen() async {
        let store = makeStore(box, server: mockupServer(), servers: [savedServer(1, "Nordlicht")])
        let chats = ChatStore(session: store)
        await store.loadServers()
        await store.connectServer(1, password: nil, channelPath: [])
        await assertEventually { store.state.showsServer }
        let chatTab = UUID()
        let conversation = UUID()

        chats.setOpenChat(owner: chatTab, chat: lobbyChat)
        chats.setOpenChat(owner: conversation, chat: annaChat)
        XCTAssertEqual(chats.openChat, annaChat)
        // The conversation goes away: it cannot take the Chat tab's chat with it.
        chats.setOpenChat(owner: conversation, chat: nil)
        XCTAssertEqual(chats.openChat, lobbyChat)
        // Opening another conversation releases the one that screen had before.
        chats.setOpenChat(owner: conversation, chat: .session(session: 7))
        chats.setOpenChat(owner: conversation, chat: annaChat)
        chats.setOpenChat(owner: conversation, chat: nil)
        chats.setOpenChat(owner: chatTab, chat: nil)
        XCTAssertNil(chats.openChat)
        XCTAssertEqual(
            box.engine.openChatCalls,
            [lobbyChat, annaChat, lobbyChat, .session(session: 7), annaChat, lobbyChat, nil]
        )
    }
}
