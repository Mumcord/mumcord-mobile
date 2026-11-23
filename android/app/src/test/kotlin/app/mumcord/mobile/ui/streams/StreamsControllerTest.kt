// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.streams

import app.mumcord.mobile.bridge.StreamKind
import app.mumcord.mobile.bridge.StreamPath
import app.mumcord.mobile.bridge.StreamUnreachable
import app.mumcord.mobile.bridge.StreamWatch
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.MOCKUP_MIRA
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.mockupStream
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.AppViewModel
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Watching streams through the view model, against the fake engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamsControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private val autoplay = MutableStateFlow(false)
    private val key = StreamKey(MOCKUP_MIRA, StreamKind.SCREEN)

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Connected to the mockup server, where Mira shares her screen. */
    private fun TestScope.connected(): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupServer(), servers = listOf(savedServer(1, "Nordlicht"))).also { engine = it }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        return AppViewModel(
            repository,
            DoneSetup,
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            streamsAutoplay = autoplay,
        ).also { viewModel ->
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()
            viewModel.connect(1)
            runCurrent()
            engine.showStreams(listOf(mockupStream()))
            runCurrent()
        }
    }

    private val AppViewModel.stream get() = streams.state.value.streams.getValue(key)

    @Test
    fun onMobileDataTheTileWaitsForTheTapAndThenTheStreamPlaysFullScreen() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.streams.tileShown(key, true)
        runCurrent()
        assertTrue("AC-5.23: nothing is fetched before the tap", engine.watchCalls.isEmpty())
        assertEquals(TileLook.TapToWatch, viewModel.stream.tile)
        assertEquals("the tile sits under Mira", 1, viewModel.uiState.value.connected!!.rows.count { it is app.mumcord.mobile.ui.connected.ChannelListRow.StreamItem })

        viewModel.streams.open(key)
        runCurrent()
        assertEquals(listOf(MOCKUP_MIRA to StreamKind.SCREEN), engine.watchCalls.toList())
        assertNotNull("the sink is set before the first watch", engine.lastVideoSink)
        assertEquals(StreamView.Full, viewModel.streams.state.value.open?.view)
        assertEquals(StreamWatch.Watching(StreamPath.DIRECT), viewModel.streams.state.value.opened?.watch)
        assertEquals(TileLook.OpenElsewhere, viewModel.stream.tile)
    }

    @Test
    fun withStreamsStartingAutomaticallyAVisibleTileStartsAndStopsWhenScrolledAway() = runTest(dispatcher) {
        autoplay.value = true
        val viewModel = connected()
        viewModel.streams.tileShown(key, true)
        runCurrent()
        assertEquals(1, engine.watchCalls.size)
        assertEquals(TileLook.Playing(mockupStream().videoId.toLong()), viewModel.stream.tile)

        viewModel.streams.tileShown(key, false)
        runCurrent()
        assertEquals("FR-5.43: nothing is decoded that nobody sees", 1, engine.stopCalls.size)

        // A switch to mobile data stops what started by itself.
        viewModel.streams.tileShown(key, true)
        runCurrent()
        autoplay.value = false
        runCurrent()
        assertEquals(2, engine.stopCalls.size)
    }

    @Test
    fun inTwoColumnsTheFullScreenButtonOpensTheWholeScreenAndLeavingItReturnsToTheColumn() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.streams.open(key)
        runCurrent()
        assertEquals("the header names the sharer's channel", "Gaming", viewModel.stream.channel)
        viewModel.streams.fillScreen()
        runCurrent()
        assertEquals(StreamView.Whole, viewModel.streams.state.value.open?.view)
        assertEquals("still watched", 1, engine.watchCalls.size)
        viewModel.streams.expand()
        runCurrent()
        assertEquals(StreamView.Full, viewModel.streams.state.value.open?.view)
        assertTrue("nothing was stopped on the way", engine.stopCalls.isEmpty())
    }

    @Test
    fun backShrinksToTheMiniWindowAndCloseStopsForGood() = runTest(dispatcher) {
        autoplay.value = true
        val viewModel = connected()
        viewModel.streams.tileShown(key, true)
        viewModel.streams.open(key)
        viewModel.streams.toggleLandscape()
        runCurrent()
        assertTrue(viewModel.streams.state.value.landscape)
        viewModel.streams.collapse()
        runCurrent()
        assertEquals(StreamView.Mini, viewModel.streams.state.value.open?.view)
        assertFalse("the mini window is never landscape", viewModel.streams.state.value.landscape)
        viewModel.streams.expand()
        runCurrent()
        assertEquals(StreamView.Full, viewModel.streams.state.value.open?.view)

        viewModel.streams.close()
        runCurrent()
        assertNull(viewModel.streams.state.value.open)
        assertEquals("the visible tile does not restart a closed stream", 1, engine.stopCalls.size)
        assertEquals(TileLook.TapToWatch, viewModel.stream.tile)
    }

    @Test
    fun leavingTheAppStopsTheStreamAndComingBackResumesTheOpenedOne() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.streams.open(key)
        viewModel.streams.collapse()
        runCurrent()
        viewModel.streams.setOnScreen(false)
        runCurrent()
        assertEquals(1, engine.stopCalls.size)
        viewModel.streams.setOnScreen(true)
        runCurrent()
        assertEquals(2, engine.watchCalls.size)
        assertEquals(StreamView.Mini, viewModel.streams.state.value.open?.view)
    }

    @Test
    fun theDirectConnectionQuestionIsAskedAndAnswered() = runTest(dispatcher) {
        val viewModel = connected()
        engine.asksDirectConnections = true
        viewModel.streams.open(key)
        runCurrent()
        assertTrue(viewModel.streams.state.value.directConnectionsQuestion)
        assertEquals(StreamWatch.Connecting, viewModel.stream.watch)

        viewModel.streams.answerDirectConnections(false)
        runCurrent()
        assertEquals(listOf(false), engine.directAnswers.toList())
        assertFalse(viewModel.streams.state.value.directConnectionsQuestion)
        assertEquals(TileLook.Unreachable(StreamUnreachable.DIRECT_CONNECTIONS_DECLINED), viewModel.stream.tile)
    }

    @Test
    fun anUnreachableStreamIsTriedAgainOnRequest() = runTest(dispatcher) {
        val viewModel = connected()
        engine.watchOutcome = StreamWatch.Unreachable(StreamUnreachable.NO_PATH)
        viewModel.streams.open(key)
        runCurrent()
        assertEquals(TileLook.Unreachable(StreamUnreachable.NO_PATH), viewModel.stream.tile)
        engine.watchOutcome = StreamWatch.Watching(StreamPath.RELAY)
        viewModel.streams.retry(key)
        runCurrent()
        assertEquals(2, engine.watchCalls.size)
        assertEquals(StreamWatch.Watching(StreamPath.RELAY), viewModel.stream.watch)
    }

    @Test
    fun theVolumeGoesToTheEngineAndAnEndedStreamClosesItsWindow() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.streams.open(key)
        viewModel.streams.changeVolume(key, 250)
        runCurrent()
        assertEquals(200, viewModel.stream.volume)
        assertEquals(200, engine.currentStreams().single().volume.toInt())

        engine.showStreams(emptyList())
        runCurrent()
        assertNull(viewModel.streams.state.value.open)
        assertTrue(viewModel.streams.state.value.streams.isEmpty())
    }
}
