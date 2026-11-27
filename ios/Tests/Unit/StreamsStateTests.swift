// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// Which streams are fetched and what their tiles show, without an engine.
final class StreamsStateTests: XCTestCase {
    private let stream = mockupStream()
    private let key = StreamKey(session: Mockup.mira, kind: .screen)
    private let camera = mockupStream(viewers: nil, hasSound: false, kind: .camera)

    func testATileOnScreenStartsByItselfOnlyWhereStreamsStartAutomatically() {
        let local = StreamsLocal(visibleTiles: [key])
        XCTAssertEqual(wantedWatches(streams: [stream], local: local, autoplay: true), [key])
        // AC-5.23: on mobile data with the default settings nothing is fetched before the tap.
        XCTAssertEqual(wantedWatches(streams: [stream], local: local, autoplay: false), [])
    }

    func testATappedStreamIsFetchedAlsoOnMobileDataUntilItIsClosed() {
        let opened = StreamsLocal(open: OpenStream(key: key, view: .full))
        XCTAssertEqual(wantedWatches(streams: [stream], local: opened, autoplay: false), [key])
        var mini = opened
        mini.open?.view = .mini
        XCTAssertEqual(wantedWatches(streams: [stream], local: mini, autoplay: false), [key])
        let closed = StreamsLocal(dismissed: [key], visibleTiles: [key])
        XCTAssertEqual(
            wantedWatches(streams: [stream], local: closed, autoplay: true),
            [],
            "a closed stream does not start by itself again"
        )
    }

    func testACameraWaitsForTheTapLikeAScreenShare() {
        let local = StreamsLocal(visibleTiles: [camera.key])
        XCTAssertEqual(wantedWatches(streams: [camera], local: local, autoplay: false), [])
        XCTAssertEqual(tileLook(camera, local: local, autoplay: false), .tapToWatch)
    }

    func testNothingIsFetchedWhileTheAppIsOffScreenAndNothingThatIsNotSeen() {
        let local = StreamsLocal(open: OpenStream(key: key, view: .mini), visibleTiles: [key], onScreen: false)
        XCTAssertEqual(wantedWatches(streams: [stream], local: local, autoplay: true), [])
        XCTAssertEqual(wantedWatches(streams: [stream], local: StreamsLocal(), autoplay: true), [], "a tile scrolled away")
        XCTAssertEqual(
            wantedWatches(streams: [], local: StreamsLocal(visibleTiles: [key]), autoplay: true),
            [],
            "a stream that ended"
        )
    }

    func testTheEngineIsToldOnlyTheDifference() {
        XCTAssertEqual(
            watchChanges(wanted: [camera.key], requested: [key]),
            WatchChanges(start: [camera.key], stop: [key])
        )
        XCTAssertTrue(watchChanges(wanted: [key], requested: [key]).isEmpty)
    }

    func testWhatBelongedToAnEndedStreamIsForgotten() {
        let local = StreamsLocal(
            open: OpenStream(key: key, view: .full),
            dismissed: [key],
            visibleTiles: [key],
            requested: [key],
            landscape: true,
            volumeDrafts: [key: 50]
        )
        XCTAssertEqual(local.pruned([]), StreamsLocal())
        var mini = local
        mini.open?.view = .mini
        XCTAssertFalse(mini.pruned([stream]).landscape, "landscape only belongs to the full screen")
    }

    func testTheTileSaysWhatHappens() {
        XCTAssertEqual(tileLook(stream, local: StreamsLocal(), autoplay: false), .tapToWatch)
        XCTAssertEqual(tileLook(stream, local: StreamsLocal(), autoplay: true), .connecting)
        XCTAssertEqual(tileLook(stream, local: StreamsLocal(dismissed: [key]), autoplay: true), .tapToWatch)
        XCTAssertEqual(tileLook(mockupStream(watch: .connecting), local: StreamsLocal(), autoplay: false), .connecting)
        let watching = mockupStream(watch: .watching(path: .relay))
        XCTAssertEqual(tileLook(watching, local: StreamsLocal(), autoplay: false), .playing(videoId: stream.videoId))
        XCTAssertEqual(
            tileLook(watching, local: StreamsLocal(open: OpenStream(key: key, view: .mini)), autoplay: false),
            .openElsewhere
        )
        XCTAssertEqual(
            tileLook(mockupStream(watch: .watching(path: .relay), paused: true), local: StreamsLocal(), autoplay: false),
            .paused
        )
        let unreachable = mockupStream(watch: .unreachable(reason: .noPath))
        XCTAssertEqual(tileLook(unreachable, local: StreamsLocal(), autoplay: true), .unreachable(.noPath))
    }

    func testEveryReasonHasItsOwnPlainWordsAndNoPathHasTheSpecifiedText() {
        let reasons: [StreamUnreachable] = [
            .peerToPeerOff, .directConnectionsDeclined, .relayUnavailable, .sharerUnsupported, .noPath,
            .noAnswer, .full, .needsUpdate, .joinFailed,
        ]
        let texts = reasons.map { $0.text("Mira") }
        XCTAssertEqual(Set(texts).count, reasons.count)
        XCTAssertEqual(StreamUnreachable.noPath.text("Mira"), "Stream not reachable from this network")
        XCTAssertEqual(StreamUnreachable.full.text("Mira"), "Too many people are watching Mira.")
    }

    func testStreamsOfPeopleTheTreeDoesNotKnowWaitAndDraftsOfTheVolumeWin() {
        let local = StreamsLocal(volumeDrafts: [key: 40])
        let state = streamsUiState(
            streams: [stream, camera],
            names: { $0 == Mockup.mira ? "Mira" : nil },
            local: local,
            autoplay: false,
            directConnectionsQuestion: true
        )
        XCTAssertEqual(state.streams[key]?.volume, 40)
        XCTAssertEqual(state.streams[camera.key]?.volume, 136)
        XCTAssertTrue(state.directConnectionsQuestion)
        XCTAssertNil(state.opened)
        let unknown = streamsUiState(
            streams: [stream],
            names: { _ in nil },
            local: local,
            autoplay: false,
            directConnectionsQuestion: false
        )
        XCTAssertTrue(unknown.streams.isEmpty)
    }

    func testTheTileIsReadAsTitleLiveViewersAndWhatItSays() throws {
        let state = streamsUiState(
            streams: [stream, camera],
            names: { _ in "Mira" },
            local: StreamsLocal(),
            autoplay: false,
            directConnectionsQuestion: false
        )
        XCTAssertEqual(try XCTUnwrap(state.streams[key]).tileDescription, "Mira's screen, live, 1 viewer, Tap to watch")
        XCTAssertEqual(try XCTUnwrap(state.streams[camera.key]).tileDescription, "Mira's camera, live, Tap to watch")
    }

    func testAPictureFitsWholeAndCentredOnBlack() {
        XCTAssertEqual(
            fitPicture(area: CGSize(width: 400, height: 400), picture: CGSize(width: 1600, height: 900)),
            CGRect(x: 0, y: 88, width: 400, height: 225)
        )
        XCTAssertEqual(
            fitPicture(area: CGSize(width: 800, height: 225), picture: CGSize(width: 1600, height: 900)),
            CGRect(x: 200, y: 0, width: 400, height: 225)
        )
        XCTAssertEqual(
            fitPicture(area: CGSize(width: 10, height: 10), picture: .zero),
            CGRect(x: 0, y: 0, width: 10, height: 10)
        )
    }
}
