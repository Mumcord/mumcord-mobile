// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.streams

import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.StreamKind
import app.mumcord.mobile.bridge.StreamPath
import app.mumcord.mobile.bridge.StreamUnreachable
import app.mumcord.mobile.bridge.StreamWatch
import app.mumcord.mobile.session.MOCKUP_MIRA
import app.mumcord.mobile.session.mockupStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which streams are fetched and what their tiles show, without an engine. */
class StreamsStateTest {
    private val stream = mockupStream()
    private val key = StreamKey(MOCKUP_MIRA, StreamKind.SCREEN)
    private val camera = mockupStream().copy(kind = StreamKind.CAMERA, videoId = 2uL shl 32 or MOCKUP_MIRA.toULong())

    @Test
    fun `a tile on screen starts by itself only where streams start automatically`() {
        val local = StreamsLocal(visibleTiles = setOf(key))
        assertEquals(setOf(key), wantedWatches(listOf(stream), local, autoplay = true))
        // AC-5.23: on mobile data with the default settings nothing is fetched before the tap.
        assertEquals(emptySet<StreamKey>(), wantedWatches(listOf(stream), local, autoplay = false))
    }

    @Test
    fun `a tapped stream is fetched also on mobile data, until it is closed`() {
        val opened = StreamsLocal(open = OpenStream(key, StreamView.Full))
        assertEquals(setOf(key), wantedWatches(listOf(stream), opened, autoplay = false))
        assertEquals(setOf(key), wantedWatches(listOf(stream), opened.copy(open = OpenStream(key, StreamView.Mini)), autoplay = false))
        val closed = StreamsLocal(visibleTiles = setOf(key), dismissed = setOf(key))
        assertEquals("a closed stream does not start by itself again", emptySet<StreamKey>(), wantedWatches(listOf(stream), closed, autoplay = true))
    }

    @Test
    fun `nothing is fetched while the app is off screen, and nothing that is not seen`() {
        val local = StreamsLocal(open = OpenStream(key, StreamView.Mini), visibleTiles = setOf(key), onScreen = false)
        assertEquals(emptySet<StreamKey>(), wantedWatches(listOf(stream), local, autoplay = true))
        assertEquals("a tile scrolled away", emptySet<StreamKey>(), wantedWatches(listOf(stream), StreamsLocal(), autoplay = true))
        assertEquals("a stream that ended", emptySet<StreamKey>(), wantedWatches(emptyList(), StreamsLocal(visibleTiles = setOf(key)), autoplay = true))
    }

    @Test
    fun `the engine is told only the difference`() {
        val cameraKey = camera.key
        assertEquals(WatchChanges(start = setOf(cameraKey), stop = setOf(key)), watchChanges(setOf(cameraKey), setOf(key)))
        assertEquals(WatchChanges(emptySet(), emptySet()), watchChanges(setOf(key), setOf(key)))
    }

    @Test
    fun `what belonged to an ended stream is forgotten`() {
        val local = StreamsLocal(
            open = OpenStream(key, StreamView.Full),
            dismissed = setOf(key),
            visibleTiles = setOf(key),
            requested = setOf(key),
            landscape = true,
            volumeDrafts = mapOf(key to 50),
        )
        assertEquals(StreamsLocal(), local.pruned(emptyList()))
        assertEquals("landscape only belongs to the full screen", false, local.copy(open = OpenStream(key, StreamView.Mini)).pruned(listOf(stream)).landscape)
    }

    @Test
    fun `the tile says what happens`() {
        assertEquals(TileLook.TapToWatch, tileLook(stream, StreamsLocal(), autoplay = false))
        assertEquals(TileLook.Connecting, tileLook(stream, StreamsLocal(), autoplay = true))
        assertEquals(TileLook.TapToWatch, tileLook(stream, StreamsLocal(dismissed = setOf(key)), autoplay = true))
        assertEquals(TileLook.Connecting, tileLook(stream.copy(watch = StreamWatch.Connecting), StreamsLocal(), autoplay = false))
        val watching = stream.copy(watch = StreamWatch.Watching(StreamPath.RELAY))
        assertEquals(TileLook.Playing(stream.videoId.toLong()), tileLook(watching, StreamsLocal(), autoplay = false))
        assertEquals(TileLook.OpenElsewhere, tileLook(watching, StreamsLocal(open = OpenStream(key, StreamView.Mini)), autoplay = false))
        assertEquals(TileLook.Paused, tileLook(watching.copy(paused = true), StreamsLocal(), autoplay = false))
        val unreachable = stream.copy(watch = StreamWatch.Unreachable(StreamUnreachable.NO_PATH))
        assertEquals(TileLook.Unreachable(StreamUnreachable.NO_PATH), tileLook(unreachable, StreamsLocal(), autoplay = true))
    }

    @Test
    fun `every reason has its own plain words and no path has the specified text`() {
        val texts = StreamUnreachable.entries.map { it.text() }
        assertEquals(StreamUnreachable.entries.size, texts.toSet().size)
        assertEquals(R.string.stream_unreachable_no_path, StreamUnreachable.NO_PATH.text())
    }

    @Test
    fun `streams of people the tree does not know wait, drafts of the volume win`() {
        val local = StreamsLocal(volumeDrafts = mapOf(key to 40))
        val state = streamsUiState(listOf(stream, camera), { if (it == MOCKUP_MIRA) "Mira" else null }, local, autoplay = false, directConnectionsQuestion = true)
        assertEquals(40, state.streams.getValue(key).volume)
        assertEquals(136, state.streams.getValue(camera.key).volume)
        assertTrue(state.directConnectionsQuestion)
        assertNull(state.opened)
        val unknown = streamsUiState(listOf(stream), { null }, local, autoplay = false, directConnectionsQuestion = false)
        assertTrue(unknown.streams.isEmpty())
    }
}
