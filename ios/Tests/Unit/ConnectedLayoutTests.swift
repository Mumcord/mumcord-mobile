// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// When the connected screens use two columns, and what they then show (AC-5.31).
@MainActor
final class ConnectedLayoutTests: XCTestCase {
    private let box = EngineBox()
    private let lobby = ChatId.channel(channelId: Mockup.lobby)

    func testPhonesStayInOneColumnAlsoInLandscape() {
        // iPhone 17 and iPhone 17 Pro Max, upright and turned.
        XCTAssertEqual(connectedLayout(width: 402, height: 874), .oneColumn)
        XCTAssertEqual(connectedLayout(width: 874, height: 402), .oneColumn)
        XCTAssertEqual(connectedLayout(width: 440, height: 956), .oneColumn)
        XCTAssertEqual(connectedLayout(width: 956, height: 440), .oneColumn, "wide but low: the landscape stream keeps the screen")
    }

    func testIPadsUseTwoColumnsInPortraitAndLandscape() {
        // The 11-inch iPad of the mockups, the 13-inch one and the iPad mini, both ways round.
        XCTAssertEqual(connectedLayout(width: 1180, height: 820), .twoColumns)
        XCTAssertEqual(connectedLayout(width: 820, height: 1180), .twoColumns)
        XCTAssertEqual(connectedLayout(width: 1376, height: 1032), .twoColumns)
        XCTAssertEqual(connectedLayout(width: 744, height: 1133), .twoColumns)
        XCTAssertEqual(connectedLayout(width: 600, height: 480), .twoColumns)
    }

    func testANarrowSplitViewOrSlideOverWindowIsOneColumn() {
        // Half of an 11-inch iPad in landscape, a third of it, Slide Over, and a low window.
        XCTAssertEqual(connectedLayout(width: 590, height: 820), .oneColumn)
        XCTAssertEqual(connectedLayout(width: 375, height: 820), .oneColumn)
        XCTAssertEqual(connectedLayout(width: 320, height: 1180), .oneColumn)
        XCTAssertEqual(connectedLayout(width: 599, height: 1180), .oneColumn)
        XCTAssertEqual(connectedLayout(width: 1180, height: 479), .oneColumn)
    }

    func testTheAppHasOneWindowOnAnIPad() throws {
        // One engine per process: a second window would share the layout, the voice and the
        // streams of the first one.
        let manifest = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "UIApplicationSceneManifest") as? [String: Any])
        XCTAssertEqual(manifest["UIApplicationSupportsMultipleScenes"] as? Bool, false)
    }

    func testTheChatTabOfThePhoneIsPartOfChannelsInTwoColumns() {
        XCTAssertEqual(ConnectedTab.chat.inTwoColumns, .channels)
        XCTAssertEqual(ConnectedTab.channels.inTwoColumns, .channels)
        XCTAssertEqual(ConnectedTab.messages.inTwoColumns, .messages)
        XCTAssertEqual(ConnectedTab.more.inTwoColumns, .more)
        XCTAssertEqual(railTabs, [.channels, .messages, .more])
    }

    func testTheChannelChatIsOnScreenBesideTheChannelsOnlyInTwoColumns() {
        XCTAssertFalse(showsChannelChat(tab: .channels, twoColumns: false))
        XCTAssertTrue(showsChannelChat(tab: .channels, twoColumns: true))
        XCTAssertTrue(showsChannelChat(tab: .chat, twoColumns: false))
        XCTAssertTrue(showsChannelChat(tab: .chat, twoColumns: true))
        XCTAssertFalse(showsChannelChat(tab: .messages, twoColumns: true))
        XCTAssertFalse(showsChannelChat(tab: .more, twoColumns: true))
    }

    func testAStreamFillsTheRightColumnOfChannelsAndFloatsElsewhere() {
        let key = StreamKey(session: Mockup.mira, kind: .screen)
        let full = OpenStream(key: key, view: .full)
        XCTAssertTrue(streamInColumn(full, tab: .channels))
        XCTAssertTrue(streamInColumn(full, tab: .chat), "the phone's Chat tab is Channels here")
        XCTAssertFalse(streamInColumn(full, tab: .messages))
        XCTAssertFalse(streamInColumn(full, tab: .more))
        XCTAssertFalse(streamInColumn(OpenStream(key: key, view: .mini), tab: .channels))
        XCTAssertFalse(streamInColumn(OpenStream(key: key, view: .whole), tab: .channels))
        XCTAssertFalse(streamInColumn(nil, tab: .channels))
    }

    func testOnlyTheWholeScreenCoversTwoColumnsWhileAPhoneShowsFullStreamsFullScreen() {
        let key = StreamKey(session: Mockup.mira, kind: .screen)
        XCTAssertTrue(streamCoversWindow(OpenStream(key: key, view: .full), twoColumns: false))
        XCTAssertFalse(streamCoversWindow(OpenStream(key: key, view: .full), twoColumns: true))
        XCTAssertTrue(streamCoversWindow(OpenStream(key: key, view: .whole), twoColumns: true))
        XCTAssertTrue(streamCoversWindow(OpenStream(key: key, view: .whole), twoColumns: false), "a window made narrow keeps it")
        XCTAssertFalse(streamCoversWindow(OpenStream(key: key, view: .mini), twoColumns: false))
        XCTAssertFalse(streamCoversWindow(nil, twoColumns: true))
    }

    func testTheStripUnderAStreamShowsTheTwoNewestMessages() {
        let rows: [ChatRow] = [
            .day(ChatDay(year: 2026, month: 5, day: 4)),
            .message(chatMessage(id: "1", chat: lobby, senderName: "Anna"), showHeader: true),
            .message(chatMessage(id: "2", chat: lobby, senderName: "Ben"), showHeader: true),
            .hint(UnsupportedHint(id: 1, personName: "Jonas", kind: .fileTransfer)),
            .message(chatMessage(id: "3", chat: lobby, senderName: "Mira"), showHeader: true),
        ]
        XCTAssertEqual(stripMessages(rows).map(\.id), ["2", "3"])
        XCTAssertEqual(stripMessages(Array(rows.prefix(1))).map(\.id), [])
    }

    func testBesideTheChannelsTheChannelChatCountsAsReadAndTheTabIsKeptAcrossLayouts() async {
        let store = makeStore(box, server: mockupServer(), servers: [savedServer(1, "Nordlicht")])
        let model = AppModel(store: store, setupStore: InMemorySetupStore(firstStartDone: true))
        await assertEventually { model.uiState.session.serversLoaded }
        model.connect(1)
        await assertEventually { model.uiState.connected != nil }
        model.chat.setResumed(true)
        let engine = box.engine
        engine.receive(chatMessage(id: "a", chat: lobby, senderName: "Anna", senderSession: 1))
        await assertEventually { model.chatBadge == 1 }
        XCTAssertTrue(engine.markReadCalls.isEmpty, "the Channels tab of a phone does not show the chat")

        model.setTwoColumns(true)
        XCTAssertEqual(model.chatBadge, 0, "no number for a chat on screen")
        await assertEventually("beside the channels the chat is read") { engine.markReadCalls == [self.lobby] }

        // The Messages tab takes the right column: the channel chat is no longer on screen.
        model.selectTab(.messages)
        engine.receive(chatMessage(id: "b", chat: lobby, senderName: "Anna", senderSession: 1))
        await assertEventually { model.chatBadge == 1 }

        // A Chat tab chosen on the phone survives the window becoming wide and narrow again.
        model.setTwoColumns(false)
        model.selectTab(.chat)
        model.setTwoColumns(true)
        XCTAssertEqual(model.uiState.connected?.tab, .chat)
        model.setTwoColumns(false)
        XCTAssertEqual(model.uiState.connected?.tab, .chat)
        await assertEventually { model.chatBadge == 0 }
    }
}
