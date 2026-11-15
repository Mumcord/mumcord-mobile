// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.streams

import app.mumcord.mobile.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Watching streams: which ones are fetched, the opened one (full screen or mini window) and the
 * direct connection question. Lives in the view model's scope, so an opened stream survives a
 * rotation.
 *
 * Which streams are watched is derived from the state ([wantedWatches]) and the difference is
 * told to the engine, so a tile scrolled away, a closed stream, the app leaving the screen or a
 * switch to mobile data stop what is no longer needed by themselves.
 *
 * @param autoplay whether streams start by themselves on the current network (FR-5.41).
 */
class StreamsController(
    private val repository: SessionRepository,
    private val scope: CoroutineScope,
    autoplay: Flow<Boolean>,
    private val video: StreamVideo,
) {
    private val local = MutableStateFlow(StreamsLocal())
    private val autoplayNow = autoplay.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

    val state: StateFlow<StreamsUiState> = combine(repository.state, local, autoplayNow) { session, local, autoplay ->
        streamsUiState(
            streams = session.streams,
            names = { session.server?.people?.get(it)?.name },
            local = local,
            autoplay = autoplay,
            directConnectionsQuestion = session.directConnectionsQuestion,
        )
    }.stateIn(scope, SharingStarted.Eagerly, StreamsUiState())

    init {
        scope.launch {
            combine(repository.state.map { it.streams }.distinctUntilChanged(), local, autoplayNow) { streams, local, autoplay ->
                Triple(streams, local, autoplay)
            }.collect { (streams, current, autoplay) ->
                val pruned = current.pruned(streams)
                if (pruned != current) {
                    local.value = pruned
                    return@collect
                }
                val changes = watchChanges(wantedWatches(streams, current, autoplay), current.requested)
                if (changes.start.isEmpty() && changes.stop.isEmpty()) return@collect
                local.update { it.copy(requested = it.requested + changes.start - changes.stop) }
                if (changes.start.isNotEmpty()) repository.setVideoSink(video.sink)
                changes.stop.forEach { repository.stopWatchingStream(it.session, it.kind) }
                changes.start.forEach { repository.watchStream(it.session, it.kind) }
            }
        }
    }

    /** A tile was tapped: the stream opens full screen and is fetched, also on mobile data. */
    fun open(key: StreamKey) = local.update {
        it.copy(open = OpenStream(key, StreamView.Full), dismissed = it.dismissed - key)
    }

    /** Back or the collapse button: the full screen shrinks to the mini window. */
    fun collapse() = local.update { current ->
        current.copy(open = current.open?.copy(view = StreamView.Mini), landscape = false)
    }

    /** The mini window's expand button: full screen again. */
    fun expand() = local.update { current -> current.copy(open = current.open?.copy(view = StreamView.Full)) }

    /** Stop watching the opened stream: it closes and does not start by itself again. */
    fun close() = local.update { current ->
        val key = current.open?.key ?: return@update current
        current.copy(open = null, dismissed = current.dismissed + key, landscape = false)
    }

    /** The full screen's rotate button: landscape, or back to following the phone. */
    fun toggleLandscape() = local.update { it.copy(landscape = !it.landscape) }

    /** A stream that could not be reached is tried again. */
    fun retry(key: StreamKey) {
        if (key !in local.value.requested) return
        scope.launch {
            repository.stopWatchingStream(key.session, key.kind)
            repository.watchStream(key.session, key.kind)
        }
    }

    fun tileShown(key: StreamKey, shown: Boolean) = local.update {
        it.copy(visibleTiles = if (shown) it.visibleTiles + key else it.visibleTiles - key)
    }

    /** The app came on screen or left it; the picture-in-picture window counts as on screen. */
    fun setOnScreen(onScreen: Boolean) = local.update { it.copy(onScreen = onScreen) }

    fun changeVolume(key: StreamKey, percent: Int) {
        val clamped = percent.coerceIn(0, MAX_STREAM_VOLUME)
        local.update { it.copy(volumeDrafts = it.volumeDrafts + (key to clamped)) }
        scope.launch { repository.setStreamVolume(key.session, clamped) }
    }

    fun answerDirectConnections(allow: Boolean) {
        scope.launch { repository.answerDirectConnections(allow) }
    }
}
