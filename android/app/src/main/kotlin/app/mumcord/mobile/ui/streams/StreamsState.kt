// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.streams

import androidx.annotation.StringRes
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.Stream
import app.mumcord.mobile.bridge.StreamKind
import app.mumcord.mobile.bridge.StreamUnreachable
import app.mumcord.mobile.bridge.StreamWatch

/** One stream: a person's screen share or camera. */
data class StreamKey(val session: UInt, val kind: StreamKind)

val Stream.key: StreamKey
    get() = StreamKey(session, kind)

/**
 * How an opened stream is shown: [Full] is the whole screen on a phone and the right column in
 * two columns, [Mini] the floating mini window above the other screens, and [Whole] the whole
 * screen that the right column's full screen button opens.
 */
enum class StreamView { Full, Mini, Whole }

data class OpenStream(val key: StreamKey, val view: StreamView)

/**
 * What the user did with the streams, besides what the engine knows.
 *
 * @param open the stream the user opened by tapping its tile, if any.
 * @param dismissed streams the user closed: they do not start by themselves again until they end.
 * @param visibleTiles the tiles that are on screen right now.
 * @param onScreen the app is on screen (also in the system's picture-in-picture window).
 * @param requested the streams the app asked the engine to watch and did not stop yet.
 * @param landscape the full screen was turned to landscape with its button.
 * @param volumeDrafts volumes moved on the slider and not yet reported back by the engine.
 */
data class StreamsLocal(
    val open: OpenStream? = null,
    val dismissed: Set<StreamKey> = emptySet(),
    val visibleTiles: Set<StreamKey> = emptySet(),
    val onScreen: Boolean = true,
    val requested: Set<StreamKey> = emptySet(),
    val landscape: Boolean = false,
    val volumeDrafts: Map<StreamKey, Int> = emptyMap(),
)

/**
 * The streams that should be watched now. Nothing is fetched while the app is off screen (and
 * not in picture-in-picture): no picture is seen there, and decoding costs battery (FR-5.43).
 * The stream the user opened is watched. A tile on screen starts by itself only where streams
 * start automatically ([autoplay], FR-5.41) and the user did not close it.
 */
fun wantedWatches(streams: List<Stream>, local: StreamsLocal, autoplay: Boolean): Set<StreamKey> {
    if (!local.onScreen) return emptySet()
    val present = streams.mapTo(HashSet()) { it.key }
    return buildSet {
        local.open?.key?.takeIf { it in present }?.let(::add)
        if (autoplay) local.visibleTiles.filterTo(this) { it in present && it !in local.dismissed }
    }
}

/** What has to be told to the engine: the watches to start and those to stop. */
data class WatchChanges(val start: Set<StreamKey>, val stop: Set<StreamKey>)

fun watchChanges(wanted: Set<StreamKey>, requested: Set<StreamKey>): WatchChanges =
    WatchChanges(start = wanted - requested, stop = requested - wanted)

/** Forgets what belonged to streams that ended. */
fun StreamsLocal.pruned(streams: List<Stream>): StreamsLocal {
    val present = streams.mapTo(HashSet()) { it.key }
    val open = open?.takeIf { it.key in present }
    return copy(
        open = open,
        dismissed = dismissed intersect present,
        visibleTiles = visibleTiles intersect present,
        requested = requested intersect present,
        landscape = landscape && open?.view == StreamView.Full,
        volumeDrafts = volumeDrafts.filterKeys { it in present },
    )
}

/** What a tile shows besides the stream's name. */
sealed interface TileLook {
    /** Not fetched: on mobile data without "Start streams automatically", or closed by the user. */
    data object TapToWatch : TileLook

    /** About to start or connecting. */
    data object Connecting : TileLook

    /** Playing in the tile. */
    data class Playing(val videoId: Long) : TileLook

    /** Playing, but in the full screen or the mini window. */
    data object OpenElsewhere : TileLook

    /** The sharer paused. */
    data object Paused : TileLook

    data class Unreachable(val reason: StreamUnreachable) : TileLook
}

/** A stream as its tile, the full screen and the mini window show it. */
data class StreamUi(
    val key: StreamKey,
    val name: String,
    val videoId: Long,
    val watch: StreamWatch,
    val paused: Boolean,
    val hasSound: Boolean,
    val volume: Int,
    val viewers: Int?,
    val tile: TileLook,
    /** The channel the sharer is in, for the stream's header in two columns; untrusted text. */
    val channel: String? = null,
)

data class StreamsUiState(
    val streams: Map<StreamKey, StreamUi> = emptyMap(),
    val open: OpenStream? = null,
    val landscape: Boolean = false,
    val directConnectionsQuestion: Boolean = false,
) {
    /** The opened stream, while it still exists. */
    val opened: StreamUi?
        get() = open?.let { streams[it.key] }
}

fun streamsUiState(
    streams: List<Stream>,
    names: (UInt) -> String?,
    local: StreamsLocal,
    autoplay: Boolean,
    directConnectionsQuestion: Boolean,
    channels: (UInt) -> String? = { null },
): StreamsUiState {
    val shown = streams.mapNotNull { stream ->
        // A stream of someone the tree does not know (yet) cannot be named, so it waits.
        val name = names(stream.session) ?: return@mapNotNull null
        val key = stream.key
        StreamUi(
            key = key,
            name = name,
            videoId = stream.videoId.toLong(),
            watch = stream.watch,
            paused = stream.paused,
            hasSound = stream.hasSound,
            volume = local.volumeDrafts[key] ?: stream.volume.toInt(),
            viewers = stream.viewers?.toInt(),
            tile = tileLook(stream, local, autoplay),
            channel = channels(stream.session),
        )
    }
    return StreamsUiState(
        streams = shown.associateBy { it.key },
        open = local.open,
        landscape = local.landscape,
        directConnectionsQuestion = directConnectionsQuestion,
    )
}

fun tileLook(stream: Stream, local: StreamsLocal, autoplay: Boolean): TileLook {
    val key = stream.key
    return when (val watch = stream.watch) {
        is StreamWatch.Unreachable -> TileLook.Unreachable(watch.reason)
        is StreamWatch.Watching -> when {
            stream.paused -> TileLook.Paused
            local.open?.key == key -> TileLook.OpenElsewhere
            else -> TileLook.Playing(stream.videoId.toLong())
        }
        StreamWatch.Connecting -> TileLook.Connecting
        StreamWatch.Idle -> when {
            key in local.requested -> TileLook.Connecting
            autoplay && key !in local.dismissed -> TileLook.Connecting
            else -> TileLook.TapToWatch
        }
    }
}

/**
 * The plain words for why a stream cannot be watched, with the sharer's name as the only
 * argument where the text has one. The desktop app's wording where it has the case.
 */
@StringRes
fun StreamUnreachable.text(): Int = when (this) {
    StreamUnreachable.NO_PATH -> R.string.stream_unreachable_no_path
    StreamUnreachable.PEER_TO_PEER_OFF -> R.string.stream_unreachable_peer_to_peer_off
    StreamUnreachable.DIRECT_CONNECTIONS_DECLINED -> R.string.stream_unreachable_direct_declined
    StreamUnreachable.RELAY_UNAVAILABLE -> R.string.stream_unreachable_relay_unavailable
    StreamUnreachable.SHARER_UNSUPPORTED -> R.string.stream_unreachable_sharer_unsupported
    StreamUnreachable.NO_ANSWER -> R.string.stream_unreachable_no_answer
    StreamUnreachable.FULL -> R.string.stream_unreachable_full
    StreamUnreachable.NEEDS_UPDATE -> R.string.stream_unreachable_needs_update
    StreamUnreachable.JOIN_FAILED -> R.string.stream_unreachable_join_failed
}

/** The highest stream volume, as for voice. */
const val MAX_STREAM_VOLUME = 200
