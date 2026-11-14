// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

final class ConnectedStateTests: XCTestCase {
    private let server = mockupServer()
    private lazy var root = buildChannelTree(channels: server.channels, people: server.people)
    private lazy var session = [
        BridgeEvent.connectionChanged(state: .connected),
        BridgeEvent.serverSynced(server: server),
    ].reduce(SessionState()) { state, event in state.fold(event) }

    private func listRows(collapsed: Set<UInt32> = [], query: String = "") -> [ChannelListRow] {
        channelRows(root: root, selfChannelId: Mockup.lobby, collapsed: collapsed, query: query)
    }

    private func names(_ rows: [ChannelListRow]) -> [String] {
        rows.map { row in
            switch row {
            case .channel(let item): "#\(item.name)"
            case .person(let item): item.name
            }
        }
    }

    private func channelItem(_ rows: [ChannelListRow], _ id: UInt32) throws -> ChannelItem {
        let items = rows.compactMap { row -> ChannelItem? in
            if case .channel(let item) = row { return item }
            return nil
        }
        return try XCTUnwrap(items.first { $0.id == id })
    }

    private func personItem(_ rows: [ChannelListRow], _ name: String) throws -> PersonItem {
        let items = rows.compactMap { row -> PersonItem? in
            if case .person(let item) = row { return item }
            return nil
        }
        return try XCTUnwrap(items.first { $0.name == name })
    }

    private func connectedState(_ session: SessionState, _ local: ConnectedLocal = ConnectedLocal()) throws -> ConnectedUiState {
        try XCTUnwrap(connectedUiState(session: session, local: local, serverName: "Nordlicht"))
    }

    func testTheRootComesFirstThenChannelsByPositionWithTheirPeopleByName() {
        XCTAssertEqual(
            names(listRows()),
            ["#Nordlicht", "#Lobby", "Anna", "Ben", "Lea", "#Gaming", "Jonas", "Mira", "#Music", "#AFK"]
        )
    }

    func testTheRootAndItsChannelsShareTheFirstLevelAndDeeperOnesAreIndented() throws {
        let deep = buildChannelTree(
            channels: server.channels + [channel(9, "Raid", parentId: Mockup.gaming)],
            people: server.people + [person(20, "Zoe", channelId: 9)]
        )
        let rows = channelRows(root: deep, selfChannelId: Mockup.lobby, collapsed: [])
        XCTAssertEqual(try channelItem(rows, Mockup.root).depth, 0)
        XCTAssertEqual(try channelItem(rows, Mockup.gaming).depth, 0)
        XCTAssertEqual(try channelItem(rows, 9).depth, 1)
        XCTAssertEqual(try personItem(rows, "Mira").depth, 1)
        XCTAssertEqual(try personItem(rows, "Zoe").depth, 2)
    }

    func testChannelsCarryCountOwnChannelLockAndWhetherTheyFold() throws {
        let rows = listRows()
        let lobby = try channelItem(rows, Mockup.lobby)
        XCTAssertEqual(lobby.peopleCount, 3)
        XCTAssertTrue(lobby.current)
        XCTAssertEqual(lobby.expanded, true)
        let music = try channelItem(rows, Mockup.music)
        XCTAssertEqual(music.peopleCount, 0)
        XCTAssertTrue(music.locked)
        XCTAssertNil(music.expanded, "An empty channel has nothing to fold")
        XCTAssertFalse(try channelItem(rows, Mockup.gaming).current)
    }

    func testPeopleCarryTheirStateIcons() throws {
        let rows = listRows()
        XCTAssertTrue(try personItem(rows, "Ben").talking)
        XCTAssertTrue(try personItem(rows, "Lea").isSelf)
        XCTAssertEqual(try personItem(rows, "Mira").state, PersonFlags(streaming: true))
        XCTAssertEqual(try personItem(rows, "Jonas").state, PersonFlags(muted: true, deafened: true))
    }

    func testACollapsedChannelHidesItsPeopleAndSubChannelsButKeepsItsCount() throws {
        let rows = listRows(collapsed: [Mockup.lobby])
        XCTAssertEqual(names(rows), ["#Nordlicht", "#Lobby", "#Gaming", "Jonas", "Mira", "#Music", "#AFK"])
        XCTAssertEqual(try channelItem(rows, Mockup.lobby).expanded, false)
        XCTAssertEqual(try channelItem(rows, Mockup.lobby).peopleCount, 3)
        XCTAssertEqual(names(self.listRows(collapsed: [Mockup.root])), ["#Nordlicht"])
    }

    func testSearchFindsPeopleAndChannelsIgnoringCaseAndKeepsTheirParents() {
        XCTAssertEqual(names(listRows(query: "mIR")), ["#Nordlicht", "#Gaming", "Mira"])
        XCTAssertEqual(names(listRows(query: " music ")), ["#Nordlicht", "#Music"])
        XCTAssertEqual(names(listRows(query: "nobody")), [])
    }

    func testSearchIgnoresCollapsedChannelsAndOffersNoFolding() throws {
        let rows = listRows(collapsed: [Mockup.gaming], query: "jonas")
        XCTAssertEqual(names(rows), ["#Nordlicht", "#Gaming", "Jonas"])
        XCTAssertNil(try channelItem(rows, Mockup.gaming).expanded)
        XCTAssertEqual(
            try channelItem(rows, Mockup.gaming).peopleCount,
            2,
            "The count is of the channel, not of the matches"
        )
    }

    func testNoServerMeansNoConnectedScreens() {
        XCTAssertNil(connectedUiState(session: SessionState(), local: ConnectedLocal(), serverName: "Nordlicht"))
    }

    func testTheHeaderNamesTheServerAndCountsEverybodyOnline() throws {
        let state = try connectedState(session)
        XCTAssertEqual(state.serverName, "Nordlicht")
        XCTAssertEqual(state.peopleOnline, 5)
        XCTAssertNil(state.sheet)
        XCTAssertNil(state.denied)
    }

    func testTheSearchTextOnlyFiltersWhileSearchIsOpen() throws {
        var local = ConnectedLocal()
        local.query = "mira"
        XCTAssertEqual(try connectedState(session, local).rows.count, 10)
        local.searching = true
        XCTAssertEqual(try connectedState(session, local).rows.count, 3)
    }

    func testThePersonSheetShowsThePersonWithTheSliderPositionWhileItMoves() throws {
        var local = ConnectedLocal()
        local.personSession = Mockup.jonas
        XCTAssertEqual(
            try connectedState(session, local).sheet,
            .person(PersonSheetState(
                session: Mockup.jonas,
                name: "Jonas",
                channelName: "Gaming",
                volumePercent: 100,
                locallyMuted: false,
                state: PersonFlags(muted: true, deafened: true)
            ))
        )
        local.volumeDraft = 150
        guard case .person(let moving) = try connectedState(session, local).sheet else {
            return XCTFail("the person sheet is not open")
        }
        XCTAssertEqual(moving.volumePercent, 150)
    }

    func testThePersonSheetClosesWhenThePersonLeaves() throws {
        let left = session.fold(.personRemoved(session: Mockup.jonas))
        var local = ConnectedLocal()
        local.personSession = Mockup.jonas
        XCTAssertNil(try connectedState(left, local).sheet)
    }

    func testTheChannelMenuOffersJoinExceptForTheOwnChannelAndFoldingWhenThereIsSomethingToFold() throws {
        func menu(_ id: UInt32, collapsed: Set<UInt32> = []) throws -> ConnectedSheet? {
            var local = ConnectedLocal()
            local.menuChannelId = id
            local.collapsed = collapsed
            return try connectedState(session, local).sheet
        }
        XCTAssertEqual(
            try menu(Mockup.lobby),
            .channelMenu(id: Mockup.lobby, name: "Lobby", expanded: true, canJoin: false)
        )
        XCTAssertEqual(
            try menu(Mockup.gaming, collapsed: [Mockup.gaming]),
            .channelMenu(id: Mockup.gaming, name: "Gaming", expanded: false, canJoin: true)
        )
        XCTAssertEqual(
            try menu(Mockup.music),
            .channelMenu(id: Mockup.music, name: "Music", expanded: nil, canJoin: true)
        )
    }

    func testAPasswordQuestionComesBeforeEveryOtherSheetAndSaysWhenThePasswordWasWrong() throws {
        let asked = session.fold(.channelPasswordChanged(channelId: Mockup.music, state: .wrong))
        var local = ConnectedLocal()
        local.personSession = Mockup.jonas
        local.password = "abc"
        XCTAssertEqual(
            try connectedState(asked, local).sheet,
            .channelPassword(channelName: "Music", wrong: true, password: "abc")
        )
    }

    func testARefusalNamesTheChannelAndKeepsTheServersWords() throws {
        let refused = session.fold(.actionDenied(channelId: Mockup.music, detail: "Permission denied."))
        XCTAssertEqual(
            try connectedState(refused).denied,
            DeniedBanner(channelName: "Music", detail: "Permission denied.")
        )
        let general = session.fold(.actionDenied(channelId: nil, detail: ""))
        XCTAssertEqual(try connectedState(general).denied, DeniedBanner(channelName: nil, detail: ""))
    }

    func testHintsOfTheSessionReachTheScreen() throws {
        let hint = UnsupportedHint(id: 1, personName: "Jonas", kind: .fileTransfer)
        XCTAssertEqual(try connectedState(session.withHint(hint)).hints, [hint])
    }

    func testInitialsAndAvatarColourFollowTheName() {
        XCTAssertEqual("Nordlicht".initials(2), "NO")
        XCTAssertEqual("jonas".initials(1), "J")
        XCTAssertEqual("<>".initials(1), "?")
        XCTAssertEqual(avatarSlot("Jonas"), avatarSlot("Jonas"))
        XCTAssertTrue((1...8).contains(avatarSlot("Jonas")))
    }
}
