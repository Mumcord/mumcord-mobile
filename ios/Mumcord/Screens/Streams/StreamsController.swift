// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import Observation

/// Watching streams: which ones are fetched, the opened one (full screen or mini window) and the
/// direct connection question. An object of its own, apart from `AppModel`, like the More tab:
/// only the stream views read it, so a picture-related change does not redraw other screens.
///
/// Which streams are watched follows from the state (`wantedWatches`), and the difference is
/// told to the engine. So a tile scrolled away, a closed stream, the app leaving the screen or a
/// switch to mobile data stop what is no longer needed by themselves.
@MainActor
@Observable
final class StreamsController {
    private(set) var local = StreamsLocal()
    private var streams: [Stream] = []
    private var names: [UInt32: String] = [:]
    /// The name of the channel each person is in, for the header of a stream in two columns.
    private var channelNames: [UInt32: String] = [:]
    private var directConnectionsQuestion = false
    private var autoplay: Bool

    /// Where the pictures of watched streams come from and where the engine's news of them goes.
    @ObservationIgnored let video: StreamVideo
    @ObservationIgnored private let store: SessionStore
    @ObservationIgnored private let network: NetworkMonitor?
    /// The last commands sent to the engine; the next ones wait for them, so a stop never
    /// overtakes the watch it ends.
    @ObservationIgnored private var commands: Task<Void, Never>?
    /// The streams whose last picture is kept for the next view of them: the watched ones.
    @ObservationIgnored private var keptPictures: Set<UInt64> = []

    /// - Parameter network: decides with the mobile data settings whether streams start by
    ///   themselves (FR-5.41); nil counts as Wi-Fi.
    init(store: SessionStore, network: NetworkMonitor?, video: StreamVideo) {
        self.store = store
        self.network = network
        self.video = video
        autoplay = network.map { autoplayStreams($0.status, store.state.mobileSettings) } ?? true
        let states = store.states()
        Task { [weak self] in
            for await state in states {
                self?.follow(state)
            }
        }
        if let statuses = network?.statuses() {
            Task { [weak self] in
                for await _ in statuses {
                    self?.followAutoplay()
                }
            }
        }
    }

    /// The streams as the views show them.
    var uiState: StreamsUiState {
        streamsUiState(
            streams: streams,
            names: { names[$0] },
            local: local,
            autoplay: autoplay,
            directConnectionsQuestion: directConnectionsQuestion,
            channels: { channelNames[$0] }
        )
    }

    private func follow(_ state: SessionState) {
        let people = state.server?.people.mapValues(\.name) ?? [:]
        // Only what changed is set, so the views are not drawn again for other news.
        if people != names { names = people }
        let channels = state.server.map { server in
            server.people.compactMapValues { server.channels[$0.channelId]?.name }
        } ?? [:]
        if channels != channelNames { channelNames = channels }
        if state.directConnectionsQuestion != directConnectionsQuestion {
            directConnectionsQuestion = state.directConnectionsQuestion
        }
        if state.streams != streams {
            streams = state.streams
            update(local.pruned(streams))
        }
        followAutoplay()
    }

    private func followAutoplay() {
        let now = network.map { autoplayStreams($0.status, store.state.mobileSettings) } ?? true
        guard now != autoplay else { return }
        autoplay = now
        update(local)
    }

    /// Takes the new local state and tells the engine what to watch and to stop.
    private func update(_ next: StreamsLocal) {
        var next = next
        let changes = watchChanges(
            wanted: wantedWatches(streams: streams, local: next, autoplay: autoplay),
            requested: next.requested
        )
        next.requested = next.requested.union(changes.start).subtracting(changes.stop)
        if next != local { local = next }
        let watched = Set(streams.filter { next.requested.contains($0.key) }.map(\.videoId))
        if watched != keptPictures {
            keptPictures = watched
            video.keepPictures(of: watched)
        }
        guard !changes.isEmpty else { return }
        // The sink first, so the news of the first picture is not lost.
        if !changes.start.isEmpty { store.setVideoSink(video.sink) }
        send { store in
            for key in changes.stop { await store.stopWatchingStream(key.session, kind: key.kind) }
            for key in changes.start { await store.watchStream(key.session, kind: key.kind) }
        }
    }

    private func send(_ command: @escaping @Sendable (SessionStore) async -> Void) {
        let previous = commands
        commands = Task { [store] in
            await previous?.value
            await command(store)
        }
    }

    /// A tile was tapped: the stream opens full screen and is fetched, also on mobile data.
    func open(_ key: StreamKey) {
        var next = local
        next.open = OpenStream(key: key, view: .full)
        next.dismissed.remove(key)
        update(next)
    }

    /// The collapse buttons: the full screen shrinks to the mini window.
    func collapse() {
        var next = local
        next.open?.view = .mini
        next.landscape = false
        update(next)
    }

    /// The mini window's expand button: full screen again, which is the right column in two
    /// columns; also the way back from the whole screen to that column.
    func expand() {
        var next = local
        next.open?.view = .full
        update(next)
    }

    /// The full screen button of the stream in the right column (two columns): the whole screen.
    /// Leaving it goes back to the column with `expand`.
    func fillScreen() {
        var next = local
        next.open?.view = .whole
        update(next)
    }

    /// Stop watching the opened stream: it closes and does not start by itself again.
    func close() {
        guard let key = local.open?.key else { return }
        var next = local
        next.open = nil
        next.dismissed.insert(key)
        next.landscape = false
        update(next)
    }

    /// The full screen's rotate button: landscape, or back to following the phone.
    func toggleLandscape() {
        var next = local
        next.landscape.toggle()
        update(next)
    }

    /// A stream that could not be reached is tried again.
    func retry(_ key: StreamKey) {
        guard local.requested.contains(key) else { return }
        send { store in
            await store.stopWatchingStream(key.session, kind: key.kind)
            await store.watchStream(key.session, kind: key.kind)
        }
    }

    func tileShown(_ key: StreamKey, _ shown: Bool) {
        var next = local
        if shown {
            next.visibleTiles.insert(key)
        } else {
            next.visibleTiles.remove(key)
        }
        update(next)
    }

    /// The app came on screen or left it.
    func setOnScreen(_ onScreen: Bool) {
        guard onScreen != local.onScreen else { return }
        var next = local
        next.onScreen = onScreen
        update(next)
    }

    func changeVolume(_ key: StreamKey, _ percent: Int) {
        let clamped = min(max(percent, 0), maxStreamVolume)
        var next = local
        next.volumeDrafts[key] = clamped
        update(next)
        send { store in await store.setStreamVolume(key.session, percent: clamped) }
    }

    func answerDirectConnections(_ allow: Bool) {
        send { store in await store.answerDirectConnections(allow) }
    }
}
