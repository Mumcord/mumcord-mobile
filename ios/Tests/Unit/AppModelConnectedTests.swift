// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// The connected screens' actions on the model, against the fake engine.
@MainActor
final class AppModelConnectedTests: XCTestCase {
    private let box = EngineBox()

    private var engine: FakeEngine { box.engine }

    /// A model that is already connected to the mockup server.
    private func makeConnected(configure: @escaping @Sendable (FakeEngine) -> Void = { _ in }) async -> AppModel {
        let store = makeStore(
            box,
            server: mockupServer(),
            servers: [savedServer(1, "Nordlicht")],
            configure: configure
        )
        let model = AppModel(store: store, setupStore: InMemorySetupStore(firstStartDone: true))
        await assertEventually { model.uiState.session.serversLoaded }
        model.connect(1)
        await assertEventually { model.uiState.connected != nil }
        return model
    }

    private func shell(_ model: AppModel) throws -> ConnectedUiState {
        try XCTUnwrap(model.uiState.connected)
    }

    private func channelOf(_ model: AppModel, _ session: UInt32) -> UInt32? {
        model.uiState.session.server?.people[session]?.channelId
    }

    private func personSheet(_ model: AppModel) -> PersonSheetState? {
        if case .person(let sheet) = model.uiState.connected?.sheet { return sheet }
        return nil
    }

    func testConnectingShowsTheChannelsTabOfTheServer() async throws {
        let model = await makeConnected()
        XCTAssertEqual(model.uiState.screen, .channels)
        XCTAssertEqual(try shell(model).tab, .channels)
        XCTAssertEqual(try shell(model).serverName, "Nordlicht")
    }

    func testTappingAChannelJoinsItAndTheOwnChannelIsLeftAlone() async throws {
        let model = await makeConnected()
        model.join(Mockup.lobby)
        XCTAssertEqual(engine.joinCalls.count, 0)

        model.join(Mockup.gaming)
        await assertEventually { self.engine.joinCalls.count == 1 }
        XCTAssertEqual(engine.joinCalls.map(\.channelId), [Mockup.gaming])
        XCTAssertEqual(engine.joinCalls.map(\.password), [nil])
        await assertEventually { self.channelOf(model, Mockup.lea) == Mockup.gaming }
        await assertEventually {
            let rows = model.uiState.connected?.rows ?? []
            return rows.contains { row in
                if case .channel(let item) = row { return item.id == Mockup.gaming && item.current }
                return false
            }
        }
    }

    func testAChannelPasswordIsAskedForRefusedWhenWrongAndLetsInWhenRight() async throws {
        let model = await makeConnected { $0.channelPasswords[Mockup.music] = "secret" }
        model.join(Mockup.music)
        await assertEventually {
            model.uiState.connected?.sheet == .channelPassword(channelName: "Music", wrong: false, password: "")
        }

        model.submitChannelPassword()
        XCTAssertEqual(engine.joinCalls.count, 1, "An empty password is not sent")

        model.changeChannelPassword("guess")
        model.submitChannelPassword()
        await assertEventually {
            model.uiState.connected?.sheet == .channelPassword(channelName: "Music", wrong: true, password: "")
        }

        model.changeChannelPassword("secret")
        model.submitChannelPassword()
        await assertEventually { model.uiState.connected?.sheet == nil && self.channelOf(model, Mockup.lea) == Mockup.music }
        XCTAssertEqual(engine.joinCalls.map(\.password), [nil, "guess", "secret"])
    }

    func testGivingUpOnAChannelPasswordClosesTheSheetAndForgetsWhatWasTyped() async throws {
        let model = await makeConnected { $0.channelPasswords[Mockup.music] = "secret" }
        model.join(Mockup.music)
        await assertEventually { model.uiState.session.channelPassword != nil }
        model.changeChannelPassword("gue")

        model.dismissSheet()

        await assertEventually { model.uiState.connected?.sheet == nil }
        XCTAssertEqual(model.connectedLocal.password, "")
        XCTAssertEqual(channelOf(model, Mockup.lea), Mockup.lobby)
    }

    func testARefusedJoinShowsABannerUntilItIsPutAwayOrTheNextJoin() async throws {
        let model = await makeConnected { $0.deniedChannels.insert(Mockup.afk) }
        model.join(Mockup.afk)
        await assertEventually {
            model.uiState.connected?.denied == DeniedBanner(channelName: "AFK", detail: "Permission denied.")
        }
        model.dismissDenied()
        await assertEventually { model.uiState.connected?.denied == nil }

        model.join(Mockup.afk)
        await assertEventually { model.uiState.connected?.denied != nil }
        model.join(Mockup.gaming)
        await assertEventually { model.uiState.connected?.denied == nil }
    }

    func testFoldingAChannelTogglesAndClosesTheMenu() async throws {
        let model = await makeConnected()
        model.openChannelMenu(Mockup.gaming)
        XCTAssertEqual(
            try shell(model).sheet,
            .channelMenu(id: Mockup.gaming, name: "Gaming", expanded: true, canJoin: true)
        )
        model.toggleCollapsed(Mockup.gaming)
        XCTAssertNil(try shell(model).sheet)
        XCTAssertEqual(model.connectedLocal.collapsed, [Mockup.gaming])
        model.toggleCollapsed(Mockup.gaming)
        XCTAssertEqual(model.connectedLocal.collapsed, [])
    }

    func testThePersonSheetSetsVolumeAndMuteForTheUserOnly() async throws {
        let model = await makeConnected()
        model.openPerson(Mockup.jonas)
        model.changeVolume(250)
        model.setLocalMute(true)
        await assertEventually {
            guard let sheet = self.personSheet(model) else { return false }
            return sheet.volumePercent == 200 && sheet.locallyMuted
        }
        XCTAssertEqual(engine.volumeCalls.map(\.session), [Mockup.jonas])
        XCTAssertEqual(engine.volumeCalls.map(\.percent), [200])

        model.dismissSheet()
        XCTAssertNil(try shell(model).sheet)
        XCTAssertNil(model.connectedLocal.volumeDraft)
    }

    func testTheOwnEntryOpensNoPersonSheet() async throws {
        let model = await makeConnected()
        model.openPerson(Mockup.lea)
        XCTAssertNil(try shell(model).sheet)
    }

    func testSearchFiltersAndClosingItShowsEverythingAgain() async throws {
        let model = await makeConnected()
        model.openSearch()
        model.changeQuery("ben")
        XCTAssertEqual(try shell(model).rows.count, 3)
        model.closeSearch()
        XCTAssertEqual(try shell(model).rows.count, 10)
        XCTAssertEqual(try shell(model).query, "")
    }

    func testDisconnectingLeavesTheConnectedScreensAndStartsThemAfresh() async throws {
        let model = await makeConnected()
        model.selectTab(.more)
        model.toggleCollapsed(Mockup.gaming)
        XCTAssertEqual(try shell(model).tab, .more)

        model.disconnect()

        await assertEventually { model.uiState.connected == nil && model.connectedLocal == ConnectedLocal() }
        XCTAssertEqual(model.uiState.screen, .serverList)
    }
}
