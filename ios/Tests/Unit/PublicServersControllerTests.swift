// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest
@testable import Mumcord

/// The public server browser through its controller and the model, against the fake engine with
/// a list made up for the test, as `PublicServersControllerTest` on Android. Nothing here
/// contacts the real list service or a listed server.
@MainActor
final class PublicServersControllerTests: XCTestCase {
    private let box = EngineBox()
    // More than one page of the bridge, so reading the list in pages is covered.
    private let listed = (0..<130).map { publicServer("Server \($0)", "s\($0).example") }

    private func listedStore(configure: @escaping @Sendable (FakeEngine) -> Void = { _ in }) async -> SessionStore {
        let listed = listed
        let store = makeStore(box, server: mockupServer()) { engine in
            engine.publicServers = listed
            configure(engine)
        }
        await store.loadServers()
        return store
    }

    private func controller(configure: @escaping @Sendable (FakeEngine) -> Void = { _ in }) async -> (SessionStore, PublicServersController) {
        let store = await listedStore(configure: configure)
        return (store, PublicServersController(store: store))
    }

    /// Opens the browser and waits for the list.
    private func open(_ controller: PublicServersController) async {
        controller.open()
        await assertEventually { controller.local.servers != nil }
    }

    /// Gives the pings a moment to be sent, for the checks that none is.
    private func settle() async {
        try? await Task.sleep(nanoseconds: 100_000_000)
    }

    func testNothingIsRequestedBeforeTheBrowserOpensAndThenTheWholeListOnce() async {
        let (_, controller) = await controller()
        XCTAssertEqual(box.engine.publicDownloads, 0)

        controller.open()
        XCTAssertTrue(controller.local.loading)
        await assertEventually { controller.local.servers == self.listed }
        XCTAssertFalse(controller.local.loading)
        XCTAssertEqual(box.engine.publicDownloads, 1)

        controller.close()
        controller.open()
        await settle()
        XCTAssertEqual(box.engine.publicDownloads, 1, "the list loaded before is shown again")
    }

    func testAFailedFirstLoadIsAFailureAndTryAgainLoads() async {
        let (_, controller) = await controller { $0.failingPublicDownloads = [1] }
        controller.open()
        await assertEventually { controller.local.loadFailed }
        XCTAssertNil(controller.local.servers)
        XCTAssertEqual(controller.uiState.status, .failed)

        controller.refresh()
        await assertEventually { controller.local.servers?.count == 130 }
        XCTAssertFalse(controller.local.loadFailed)
    }

    func testAFailedRefreshKeepsTheLastList() async {
        let (_, controller) = await controller { $0.failingPublicDownloads = [2] }
        await open(controller)

        controller.refresh()
        XCTAssertTrue(controller.uiState.refreshing)
        await assertEventually { controller.local.refreshFailed }

        XCTAssertEqual(box.engine.publicDownloads, 2)
        XCTAssertEqual(controller.local.servers, listed)
        XCTAssertFalse(controller.local.loadFailed)
        XCTAssertEqual(controller.uiState.status, .ready)
    }

    func testARefreshPingsTheRowsOnScreenAgain() async {
        let (_, controller) = await controller()
        await open(controller)
        controller.showing([listed[0].key])
        await assertEventually { self.box.engine.publicPingRequests.count == 1 }

        controller.refresh()
        await assertEventually { self.box.engine.publicPingRequests == ["s0.example:64738", "s0.example:64738"] }
        XCTAssertEqual(box.engine.publicDownloads, 2)
    }

    func testVisibleRowsArePingedAndTheAnswersShowAndClosingStopsThePings() async {
        let (_, controller) = await controller {
            $0.publicPings["s1.example:64738"] = ServerPing(latencyMs: 30, peopleOnline: 4, maxPeople: 40)
        }
        await open(controller)

        controller.showing([listed[1].key, listed[2].key])
        await assertEventually { controller.local.pings.count == 2 }
        XCTAssertEqual(box.engine.publicPingRequests, ["s1.example:64738", "s2.example:64738"])
        XCTAssertEqual(
            controller.local.pings,
            [listed[1].key: .answered(people: 4, maxPeople: 40, latencyMs: 30), listed[2].key: .noAnswer]
        )

        controller.close()
        controller.showing([listed[3].key])
        await settle()
        XCTAssertEqual(box.engine.publicPingRequests.count, 2)
    }

    func testPausedPingsWaitForTheScreenToComeBack() async {
        let (_, controller) = await controller()
        await open(controller)
        controller.pausePings()
        controller.showing([listed[0].key])
        await settle()
        XCTAssertTrue(box.engine.publicPingRequests.isEmpty)

        controller.resumePings()
        await assertEventually { self.box.engine.publicPingRequests == ["s0.example:64738"] }
    }

    func testRowsOnScreenWhenTheAppLeftAreAskedAgainWhenItComesBack() async {
        let (_, controller) = await controller { $0.publicPingDelay = .seconds(30) }
        await open(controller)
        controller.showing([listed[0].key])
        await assertEventually { self.box.engine.publicPingRequests.count == 1 }

        controller.pausePings()
        controller.resumePings()
        await assertEventually { self.box.engine.publicPingRequests.count == 2 }
    }

    func testAddingSavesTheServerWithTheNameOfTheDefaultIdentity() async {
        let (store, controller) = await controller()
        await open(controller)

        controller.add(listed[5].key)
        XCTAssertTrue(controller.local.adding.contains(listed[5].key))
        await assertEventually { controller.local.adding.isEmpty }

        XCTAssertEqual(
            box.engine.serverInputs,
            [ServerInput(name: "Server 5", address: "s5.example", port: 64738, username: "Lea", password: .keep, identityId: nil)]
        )
        await assertEventually {
            controller.uiState.rows.first { $0.name == "Server 5" }?.savedId == store.state.servers.first?.id
                && store.state.servers.count == 1
        }
    }

    func testAServerThatCannotBeAddedSaysSo() async {
        let (_, controller) = await controller { $0.saveFailure = .Internal(detail: "disk full") }
        await open(controller)

        controller.add(listed[5].key)
        await assertEventually { controller.local.addFailed }
        XCTAssertTrue(controller.local.adding.isEmpty)
        XCTAssertNil(controller.uiState.rows.first { $0.name == "Server 5" }?.savedId)
    }

    func testClosingForgetsSearchAndCountry() async {
        let (_, controller) = await controller()
        controller.open()
        controller.search("Server 1")
        controller.selectCountry("DE")
        controller.close()

        XCTAssertEqual(controller.local.search, "")
        XCTAssertNil(controller.local.country)
    }

    // MARK: Through the model

    func testTheBrowserOpensFromTheServerListAndConnectLeavesItForTheNormalFlow() async {
        let store = await listedStore()
        let model = AppModel(store: store, setupStore: InMemorySetupStore(firstStartDone: true))
        model.toggleEditing()
        XCTAssertEqual(box.engine.publicDownloads, 0)

        model.openPublicServers()
        XCTAssertEqual(model.uiState.screen, .publicServers)
        XCTAssertFalse(model.uiState.editing)
        await assertEventually { model.publicServers.local.servers != nil }

        model.publicServers.add(listed[7].key)
        await assertEventually { model.publicServers.uiState.rows.contains { $0.savedId != nil } }
        guard let savedId = model.publicServers.uiState.rows.first(where: { $0.name == "Server 7" })?.savedId else {
            return XCTFail("Server 7 was not added")
        }

        model.connectFromPublicServers(savedId)
        XCTAssertEqual(model.uiState.screen, .serverList)
        await assertEventually { self.box.engine.connectCalls.map(\.serverId) == [savedId] }
    }

    func testBackLeavesTheBrowser() async {
        let store = await listedStore()
        let model = AppModel(store: store, setupStore: InMemorySetupStore(firstStartDone: true))
        model.openPublicServers()
        model.publicServers.search("Server 1")
        model.closePublicServers()

        XCTAssertEqual(model.uiState.screen, .serverList)
        XCTAssertEqual(model.publicServers.local.search, "")
    }
}
