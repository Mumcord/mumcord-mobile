// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge

/// One stream: a person's screen share or camera.
struct StreamKey: Hashable {
    let session: UInt32
    let kind: StreamKind
}

extension Stream {
    var key: StreamKey { StreamKey(session: session, kind: kind) }
}

/// How an opened stream is shown: `full` is the whole screen on a phone layout and the right
/// column in two columns, `mini` the floating mini window above the other screens, and `whole`
/// the whole screen that the right column's full screen button opens.
enum StreamView: Equatable {
    case full
    case mini
    case whole
}

struct OpenStream: Equatable {
    let key: StreamKey
    var view: StreamView
}

/// What the user did with the streams, besides what the engine knows.
struct StreamsLocal: Equatable {
    /// The stream the user opened by tapping its tile, if any.
    var open: OpenStream?
    /// Streams the user closed: they do not start by themselves again until they end.
    var dismissed: Set<StreamKey> = []
    /// The tiles that are on screen right now.
    var visibleTiles: Set<StreamKey> = []
    /// The app is on screen.
    var onScreen = true
    /// The streams the app asked the engine to watch and did not stop yet.
    var requested: Set<StreamKey> = []
    /// The full screen was turned to landscape with its button.
    var landscape = false
    /// Volumes moved on the slider and not yet reported back by the engine.
    var volumeDrafts: [StreamKey: Int] = [:]
}

/// The streams that should be watched now. Nothing is fetched while the app is off screen: no
/// picture is seen there, and decoding costs battery (FR-5.43). The stream the user opened is
/// watched. A tile on screen starts by itself only where streams start automatically
/// (`autoplay`, FR-5.41) and the user did not close it.
func wantedWatches(streams: [Stream], local: StreamsLocal, autoplay: Bool) -> Set<StreamKey> {
    guard local.onScreen else { return [] }
    let present = Set(streams.map(\.key))
    var wanted: Set<StreamKey> = []
    if let open = local.open?.key, present.contains(open) { wanted.insert(open) }
    if autoplay {
        wanted.formUnion(local.visibleTiles.filter { present.contains($0) && !local.dismissed.contains($0) })
    }
    return wanted
}

/// What has to be told to the engine: the watches to start and those to stop.
struct WatchChanges: Equatable {
    let start: Set<StreamKey>
    let stop: Set<StreamKey>

    var isEmpty: Bool { start.isEmpty && stop.isEmpty }
}

func watchChanges(wanted: Set<StreamKey>, requested: Set<StreamKey>) -> WatchChanges {
    WatchChanges(start: wanted.subtracting(requested), stop: requested.subtracting(wanted))
}

extension StreamsLocal {
    /// Forgets what belonged to streams that ended.
    func pruned(_ streams: [Stream]) -> StreamsLocal {
        let present = Set(streams.map(\.key))
        var next = self
        next.open = open.flatMap { present.contains($0.key) ? $0 : nil }
        next.dismissed = dismissed.intersection(present)
        next.visibleTiles = visibleTiles.intersection(present)
        next.requested = requested.intersection(present)
        next.landscape = landscape && next.open?.view == .full
        next.volumeDrafts = volumeDrafts.filter { present.contains($0.key) }
        return next
    }
}

/// What a tile shows besides the stream's name.
enum TileLook: Equatable {
    /// Not fetched: on mobile data without "Start streams automatically", or closed by the user.
    case tapToWatch
    /// About to start or connecting.
    case connecting
    /// Playing in the tile.
    case playing(videoId: UInt64)
    /// Playing, but in the full screen or the mini window.
    case openElsewhere
    /// The sharer paused.
    case paused
    case unreachable(StreamUnreachable)
}

/// A stream as its tile, the full screen and the mini window show it. `name` is the sharer's
/// name and therefore untrusted text.
struct StreamUi: Equatable {
    let key: StreamKey
    let name: String
    let videoId: UInt64
    let watch: StreamWatch
    let paused: Bool
    let hasSound: Bool
    let volume: Int
    let viewers: Int?
    let tile: TileLook
    /// The channel the sharer is in, for the stream's header in two columns; untrusted text.
    var channel: String?

    /// The picture is there to be drawn.
    var playing: Bool {
        if case .watching = watch { return !paused }
        return false
    }
}

struct StreamsUiState: Equatable {
    var streams: [StreamKey: StreamUi] = [:]
    var open: OpenStream?
    var landscape = false
    var directConnectionsQuestion = false

    /// The opened stream, while it still exists.
    var opened: StreamUi? {
        open.flatMap { streams[$0.key] }
    }
}

func streamsUiState(
    streams: [Stream],
    names: (UInt32) -> String?,
    local: StreamsLocal,
    autoplay: Bool,
    directConnectionsQuestion: Bool,
    channels: (UInt32) -> String? = { _ in nil }
) -> StreamsUiState {
    var shown: [StreamKey: StreamUi] = [:]
    for stream in streams {
        // A stream of someone the tree does not know (yet) cannot be named, so it waits.
        guard let name = names(stream.session) else { continue }
        let key = stream.key
        shown[key] = StreamUi(
            key: key,
            name: name,
            videoId: stream.videoId,
            watch: stream.watch,
            paused: stream.paused,
            hasSound: stream.hasSound,
            volume: local.volumeDrafts[key] ?? Int(stream.volume),
            viewers: stream.viewers.map(Int.init),
            tile: tileLook(stream, local: local, autoplay: autoplay),
            channel: channels(stream.session)
        )
    }
    return StreamsUiState(
        streams: shown,
        open: local.open,
        landscape: local.landscape,
        directConnectionsQuestion: directConnectionsQuestion
    )
}

func tileLook(_ stream: Stream, local: StreamsLocal, autoplay: Bool) -> TileLook {
    let key = stream.key
    switch stream.watch {
    case .unreachable(let reason):
        return .unreachable(reason)
    case .watching:
        if stream.paused { return .paused }
        if local.open?.key == key { return .openElsewhere }
        return .playing(videoId: stream.videoId)
    case .connecting:
        return .connecting
    case .idle:
        if local.requested.contains(key) { return .connecting }
        if autoplay && !local.dismissed.contains(key) { return .connecting }
        return .tapToWatch
    }
}

/// The highest stream volume, as for voice.
let maxStreamVolume = 200
