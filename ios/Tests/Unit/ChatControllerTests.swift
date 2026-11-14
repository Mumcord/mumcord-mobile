// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import XCTest

@testable import Mumcord

private let lobby = ChatId.channel(channelId: Mockup.lobby)
private let gaming = ChatId.channel(channelId: Mockup.gaming)

private func history(_ chat: ChatId, _ count: Int) -> [ChatMessage] {
    (0..<count).map { index in
        chatMessage(id: "m\(index)", chat: chat, senderName: "Anna", senderSession: ChatMockup.anna, sentAtMs: Int64(index) * 1000)
    }
}

private func incoming(_ id: String, chat: ChatId = lobby) -> ChatMessage {
    chatMessage(id: id, chat: chat, senderName: "Anna", senderSession: ChatMockup.anna, sentAtMs: 1_000_000)
}

/// A session that is connected to the mockup server, and the chat controller on it.
private struct ConnectedChat {
    let store: SessionStore
    let controller: ChatController
}

/// The chat's actions through the controller, against the fake engine, with the real session
/// and chat stores in between.
@MainActor
final class ChatControllerTests: XCTestCase {
    private let box = EngineBox()

    /// Connected to the mockup server; the user is Lea in the Lobby. No chat is open yet.
    private func makeConnected(configure: @escaping @Sendable (FakeEngine) -> Void = { _ in }) async -> ConnectedChat {
        let store = makeStore(
            box,
            server: mockupServer(),
            servers: [savedServer(1, "Nordlicht")],
            configure: configure
        )
        // Before anything is sent: the chat store is the one reader of the chat events.
        let chats = ChatStore(session: store)
        let controller = ChatController(chats: chats)
        await store.loadServers()
        await store.connectServer(1, password: nil, channelPath: [])
        await assertEventually { store.state.showsServer }
        return ConnectedChat(store: store, controller: controller)
    }

    /// Opens the Lobby's chat and waits until its first page and the limits were read. With
    /// `shown`, its tab is selected and the app is in front.
    private func openLobby(_ controller: ChatController, shown: Bool = true) async {
        controller.open(lobby)
        if shown { show(controller) }
        await assertEventually { !controller.local.loading && controller.local.limits != nil }
    }

    private func show(_ controller: ChatController) {
        controller.setShown(true)
        controller.setResumed(true)
    }

    private func ui(_ chat: ConnectedChat) throws -> ChatUiState {
        try XCTUnwrap(
            chatUiState(session: chat.store.state, local: chat.controller.local, serverName: "Nordlicht", calendar: .current)
        )
    }

    // MARK: History

    func testOpeningAChatShowsItsNewestPage() async throws {
        let chat = await makeConnected { $0.messages = [lobby: history(lobby, 120)] }
        let controller = chat.controller

        controller.open(lobby)
        XCTAssertTrue(controller.local.loading, "the chat is loading at once")
        XCTAssertEqual(controller.local.chat, lobby)
        await assertEventually { !controller.local.loading && controller.local.limits != nil }

        XCTAssertEqual(controller.local.messages.count, Int(ChatStore.pageSize))
        XCTAssertEqual(controller.local.messages.first?.id, "m70")
        XCTAssertEqual(controller.local.messages.last?.id, "m119")
        XCTAssertTrue(controller.local.hasMore)
        XCTAssertFalse(controller.local.loadFailed)
        XCTAssertEqual(controller.local.scrollToken, 1, "the list jumps to the newest message")
        XCTAssertEqual(controller.local.limits, box.engine.limits)

        let state = try ui(chat)
        XCTAssertEqual(state.chat, lobby)
        XCTAssertEqual(state.title, "Lobby")
        XCTAssertTrue(state.hasMore)
        XCTAssertFalse(state.loading)
        XCTAssertFalse(state.historyStart)
    }

    func testOpeningTheChatThatIsOpenChangesNothing() async {
        let chat = await makeConnected { $0.messages = [lobby: history(lobby, 3)] }
        let controller = chat.controller
        await openLobby(controller)
        let before = controller.local

        controller.open(lobby)
        XCTAssertEqual(controller.local, before)
    }

    func testReadingBackLoadsOlderPagesUntilTheHistoryEnds() async throws {
        let chat = await makeConnected { $0.messages = [lobby: history(lobby, 120)] }
        let controller = chat.controller
        await openLobby(controller)
        let firstJump = controller.local.scrollToken

        controller.loadOlder()
        XCTAssertTrue(controller.local.loadingOlder)
        controller.loadOlder()
        await assertEventually { !controller.local.loadingOlder }
        XCTAssertEqual(controller.local.messages.count, 100)
        XCTAssertEqual(controller.local.messages.first?.id, "m20")
        XCTAssertTrue(controller.local.hasMore)
        XCTAssertFalse(try ui(chat).historyStart)

        controller.loadOlder()
        await assertEventually { !controller.local.loadingOlder }
        XCTAssertEqual(controller.local.messages.map(\.id), (0..<120).map { "m\($0)" })
        XCTAssertFalse(try ui(chat).hasMore)
        XCTAssertTrue(try ui(chat).historyStart)
        XCTAssertEqual(controller.local.scrollToken, firstJump, "the list never jumps while reading back")

        controller.loadOlder()
        XCTAssertFalse(controller.local.loadingOlder, "there is nothing older to ask for")
        XCTAssertEqual(controller.local.messages.count, 120)
    }

    func testAnOlderPageThatCannotBeReadIsAskedForAgainLater() async {
        let chat = await makeConnected { $0.messages = [lobby: history(lobby, 60)] }
        let controller = chat.controller
        let engine = box.engine
        await openLobby(controller)

        engine.pageFailure = .Io(detail: "disk")
        controller.loadOlder()
        await assertEventually { !controller.local.loadingOlder }
        XCTAssertEqual(controller.local.messages.count, 50)
        XCTAssertTrue(controller.local.hasMore)
        XCTAssertFalse(controller.local.loadFailed, "what is on screen stays")

        engine.pageFailure = nil
        controller.loadOlder()
        await assertEventually { controller.local.messages.count == 60 }
        XCTAssertFalse(controller.local.hasMore)
    }

    func testAChatThatCannotBeReadSaysSoAndCanBeTriedAgain() async throws {
        let chat = await makeConnected {
            $0.messages = [lobby: history(lobby, 2)]
            $0.pageFailure = .Io(detail: "disk")
        }
        let controller = chat.controller
        let engine = box.engine
        await openLobby(controller)
        XCTAssertTrue(controller.local.loadFailed)
        XCTAssertTrue(controller.local.messages.isEmpty)
        XCTAssertTrue(try ui(chat).loadFailed)
        XCTAssertFalse(try ui(chat).empty)

        engine.pageFailure = nil
        controller.reload()
        XCTAssertTrue(controller.local.loading)
        await assertEventually { !controller.local.loading && controller.local.messages.count == 2 }
        XCTAssertFalse(controller.local.loadFailed)
        XCTAssertFalse(try ui(chat).loadFailed)
    }

    func testAnotherChatReplacesTheMessagesAndKeepsWhatWasTyped() async throws {
        let chat = await makeConnected {
            $0.messages = [
                lobby: history(lobby, 3),
                gaming: [chatMessage(id: "g1", chat: gaming, senderName: "Mira")],
            ]
        }
        let controller = chat.controller
        let store = chat.store
        await openLobby(controller)
        controller.changeDraft("half a sentence", cursor: 15)
        controller.startReply(try XCTUnwrap(controller.local.messages.first))
        XCTAssertNotNil(controller.local.reply)

        await store.joinChannel(Mockup.gaming, password: nil)
        await assertEventually { currentChannelChat(store.state) == gaming }
        controller.open(gaming)
        await assertEventually {
            controller.local.chat == gaming && !controller.local.loading && controller.local.limits != nil
        }

        XCTAssertEqual(controller.local.messages.map(\.id), ["g1"])
        XCTAssertEqual(controller.local.draft, "half a sentence")
        XCTAssertEqual(controller.local.cursor, 15)
        XCTAssertNil(controller.local.reply, "a reply belongs to the chat it was started in")
        XCTAssertTrue(controller.local.visible)
        let state = try ui(chat)
        XCTAssertEqual(state.chat, gaming)
        XCTAssertEqual(state.title, "Gaming")
        XCTAssertEqual(state.draft, "half a sentence")
        XCTAssertEqual(box.engine.openChatCalls, [nil, lobby, gaming])
    }

    func testClosingTheChatForgetsItsMessages() async {
        let chat = await makeConnected { $0.messages = [lobby: history(lobby, 3)] }
        let controller = chat.controller
        await openLobby(controller)

        controller.open(nil)
        XCTAssertNil(controller.local.chat)
        XCTAssertFalse(controller.local.loading)
        XCTAssertTrue(controller.local.messages.isEmpty)
        XCTAssertEqual(box.engine.openChatCalls, [nil, lobby, nil])
    }

    // MARK: The open chat and unread messages

    func testTheEngineKnowsTheChatOnlyWhileItIsOnScreenAndTheAppInFront() async {
        let chat = await makeConnected()
        let controller = chat.controller
        let engine = box.engine
        XCTAssertEqual(engine.openChatCalls, [])

        await openLobby(controller, shown: false)
        XCTAssertEqual(engine.openChatCalls, [nil])

        controller.setShown(true)
        XCTAssertEqual(engine.openChatCalls, [nil], "not while the app is in the background")

        controller.setResumed(true)
        XCTAssertEqual(engine.openChatCalls, [nil, lobby])

        controller.setResumed(false)
        XCTAssertEqual(engine.openChatCalls, [nil, lobby, nil])

        controller.setResumed(true)
        XCTAssertEqual(engine.openChatCalls, [nil, lobby, nil, lobby])

        controller.setShown(false)
        XCTAssertEqual(engine.openChatCalls, [nil, lobby, nil, lobby, nil])

        controller.setShown(false)
        XCTAssertEqual(engine.openChatCalls, [nil, lobby, nil, lobby, nil], "nothing is reported twice")
    }

    func testUnreadMessagesAreCountedUntilTheirChatIsOnScreen() async {
        let chat = await makeConnected()
        let controller = chat.controller
        let store = chat.store
        let engine = box.engine
        await openLobby(controller, shown: false)

        engine.receive(incoming("a"))
        engine.receive(incoming("b"))
        engine.receive(incoming("x", chat: gaming))
        await assertEventually {
            controller.unread == [lobby: 2, gaming: 1] && controller.local.messages.count == 2
        }
        XCTAssertEqual(chatTabBadge(unread: controller.unread, session: store.state, chatTabOpen: false), 2)
        XCTAssertEqual(controller.local.messages.map(\.id), ["a", "b"], "messages of other chats are not shown here")
        XCTAssertTrue(engine.markReadCalls.isEmpty)

        controller.setShown(true)
        XCTAssertTrue(engine.markReadCalls.isEmpty, "not while the app is in the background")

        controller.setResumed(true)
        await assertEventually { controller.unread == [gaming: 1] }
        XCTAssertEqual(engine.markReadCalls, [lobby], "seen messages are marked read")
        XCTAssertEqual(engine.unread, [gaming: 1])
        XCTAssertEqual(chatTabBadge(unread: controller.unread, session: store.state, chatTabOpen: false), 0)
    }

    func testUnreadCountsFromBeforeTheConnectionAreShown() async {
        let chat = await makeConnected { $0.unread = [lobby: 5, gaming: 2] }
        let controller = chat.controller
        let store = chat.store
        await assertEventually { controller.unread == [lobby: 5, gaming: 2] }
        XCTAssertEqual(chatTabBadge(unread: controller.unread, session: store.state, chatTabOpen: false), 5)
        XCTAssertTrue(box.engine.markReadCalls.isEmpty)
    }

    func testUnreadCountsFollowTheEnginesEvents() async {
        let chat = await makeConnected()
        let controller = chat.controller
        let engine = box.engine

        engine.emit(.chatUnreadChanged(chat: gaming, count: 3))
        await assertEventually { controller.unread == [gaming: 3] }

        engine.emit(.chatUnreadChanged(chat: .session(session: 7), count: 1))
        await assertEventually { controller.unread == [gaming: 3, .session(session: 7): 1] }

        engine.emit(.chatUnreadChanged(chat: gaming, count: 0))
        await assertEventually { controller.unread == [.session(session: 7): 1] }
    }

    func testDisconnectingForgetsTheUnreadCounts() async {
        let chat = await makeConnected { $0.unread = [lobby: 2] }
        let controller = chat.controller
        let store = chat.store
        await assertEventually { controller.unread == [lobby: 2] }

        await store.disconnect()
        await assertEventually { controller.unread.isEmpty }
        XCTAssertEqual(chatTabBadge(unread: controller.unread, session: store.state, chatTabOpen: false), 0)
    }

    func testAMessageArrivingAtTheBottomIsFollowedAndRead() async {
        let chat = await makeConnected()
        let controller = chat.controller
        let engine = box.engine
        await openLobby(controller)
        let before = controller.local.scrollToken

        engine.receive(incoming("a"))
        await assertEventually {
            controller.local.messages.map(\.id) == ["a"] && engine.unread.isEmpty && controller.unread.isEmpty
        }
        XCTAssertEqual(controller.local.scrollToken, before + 1)
        XCTAssertEqual(controller.local.newBelow, 0)
        XCTAssertEqual(engine.markReadCalls.first, lobby)
    }

    func testAMessageArrivingWhileReadingFurtherUpShowsThePillAndStaysUnread() async {
        let chat = await makeConnected { $0.messages = [lobby: history(lobby, 30)] }
        let controller = chat.controller
        let engine = box.engine
        await openLobby(controller)
        controller.setAtBottom(false)
        let before = controller.local.scrollToken

        engine.receive(incoming("a"))
        await assertEventually { controller.local.newBelow == 1 && controller.unread == [lobby: 1] }
        XCTAssertEqual(controller.local.messages.last?.id, "a")
        XCTAssertEqual(controller.local.scrollToken, before)
        XCTAssertEqual(engine.unread, [lobby: 1])
        XCTAssertTrue(engine.markReadCalls.isEmpty)

        controller.scrollToNewest()
        XCTAssertEqual(controller.local.newBelow, 0)
        XCTAssertEqual(controller.local.scrollToken, before + 1)
        XCTAssertTrue(engine.markReadCalls.isEmpty, "the list has not arrived at the newest message yet")

        // The list reports that it arrived at the newest message.
        controller.setAtBottom(true)
        await assertEventually { engine.unread.isEmpty && controller.unread.isEmpty }
        XCTAssertEqual(engine.markReadCalls, [lobby])
    }

    func testMessagesArrivingWhileTheAppIsInTheBackgroundStayUnread() async {
        let chat = await makeConnected()
        let controller = chat.controller
        let engine = box.engine
        await openLobby(controller)
        controller.setResumed(false)

        engine.receive(incoming("a"))
        await assertEventually { controller.local.messages.map(\.id) == ["a"] && controller.unread == [lobby: 1] }
        XCTAssertEqual(engine.unread, [lobby: 1])
        XCTAssertTrue(engine.markReadCalls.isEmpty)
        XCTAssertEqual(controller.local.newBelow, 1)

        controller.setResumed(true)
        await assertEventually { engine.unread.isEmpty && controller.unread.isEmpty }
        XCTAssertEqual(engine.markReadCalls, [lobby])
    }

    // MARK: Writing

    func testTypingAnAtSignSuggestsPeopleAndPickingOneCompletesTheName() async throws {
        let chat = await makeConnected {
            $0.mentionNames = [
                MentionCandidate(session: ChatMockup.anna, name: "Anna"),
                MentionCandidate(session: Mockup.ben, name: "Ben"),
                MentionCandidate(session: nil, name: "channel"),
            ]
        }
        let controller = chat.controller
        await openLobby(controller)

        controller.changeDraft("Hi @", cursor: 4)
        await assertEventually { controller.local.mentions.map(\.name) == ["Anna", "Ben", "channel"] }
        XCTAssertEqual(try ui(chat).mentions.map(\.name), ["Anna", "Ben", "channel"])

        controller.changeDraft("Hi @an", cursor: 6)
        await assertEventually { controller.local.mentions.map(\.name) == ["Anna", "channel"] }

        controller.pickMention(try XCTUnwrap(controller.local.mentions.first))
        XCTAssertEqual(controller.local.draft, "Hi @Anna ")
        XCTAssertEqual(controller.local.cursor, 9)
        XCTAssertTrue(controller.local.mentions.isEmpty)

        controller.changeDraft("Hi @Anna x", cursor: 10)
        XCTAssertTrue(controller.local.mentions.isEmpty, "no list outside a mention")
    }

    func testLeavingAMentionPutsTheListAway() async {
        let chat = await makeConnected { $0.mentionNames = [MentionCandidate(session: ChatMockup.anna, name: "Anna")] }
        let controller = chat.controller
        await openLobby(controller)

        controller.changeDraft("@", cursor: 1)
        await assertEventually { controller.local.mentions.map(\.name) == ["Anna"] }

        controller.changeDraft("", cursor: 0)
        XCTAssertTrue(controller.local.mentions.isEmpty)
    }

    func testSendingClearsTheFieldAndAppendsTheOwnMessage() async throws {
        let chat = await makeConnected()
        let controller = chat.controller
        await openLobby(controller)
        controller.changeDraft("  Hello there  ", cursor: 15)
        XCTAssertTrue(try ui(chat).canSend)
        let before = controller.local.scrollToken

        controller.send()
        XCTAssertEqual(controller.local.draft, "", "the field is empty at once")
        XCTAssertEqual(controller.local.cursor, 0)
        XCTAssertEqual(controller.local.pending.map(\.content), [.text("Hello there", reply: nil)])
        XCTAssertNil(controller.local.pending.first?.failure)
        XCTAssertEqual(controller.local.scrollToken, before + 1)

        await assertEventually { controller.local.pending.isEmpty && controller.local.messages.count == 1 }
        let sent = try XCTUnwrap(controller.local.messages.first)
        XCTAssertTrue(sent.own)
        XCTAssertEqual(sent.content.plainText(), "Hello there")
        XCTAssertEqual(controller.local.scrollToken, before + 2)
        XCTAssertEqual(box.engine.messages[lobby]?.map(\.id), [sent.id])
    }

    func testNothingIsSentWhileTheFieldIsEmptyOrTheTextTooLong() async throws {
        let chat = await makeConnected { $0.limits = ChatLimits(text: 10, image: 0, allowHtml: true) }
        let controller = chat.controller
        await openLobby(controller)

        controller.send()
        controller.changeDraft("   ", cursor: 3)
        controller.send()
        XCTAssertTrue(controller.local.pending.isEmpty)
        XCTAssertEqual(controller.local.draft, "   ")

        controller.changeDraft("12345678901", cursor: 11)
        XCTAssertEqual(try ui(chat).charactersLeft, -1)
        XCTAssertFalse(try ui(chat).canSend)
        controller.send()
        XCTAssertTrue(controller.local.pending.isEmpty)
        XCTAssertEqual(controller.local.draft, "12345678901", "the text stays in the field")
        XCTAssertTrue((box.engine.messages[lobby] ?? []).isEmpty)
    }

    func testNothingIsSentWhileNoChatIsOpen() async {
        let chat = await makeConnected()
        let controller = chat.controller

        controller.changeDraft("Hello", cursor: 5)
        controller.send()
        XCTAssertTrue(controller.local.pending.isEmpty)
        XCTAssertEqual(controller.local.draft, "Hello")
    }

    func testAReplyCarriesTheQuoteOfTheMessageAndCanBeCancelled() async throws {
        let quote = ReplyQuote(name: "Anna", snippet: "can you host?")
        var original = incoming("a")
        original.quote = quote
        let stored = original
        let chat = await makeConnected { $0.messages = [lobby: [stored]] }
        let controller = chat.controller
        await openLobby(controller)

        controller.startReply(stored)
        XCTAssertEqual(controller.local.reply, ReplyTarget(messageId: "a", quote: quote))
        XCTAssertEqual(try ui(chat).reply, ReplyTarget(messageId: "a", quote: quote))
        controller.cancelReply()
        XCTAssertNil(controller.local.reply)

        controller.startReply(stored)
        controller.changeDraft("Sure", cursor: 4)
        controller.send()
        XCTAssertNil(controller.local.reply)
        XCTAssertEqual(controller.local.pending.map(\.content), [.text("Sure", reply: quote)])

        await assertEventually { controller.local.pending.isEmpty && controller.local.messages.count == 2 }
        XCTAssertEqual(controller.local.messages.last?.content.reply, quote)
    }

    func testAMessageThatDidNotGetOutKeepsItsTextAndCanBeRetried() async throws {
        let chat = await makeConnected()
        let controller = chat.controller
        let engine = box.engine
        await openLobby(controller)
        engine.sendFailure = .NotConnected(detail: "gone")
        controller.changeDraft("Important", cursor: 9)
        controller.send()
        await assertEventually { controller.local.pending.first?.failure == .notSent }

        let failed = try XCTUnwrap(controller.local.pending.first)
        XCTAssertEqual(controller.local.pending.count, 1)
        XCTAssertEqual(failed.content, .text("Important", reply: nil))
        XCTAssertTrue(try ui(chat).rows.contains(.pending(failed, showHeader: true)))

        controller.retry(failed.id)
        XCTAssertNil(controller.local.pending.first?.failure, "it is on its way again")
        await assertEventually { controller.local.pending.first?.failure == .notSent }
        XCTAssertEqual(controller.local.pending.count, 1, "still failing: still there")

        engine.sendFailure = nil
        controller.retry(failed.id)
        await assertEventually { controller.local.pending.isEmpty && controller.local.messages.count == 1 }
        XCTAssertEqual(controller.local.messages.first?.content.plainText(), "Important")
    }

    func testAMessageThatIsOnItsWayIsNeitherRetriedNorDiscarded() async {
        let chat = await makeConnected()
        let controller = chat.controller
        await openLobby(controller)
        controller.changeDraft("Once", cursor: 4)
        controller.send()
        let pendingId = controller.local.pending.first?.id ?? 0

        controller.retry(pendingId)
        controller.discard(pendingId)
        XCTAssertEqual(controller.local.pending.count, 1)

        await assertEventually { controller.local.pending.isEmpty && controller.local.messages.count == 1 }
        XCTAssertEqual(box.engine.messages[lobby]?.count, 1, "it was sent once")
    }

    func testAMessageTheServerRefusesCannotWorkOnASecondTry() async {
        let chat = await makeConnected()
        let controller = chat.controller
        let engine = box.engine
        await openLobby(controller)

        engine.sendFailure = .PermissionDenied(detail: "no")
        controller.changeDraft("Hello", cursor: 5)
        controller.send()
        await assertEventually { controller.local.pending.first?.failure == .refused }

        engine.sendFailure = .Io(detail: "disk")
        controller.changeDraft("Again", cursor: 5)
        controller.send()
        await assertEventually { controller.local.pending.last?.failure == .notSent }
        XCTAssertEqual(controller.local.pending.map(\.failure), [.refused, .notSent])
    }

    func testAFailedMessageCanBeDiscarded() async throws {
        let chat = await makeConnected()
        let controller = chat.controller
        await openLobby(controller)
        box.engine.sendFailure = .Io(detail: "x")
        controller.changeDraft("Never mind", cursor: 10)
        controller.send()
        await assertEventually { controller.local.pending.first?.failure == .notSent }

        controller.discard(try XCTUnwrap(controller.local.pending.first).id)
        XCTAssertTrue(controller.local.pending.isEmpty)
        XCTAssertTrue(try ui(chat).empty)
    }

    func testAPictureIsSentAndARefusedOneSaysSoPlainly() async throws {
        let chat = await makeConnected()
        let controller = chat.controller
        let engine = box.engine
        await openLobby(controller)

        controller.openImageSources()
        XCTAssertEqual(controller.local.sheet, .imageSource)
        XCTAssertEqual(try ui(chat).sheet, .imageSource)

        let picture = Data([1, 2, 3])
        controller.sendImage(bytes: picture, mime: "image/png")
        XCTAssertNil(controller.local.sheet)
        XCTAssertEqual(controller.local.pending.map(\.content), [.image(bytes: picture, mime: "image/png")])
        await assertEventually { controller.local.pending.isEmpty && controller.local.messages.count == 1 }
        XCTAssertEqual(controller.local.messages.first?.own, true)

        engine.sendFailure = .InvalidInput(detail: "too large")
        controller.sendImage(bytes: Data(count: 10), mime: "image/jpeg")
        await assertEventually { controller.local.pending.first?.failure == .refused }
        XCTAssertEqual(controller.local.pending.count, 1, "the size rules refuse it; trying again cannot work")

        controller.openImageSources()
        controller.imageUnreadable()
        XCTAssertNil(controller.local.sheet)
        XCTAssertEqual(controller.local.notice, .imageUnreadable)
        XCTAssertEqual(try ui(chat).notice, .imageUnreadable)
        controller.dismissNotice()
        XCTAssertNil(controller.local.notice)
    }

    // MARK: GIF favourites

    func testGifFavouritesAreListedSentRemovedAndAdded() async throws {
        let first = "https://gifs.example/a.gif"
        let second = "https://gifs.example/b.gif"
        let third = "https://gifs.example/c.gif"
        let chat = await makeConnected {
            $0.storedGifFavourites = [
                GifFavourite(url: first, addedAtMs: 0, lastUsedAtMs: 0),
                GifFavourite(url: second, addedAtMs: 0, lastUsedAtMs: 0),
            ]
        }
        let controller = chat.controller
        let engine = box.engine
        await openLobby(controller)

        controller.openGifs()
        XCTAssertEqual(controller.local.sheet, .gifs)
        await assertEventually { controller.local.gifs.map(\.url) == [first, second] }
        XCTAssertEqual(try ui(chat).sheet, .gifs(controller.local.gifs))

        controller.removeGif(first)
        XCTAssertEqual(controller.local.gifs.map(\.url), [second], "it leaves the list at once")
        await assertEventually { engine.storedGifFavourites.map(\.url) == [second] }
        XCTAssertEqual(controller.local.gifs.map(\.url), [second])

        controller.sendGif(second)
        XCTAssertNil(controller.local.sheet, "the sheet closes when a GIF is picked")
        XCTAssertEqual(controller.local.pending.map(\.content), [.gif(url: second)])
        await assertEventually { controller.local.pending.isEmpty && controller.local.messages.count == 1 }
        XCTAssertEqual(controller.local.messages.first?.content.plainText(), second)

        controller.addGif(third)
        await assertEventually { controller.local.notice == .gifSaved }
        XCTAssertEqual(try ui(chat).notice, .gifSaved)
        XCTAssertTrue(engine.storedGifFavourites.contains { $0.url == third })
        controller.dismissNotice()
        XCTAssertNil(controller.local.notice)
    }

    // MARK: Links and pictures

    func testAnOrdinaryLinkGoesStraightToTheBrowser() async throws {
        let chat = await makeConnected()
        let controller = chat.controller
        await openLobby(controller)

        controller.tapLink(ChatMockup.rulesLink)
        XCTAssertEqual(controller.local.urlToOpen, "https://nordlicht.example/rules")
        XCTAssertNil(controller.local.linkWarning)
        XCTAssertEqual(try ui(chat).urlToOpen, "https://nordlicht.example/rules")
        XCTAssertNil(try ui(chat).sheet)

        controller.urlOpened()
        XCTAssertNil(controller.local.urlToOpen)
    }

    func testAMisleadingLinkShowsItsRealTargetAndOpensOnlyAfterConfirming() async throws {
        let chat = await makeConnected()
        let controller = chat.controller
        await openLobby(controller)
        let link = SafeLink(
            url: "https://bank.example.evil.test/login",
            host: "bank.example.evil.test",
            kind: .web,
            misleading: true
        )

        controller.tapLink(link)
        XCTAssertEqual(controller.local.linkWarning, link)
        XCTAssertEqual(try ui(chat).sheet, .linkWarning(link))
        XCTAssertNil(controller.local.urlToOpen, "nothing is opened before the user decides")

        controller.dismissSheet()
        XCTAssertNil(controller.local.linkWarning)
        XCTAssertNil(try ui(chat).sheet)
        XCTAssertNil(controller.local.urlToOpen)

        controller.tapLink(link)
        controller.confirmLink()
        XCTAssertNil(controller.local.linkWarning)
        XCTAssertNil(try ui(chat).sheet)
        XCTAssertEqual(controller.local.urlToOpen, "https://bank.example.evil.test/login")
    }

    func testConfirmingWithoutAWarningOpensNothing() async {
        let chat = await makeConnected()
        let controller = chat.controller

        controller.confirmLink()
        XCTAssertNil(controller.local.urlToOpen)
    }

    func testAPictureOpensInTheViewerAndCloses() async throws {
        let chat = await makeConnected()
        let controller = chat.controller
        await openLobby(controller)
        let source = ChatImageSource.embedded(chat: lobby, messageId: "m1", imageId: "i1", width: 10, height: 10)

        controller.openViewer(source)
        XCTAssertEqual(controller.local.viewer, source)
        XCTAssertEqual(try ui(chat).viewer, source)

        controller.closeViewer()
        XCTAssertNil(controller.local.viewer)
    }
}
