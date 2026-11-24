// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// Watching streams through the model, against the fake engine.
@MainActor
final class StreamsControllerTests: XCTestCase {
    private let box = EngineBox()
    private let key = StreamKey(session: Mockup.mira, kind: .screen)
    /// Mobile data unless a test switches to Wi-Fi: streams wait for a tap.
    private let network = FakeNetworkMonitor(fakeMobileData)

    private var engine: FakeEngine { box.engine }

    /// Connected to the mockup server, where Mira shares her screen.
    private func connected(_ streams: [MumcordBridge.Stream] = [mockupStream()]) async -> AppModel {
        let store = makeStore(box, server: mockupServer(), servers: [savedServer(1, "Nordlicht")])
        let model = AppModel(store: store, setupStore: InMemorySetupStore(firstStartDone: true), network: network)
        await assertEventually { model.uiState.session.serversLoaded }
        model.connect(1)
        await assertEventually { model.uiState.connected != nil }
        engine.showStreams(streams)
        await assertEventually { model.streams.uiState.streams.count == streams.count }
        return model
    }

    private func stream(_ model: AppModel) -> StreamUi? {
        model.streams.uiState.streams[key]
    }

    func testOnMobileDataTheTileWaitsForTheTapAndThenTheStreamPlaysFullScreen() async {
        let model = await connected()
        model.streams.tileShown(key, true)
        try? await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertTrue(engine.watchCalls.isEmpty, "AC-5.23: nothing is fetched before the tap")
        XCTAssertEqual(stream(model)?.tile, .tapToWatch)
        let tiles = model.uiState.connected?.rows.filter { if case .stream = $0 { true } else { false } }
        XCTAssertEqual(tiles?.count, 1, "the tile sits under Mira")

        model.streams.open(key)
        await assertEventually { self.engine.watchCalls == [self.key] }
        XCTAssertNotNil(engine.videoSink, "the sink is set before the first watch")
        XCTAssertEqual(model.streams.uiState.open?.view, .full)
        await assertEventually { model.streams.uiState.opened?.watch == .watching(path: .direct) }
        XCTAssertEqual(stream(model)?.tile, .openElsewhere)
    }

    func testACameraIsNotFetchedBeforeItsTileIsTapped() async {
        let camera = mockupStream(viewers: nil, hasSound: false, kind: .camera)
        let model = await connected([camera])
        model.streams.tileShown(camera.key, true)
        try? await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertTrue(engine.watchCalls.isEmpty, "the camera room stays closed")
        model.streams.open(camera.key)
        await assertEventually { self.engine.watchCalls == [camera.key] }
    }

    func testWithStreamsStartingAutomaticallyAVisibleTileStartsAndStopsWhenScrolledAway() async {
        network.status = fakeWifi
        let model = await connected()
        model.streams.tileShown(key, true)
        await assertEventually { self.engine.watchCalls.count == 1 }
        await assertEventually { self.stream(model)?.tile == .playing(videoId: mockupStream().videoId) }

        model.streams.tileShown(key, false)
        await assertEventually("FR-5.43: nothing is decoded that nobody sees") { self.engine.stopCalls.count == 1 }

        // A switch to mobile data stops what started by itself.
        model.streams.tileShown(key, true)
        await assertEventually { self.engine.watchCalls.count == 2 }
        network.status = fakeMobileData
        await assertEventually { self.engine.stopCalls.count == 2 }
    }

    func testStartStreamsAutomaticallyLetsATileStartOnMobileData() async {
        let model = await connected()
        model.more.changeMobile { $0.mobileDataAutoplayStreams = true }
        await assertEventually { model.uiState.session.mobileSettings?.mobileDataAutoplayStreams == true }
        model.streams.tileShown(key, true)
        await assertEventually { self.engine.watchCalls == [self.key] }
    }

    func testCollapsingShrinksToTheMiniWindowAndCloseStopsForGood() async {
        network.status = fakeWifi
        let model = await connected()
        model.streams.tileShown(key, true)
        model.streams.open(key)
        model.streams.toggleLandscape()
        XCTAssertTrue(model.streams.uiState.landscape)
        model.streams.collapse()
        XCTAssertEqual(model.streams.uiState.open?.view, .mini)
        XCTAssertFalse(model.streams.uiState.landscape, "the mini window is never landscape")
        model.streams.expand()
        XCTAssertEqual(model.streams.uiState.open?.view, .full)

        model.streams.close()
        XCTAssertNil(model.streams.uiState.open)
        await assertEventually("the visible tile does not restart a closed stream") { self.engine.stopCalls.count == 1 }
        XCTAssertEqual(stream(model)?.tile, .tapToWatch)
    }

    func testLeavingTheAppStopsTheStreamAndComingBackResumesTheOpenedOne() async {
        let model = await connected()
        model.streams.open(key)
        model.streams.collapse()
        await assertEventually { self.engine.watchCalls.count == 1 }
        model.streams.setOnScreen(false)
        await assertEventually { self.engine.stopCalls.count == 1 }
        model.streams.setOnScreen(true)
        await assertEventually { self.engine.watchCalls.count == 2 }
        XCTAssertEqual(model.streams.uiState.open?.view, .mini)
    }

    func testTheDirectConnectionQuestionIsAskedAndAnswered() async {
        let model = await connected()
        engine.asksDirectConnections = true
        model.streams.open(key)
        await assertEventually { model.streams.uiState.directConnectionsQuestion }
        XCTAssertEqual(stream(model)?.watch, .connecting)

        model.streams.answerDirectConnections(false)
        await assertEventually { self.engine.directAnswers == [false] }
        await assertEventually { !model.streams.uiState.directConnectionsQuestion }
        await assertEventually { self.stream(model)?.tile == .unreachable(.directConnectionsDeclined) }
    }

    func testAnUnreachableStreamIsTriedAgainOnRequest() async {
        let model = await connected()
        engine.watchOutcome = .unreachable(reason: .noPath)
        model.streams.open(key)
        await assertEventually { self.stream(model)?.tile == .unreachable(.noPath) }
        engine.watchOutcome = .watching(path: .relay)
        model.streams.retry(key)
        await assertEventually { self.engine.watchCalls.count == 2 }
        await assertEventually { self.stream(model)?.watch == .watching(path: .relay) }
    }

    func testTheVolumeGoesToTheEngineAndAnEndedStreamClosesItsWindow() async {
        let model = await connected()
        model.streams.open(key)
        model.streams.changeVolume(key, 250)
        XCTAssertEqual(stream(model)?.volume, 200)
        await assertEventually { self.engine.currentStreams.first?.volume == 200 }

        engine.showStreams([])
        await assertEventually { model.streams.uiState.open == nil }
        XCTAssertTrue(model.streams.uiState.streams.isEmpty)
    }

    func testTheEndOfTheConnectionForgetsTheStreams() async {
        let model = await connected()
        model.streams.open(key)
        model.disconnect()
        await assertEventually { model.streams.uiState.streams.isEmpty && model.streams.uiState.open == nil }
    }
}
