// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// The server cannot be reached at the next try to connect.
private func failNextConnect(_ engine: FakeEngine) {
    engine.connectFailure = .ServerUnreachable(detail: "unreachable")
}

/// Connecting to a server on the local network (FR-5.8): the explanation before iOS asks, and
/// what the error says when such a server cannot be reached.
@MainActor
final class LocalNetworkFlowTests: XCTestCase {
    private let box = EngineBox()
    private let home: Int64 = 1
    private let vpn: Int64 = 2
    private let internet: Int64 = 3

    private var engine: FakeEngine { box.engine }

    private func gateway(explained: Bool = false, refused: Bool = false) -> FakeLocalNetworkGateway {
        let wifi = IpAddress(bytes: [192, 168, 1, 37])
        return FakeLocalNetworkGateway(
            prefixes: [IpPrefix(address: wifi, length: 24)],
            addresses: [
                "home.example": [IpAddress(bytes: [192, 168, 1, 20])],
                "vpn.example": [IpAddress(bytes: [10, 8, 0, 3])],
                "mumble.example": [IpAddress(bytes: [203, 0, 113, 9])],
            ],
            explained: explained,
            refused: refused
        )
    }

    private func makeModel(
        _ localNetwork: FakeLocalNetworkGateway,
        configure: @escaping @Sendable (FakeEngine) -> Void = { _ in }
    ) async -> (AppModel, PhoneFakes) {
        let fakes = PhoneFakes(localNetwork: localNetwork)
        let store = makeStore(
            box,
            server: mockupServer(),
            servers: [
                savedServer(home, "Home", address: "home.example"),
                savedServer(vpn, "Office", address: "vpn.example"),
                savedServer(internet, "Nordlicht", address: "mumble.example"),
            ],
            configure: configure
        )
        let model = fakes.makeModel(store: store, setupStore: InMemorySetupStore(firstStartDone: true))
        await assertEventually { model.uiState.session.serversLoaded }
        return (model, fakes)
    }

    func testALocalServerIsExplainedFirstAndConnectsOnceContinued() async {
        let (model, fakes) = await makeModel(gateway())
        model.connect(home)
        await assertEventually { model.uiState.overlay == .localNetwork(target: ConnectTarget(serverId: 1), serverName: "Home") }
        XCTAssertEqual(engine.connectCalls, [])
        XCTAssertFalse(fakes.localNetwork.explained)

        model.continueLocalNetwork()
        await assertEventually { model.uiState.connected != nil }
        XCTAssertNil(model.uiState.overlay)
        XCTAssertTrue(fakes.localNetwork.explained)
        XCTAssertEqual(engine.connectCalls.map(\.serverId), [home])
    }

    func testTheExplanationIsShownOnlyOnce() async {
        let (model, _) = await makeModel(gateway(explained: true))
        model.connect(home)
        await assertEventually { model.uiState.connected != nil }
        XCTAssertNil(model.uiState.overlay)
    }

    func testNotNowLeavesTheServerListAsItWas() async {
        let (model, fakes) = await makeModel(gateway())
        model.connect(home)
        await assertEventually { model.uiState.overlay != nil }
        model.dismissOverlay()
        XCTAssertNil(model.uiState.overlay)
        XCTAssertNil(model.uiState.connectFailure)
        XCTAssertEqual(engine.connectCalls, [])
        XCTAssertFalse(model.uiState.serverRows.contains(where: \.connecting))
        // Nothing was asked, so the next try explains again.
        XCTAssertFalse(fakes.localNetwork.explained)
    }

    func testALocalServerThatCannotBeReachedSaysThatAccessMayBeMissing() async {
        let (model, fakes) = await makeModel(gateway(explained: true), configure: failNextConnect)
        model.connect(home)
        await assertEventually { model.uiState.connectFailure != nil }
        await assertEventually { fakes.localNetwork.probes == 1 }
        XCTAssertEqual(
            model.uiState.connectFailure,
            ConnectFailure(error: .serverUnreachable, serverName: "Home", localNetwork: .suspected)
        )
    }

    func testWhenTheSystemSaysAccessIsRefusedTheErrorSaysSo() async {
        let (model, _) = await makeModel(gateway(explained: true, refused: true), configure: failNextConnect)
        model.connect(home)
        await assertEventually {
            model.uiState.connectFailure
                == ConnectFailure(error: .serverUnreachable, serverName: "Home", localNetwork: .denied)
        }
    }

    func testTryAgainConnectsAgainWithoutExplainingASecondTime() async {
        let (model, _) = await makeModel(gateway(explained: true, refused: true), configure: failNextConnect)
        model.connect(home)
        await assertEventually { model.uiState.connectFailure?.localNetwork == .denied }
        model.retry()
        await assertEventually { model.uiState.connected != nil }
        XCTAssertNil(model.uiState.overlay)
        XCTAssertEqual(engine.connectCalls.map(\.serverId), [home])
    }

    func testDismissingTheErrorForgetsTheHint() async {
        let (model, _) = await makeModel(gateway(explained: true, refused: true), configure: failNextConnect)
        model.connect(home)
        await assertEventually { model.uiState.connectFailure?.localNetwork == .denied }
        model.dismissError()
        await assertEventually { model.uiState.connectFailure == nil }
    }

    func testAnInternetServerConnectsWithoutAnyQuestion() async {
        let (model, _) = await makeModel(gateway())
        model.connect(internet)
        await assertEventually { model.uiState.connected != nil }
        XCTAssertNil(model.uiState.overlay)
    }

    func testAnInternetServerThatCannotBeReachedDoesNotMentionTheLocalNetwork() async {
        let (model, fakes) = await makeModel(gateway(refused: true), configure: failNextConnect)
        model.connect(internet)
        await assertEventually { model.uiState.connectFailure != nil }
        XCTAssertEqual(
            model.uiState.connectFailure,
            ConnectFailure(error: .serverUnreachable, serverName: "Nordlicht", localNetwork: nil)
        )
        XCTAssertEqual(fakes.localNetwork.probes, 0)
    }

    func testAPrivateAddressBehindARouterConnectsWithoutAnyQuestion() async {
        let (model, _) = await makeModel(gateway())
        model.connect(vpn)
        await assertEventually { model.uiState.connected != nil }
        XCTAssertNil(model.uiState.overlay)
    }

    func testAPrivateAddressThatCannotBeReachedMentionsTheLocalNetwork() async {
        let (model, _) = await makeModel(gateway(), configure: failNextConnect)
        model.connect(vpn)
        await assertEventually { model.uiState.connectFailure?.localNetwork == .suspected }
    }

    func testAnotherErrorOfALocalServerDoesNotMentionTheLocalNetwork() async {
        let (model, fakes) = await makeModel(gateway(explained: true, refused: true)) {
            $0.requiredPassword = "open sesame"
        }
        model.connect(home)
        await assertEventually { model.uiState.connectFailure != nil }
        XCTAssertEqual(
            model.uiState.connectFailure,
            ConnectFailure(error: .wrongPassword, serverName: "Home", localNetwork: nil)
        )
        XCTAssertEqual(fakes.localNetwork.probes, 0)
    }

    func testALinkToALocalServerIsExplainedAndThenJoinsItsChannel() async {
        let (model, _) = await makeModel(gateway())
        model.openLink("mumble://Me@home.example/Gaming")
        await assertEventually {
            if case .link = model.uiState.overlay { return true }
            return false
        }
        model.confirmLink()
        await assertEventually {
            if case .localNetwork = model.uiState.overlay { return true }
            return false
        }
        model.continueLocalNetwork()
        await assertEventually { model.uiState.connected != nil }
        XCTAssertEqual(engine.connectCalls, [ConnectCall(serverId: home, password: nil, channelPath: ["Gaming"])])
    }

    func testCancellingWhileTheAddressIsLookedAtDoesNotConnect() async {
        let (model, _) = await makeModel(gateway(explained: true))
        model.connect(home)
        XCTAssertTrue(model.uiState.serverRows.contains { $0.id == self.home && $0.connecting })
        model.disconnect()
        XCTAssertFalse(model.uiState.serverRows.contains(where: \.connecting))
        try? await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertEqual(engine.connectCalls, [])
        XCTAssertNil(model.uiState.connected)
    }
}
