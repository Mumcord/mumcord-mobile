// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.bridge.WhisperTarget
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.MOCKUP_GAMING
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.MOCKUP_LEA
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupVoiceServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.AppViewModel
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** The voice bar, the full voice view and the whisper bar through the view model, against the fake engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private lateinit var repository: SessionRepository

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A view model that is already connected to the mockup server, everybody in the Lobby. */
    private fun TestScope.connected(talkMode: TalkMode = TalkMode.PUSH_TO_TALK): AppViewModel {
        repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupVoiceServer(), servers = listOf(savedServer(1, "Nordlicht")), talkMode = talkMode)
                    .also { engine = it }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        return AppViewModel(repository, DoneSetup, FakeLocalNetworkGateway(), FakeMicrophoneAccess()).also { viewModel ->
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()
            viewModel.connect(1)
            runCurrent()
        }
    }

    private val AppViewModel.voice get() = uiState.value.connected!!.voice!!

    @Test
    fun theTalkModeChosenAtFirstStartDrivesTheVoiceView() = runTest(dispatcher) {
        assertEquals(TalkMode.PUSH_TO_TALK, connected(TalkMode.PUSH_TO_TALK).voice.talkMode)
    }

    @Test
    fun theVoiceViewOpensAndCloses() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.openVoice()
        runCurrent()
        assertTrue(viewModel.voice.open)
        viewModel.connected.closeVoice()
        runCurrent()
        assertFalse(viewModel.voice.open)
    }

    @Test
    fun muteAndDeafenAreSentToTheEngineAndComeBackAsTheOwnState() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.toggleMute()
        runCurrent()
        assertTrue(engine.selfMuted)
        assertEquals(MuteControl.Muted, viewModel.voice.mute)

        viewModel.connected.toggleMute()
        runCurrent()
        assertFalse(engine.selfMuted)
        assertEquals(MuteControl.Unmuted, viewModel.voice.mute)

        viewModel.connected.toggleDeafen()
        runCurrent()
        assertTrue(engine.selfDeafened)
        assertTrue(viewModel.voice.deafened)
        assertEquals("Deafen implies mute", MuteControl.Muted, viewModel.voice.mute)

        viewModel.connected.toggleDeafen()
        runCurrent()
        assertFalse(engine.selfDeafened)
        assertFalse(viewModel.voice.deafened)
    }

    @Test
    fun switchingTheTalkModeIsSavedAtOnceAndLetsGoOfTheButton() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.setTalkHeld(true)
        viewModel.connected.selectTalkMode(TalkMode.VOICE_ACTIVITY)
        runCurrent()
        assertEquals(TalkMode.VOICE_ACTIVITY, engine.talkMode)
        assertEquals(TalkMode.VOICE_ACTIVITY, viewModel.voice.talkMode)
        assertEquals(listOf(true, false), engine.pushToTalkCalls.toList())
        assertFalse(viewModel.voice.talkHeld)
    }

    @Test
    fun aTalkModeThatCannotBeSavedStaysAsItWasAndIsReported() = runTest(dispatcher) {
        val viewModel = connected()
        engine.talkModeFailure = BridgeException.Io("disk full")
        viewModel.connected.selectTalkMode(TalkMode.VOICE_ACTIVITY)
        runCurrent()
        assertEquals(TalkMode.PUSH_TO_TALK, viewModel.voice.talkMode)
        assertNotNull(viewModel.uiState.value.connected!!.denied)
    }

    @Test
    fun holdingTheButtonTalksAndLettingGoStops() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.setTalkHeld(true)
        runCurrent()
        assertTrue(engine.pushToTalkPressed)
        assertTrue(viewModel.voice.talkHeld)

        viewModel.connected.setTalkHeld(false)
        runCurrent()
        assertFalse(engine.pushToTalkPressed)
        assertFalse(viewModel.voice.talkHeld)
        assertEquals(listOf(true, false), engine.pushToTalkCalls.toList())
    }

    @Test
    fun inVoiceActivityModeTheButtonDoesNothing() = runTest(dispatcher) {
        val viewModel = connected(TalkMode.VOICE_ACTIVITY)
        viewModel.connected.setTalkHeld(true)
        runCurrent()
        assertEquals(emptyList<Boolean>(), engine.pushToTalkCalls.toList())
    }

    @Test
    fun theButtonIsLetGoWhenTheAppLeavesTheScreenTheViewClosesOrTheConnectionEnds() = runTest(dispatcher) {
        val viewModel = connected()
        // The app goes to the background.
        viewModel.connected.setTalkHeld(true)
        viewModel.connected.releaseHolds()
        assertFalse(engine.pushToTalkPressed)

        viewModel.connected.openVoice()
        viewModel.connected.setTalkHeld(true)
        viewModel.connected.closeVoice()
        assertFalse(engine.pushToTalkPressed)

        viewModel.connected.setTalkHeld(true)
        engine.emit(BridgeEvent.ConnectionChanged(ConnectionState.Disconnected(null)))
        runCurrent()
        assertFalse(engine.pushToTalkPressed)
        assertEquals(listOf(true, false, true, false, true, false), engine.pushToTalkCalls.toList())
    }

    @Test
    fun lettingGoWithoutHoldingSendsNothing() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.releaseHolds()
        viewModel.connected.setTalkHeld(false)
        assertEquals(emptyList<Boolean>(), engine.pushToTalkCalls.toList())
        assertEquals(emptyList<WhisperTarget?>(), engine.whisperCalls.toList())
    }

    @Test
    fun whisperOpensTheBarClosesTheSheetAndOnlySendsWhileHeld() = runTest(dispatcher) {
        val viewModel = connected()
        val jonas = WhisperTarget.Person(MOCKUP_JONAS)
        viewModel.connected.openPerson(MOCKUP_JONAS)
        viewModel.connected.openWhisper(jonas)
        runCurrent()
        assertNull(viewModel.uiState.value.connected!!.sheet)
        assertEquals(WhisperBarState("Jonas", shout = false, held = false), viewModel.voice.whisper)
        assertNull(engine.whisperTarget)

        viewModel.connected.setWhisperHeld(true)
        runCurrent()
        assertEquals(jonas, engine.whisperTarget)
        assertTrue(viewModel.voice.whisper!!.held)

        viewModel.connected.setWhisperHeld(false)
        runCurrent()
        assertNull(engine.whisperTarget)
        assertFalse(viewModel.voice.whisper!!.held)
        assertEquals(listOf(jonas, null), engine.whisperCalls.toList())
    }

    @Test
    fun shoutGoesToTheChannel() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.openChannelMenu(MOCKUP_GAMING)
        viewModel.connected.openWhisper(WhisperTarget.Channel(MOCKUP_GAMING))
        viewModel.connected.setWhisperHeld(true)
        runCurrent()
        assertNull(viewModel.uiState.value.connected!!.sheet)
        assertEquals(WhisperBarState("Gaming", shout = true, held = true), viewModel.voice.whisper)
        assertEquals(WhisperTarget.Channel(MOCKUP_GAMING), engine.whisperTarget)
    }

    @Test
    fun aReleaseThatComesBeforeTheEngineStartedStillEndsTheWhisper() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.openWhisper(WhisperTarget.Person(MOCKUP_JONAS))
        viewModel.connected.setWhisperHeld(true)
        viewModel.connected.setWhisperHeld(false)
        runCurrent()
        assertNull(engine.whisperTarget)
        assertFalse(viewModel.voice.whisper!!.held)
    }

    @Test
    fun closingTheBarEndsTheWhisper() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.openWhisper(WhisperTarget.Person(MOCKUP_JONAS))
        viewModel.connected.setWhisperHeld(true)
        runCurrent()
        viewModel.connected.closeWhisper()
        runCurrent()
        assertNull(engine.whisperTarget)
        assertNull(viewModel.voice.whisper)
    }

    @Test
    fun aRefusedWhisperIsShownInTheServersWordsAndTheButtonIsNotHeld() = runTest(dispatcher) {
        val viewModel = connected()
        engine.whisperFailure = BridgeException.PermissionDenied("Whispering is not allowed here.")
        viewModel.connected.openWhisper(WhisperTarget.Person(MOCKUP_JONAS))
        viewModel.connected.setWhisperHeld(true)
        runCurrent()
        assertEquals(DeniedBanner(null, "Whispering is not allowed here."), viewModel.uiState.value.connected!!.denied)
        assertFalse(viewModel.voice.whisper!!.held)
        assertNull(engine.whisperTarget)
    }

    @Test
    fun theBarClosesAndTheWhisperEndsWhenThePersonLeavesTheServer() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.openWhisper(WhisperTarget.Person(MOCKUP_JONAS))
        viewModel.connected.setWhisperHeld(true)
        runCurrent()
        engine.emit(BridgeEvent.PersonRemoved(MOCKUP_JONAS))
        runCurrent()
        assertNull(viewModel.voice.whisper)
        assertNull(viewModel.connected.local.value.whisperTarget)
        assertNull(engine.whisperTarget)
    }

    @Test
    fun theWhisperIsLetGoWhenTheAppLeavesTheScreenButTheBarStays() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.openWhisper(WhisperTarget.Person(MOCKUP_JONAS))
        viewModel.connected.setWhisperHeld(true)
        runCurrent()
        viewModel.connected.releaseHolds()
        runCurrent()
        assertNull(engine.whisperTarget)
        assertEquals(WhisperBarState("Jonas", shout = false, held = false), viewModel.voice.whisper)
    }

    @Test
    fun theMicrophoneLevelHasItsOwnFlowAndDoesNotTouchTheScreenState() = runTest(dispatcher) {
        val viewModel = connected()
        val before = viewModel.uiState.value
        engine.emit(BridgeEvent.InputLevelChanged(42u))
        runCurrent()
        assertEquals(42, viewModel.connected.inputLevel.value)
        assertTrue("The same state object: nothing was folded", before === viewModel.uiState.value)
        assertEquals(MOCKUP_LEA, viewModel.uiState.value.session.server!!.selfSession)
    }
}
