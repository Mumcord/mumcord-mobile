// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.bridge.DefaultOutput
import app.mumcord.mobile.platform.call.OutputKind
import app.mumcord.mobile.platform.call.OutputState
import app.mumcord.mobile.session.BUDS
import app.mumcord.mobile.session.EARPIECE
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.FakeOutputs
import app.mumcord.mobile.session.SPEAKER
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.fold
import app.mumcord.mobile.session.VoiceOutput
import app.mumcord.mobile.session.WIRED
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The output chip, the output sheet and the first-connect question through the view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class OutputUiTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private val outputs = FakeOutputs()
    private val phone = OutputState(available = listOf(SPEAKER, EARPIECE), current = EARPIECE)

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Connected to the mockup server; the phone's outputs appear with the session, as on a phone. */
    private suspend fun TestScope.connected(default: DefaultOutput = DefaultOutput.SPEAKER): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupVoiceServer(), servers = listOf(savedServer(1, "Nordlicht"))).also {
                    it.mobileSettings = it.mobileSettings.copy(defaultOutput = default)
                    engine = it
                }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        repository.loadServers()
        val viewModel = AppViewModel(
            repository,
            DoneSetup,
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            outputs = VoiceOutput(repository, outputs, backgroundScope),
        )
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        viewModel.connect(1)
        runCurrent()
        outputs.state.value = phone
        runCurrent()
        return viewModel
    }

    private val AppViewModel.sheet get() = uiState.value.connected!!.sheet
    private val AppViewModel.chip get() = uiState.value.connected!!.voice!!.output

    @Test
    fun theSheetListsSpeakerEarpieceBluetoothByNameAndTheWiredHeadsetLast() {
        val state = outputUiState(OutputState(available = listOf(BUDS, EARPIECE, SPEAKER), current = SPEAKER))!!

        assertEquals(
            listOf(
                OutputRow(SPEAKER.id, OutputKind.Speaker, "Speaker", selected = true),
                OutputRow(EARPIECE.id, OutputKind.Earpiece, "Earpiece", selected = false),
                OutputRow(BUDS.id, OutputKind.Bluetooth, "Lea's Buds", selected = false),
                OutputRow(id = null, OutputKind.WiredHeadset, "", selected = false),
            ),
            state.rows,
        )
    }

    @Test
    fun aConnectedWiredHeadsetCanBePickedAndWithoutASessionThereIsNothingToShow() {
        val state = outputUiState(OutputState(available = listOf(SPEAKER, WIRED), current = WIRED))!!

        assertEquals(OutputRow(WIRED.id, OutputKind.WiredHeadset, "Wired headset", selected = true), state.rows.last())
        assertNull(outputUiState(OutputState()))
    }

    @Test
    fun theChipShowsTheOutputAndTheSheetSwitchesAtOnce() = runTest(dispatcher) {
        val viewModel = connected()
        assertEquals("The saved default was applied", SPEAKER, viewModel.chip)

        viewModel.connected.openVoice()
        viewModel.connected.openOutput()
        runCurrent()
        val sheet = viewModel.sheet as ConnectedSheet.AudioOutput
        assertTrue(sheet.rows.first { it.kind == OutputKind.Speaker }.selected)

        viewModel.connected.selectOutput(EARPIECE.id)
        runCurrent()

        assertEquals(EARPIECE, viewModel.chip)
        assertNull("The sheet closes with the choice", viewModel.sheet)
    }

    @Test
    fun theChipAndTheSheetFollowWhatThePhoneDoes() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.openVoice()
        viewModel.connected.openOutput()

        outputs.state.value = OutputState(available = listOf(SPEAKER, EARPIECE, BUDS), current = SPEAKER)
        runCurrent()

        assertEquals("A headset that connects is used", BUDS, viewModel.chip)
        val rows = (viewModel.sheet as ConnectedSheet.AudioOutput).rows
        assertTrue(rows.first { it.kind == OutputKind.Bluetooth }.selected)

        outputs.state.value = phone.copy(current = SPEAKER)
        runCurrent()
        assertEquals(SPEAKER, viewModel.chip)
    }

    @Test
    fun theFirstConnectionAsksAndTheAnswerClosesTheQuestion() = runTest(dispatcher) {
        val viewModel = connected(DefaultOutput.ASK)
        assertEquals(ConnectedSheet.OutputQuestion, viewModel.sheet)

        viewModel.connected.answerOutputQuestion(DefaultOutput.SPEAKER)
        runCurrent()

        assertNull(viewModel.sheet)
        assertEquals(SPEAKER, viewModel.chip)
        assertEquals(DefaultOutput.SPEAKER, engine.mobileSettings.defaultOutput)
    }

    @Test
    fun theQuestionCanBePutAwayAndClosingTheVoiceViewClosesTheSheet() = runTest(dispatcher) {
        val viewModel = connected(DefaultOutput.ASK)

        viewModel.connected.dismissOutputQuestion()
        runCurrent()
        assertNull(viewModel.sheet)
        assertEquals("What the phone chose stays", EARPIECE, viewModel.chip)

        viewModel.connected.openVoice()
        viewModel.connected.openOutput()
        runCurrent()
        viewModel.connected.closeVoice()
        runCurrent()
        assertNull(viewModel.sheet)
    }

    @Test
    fun theTwoPermissionQuestionsComeBeforeTheOutputQuestionAndThatBeforeTheUsersOwnSheets() {
        val session = listOf(
            BridgeEvent.ConnectionChanged(ConnectionState.Connected),
            BridgeEvent.ServerSynced(mockupVoiceServer()),
        ).fold(SessionState()) { state, event -> state.fold(event) }
        fun sheet(microphone: MicrophoneNotice = MicrophoneNotice.None, notifications: Boolean = false) = connectedUiState(
            session,
            ConnectedLocal(voiceOpen = true, outputOpen = true),
            "Nordlicht",
            microphone,
            notifications,
            output = phone,
            askOutput = true,
        )!!.sheet

        assertEquals(ConnectedSheet.Microphone, sheet(microphone = MicrophoneNotice.Explain, notifications = true))
        assertEquals(ConnectedSheet.Notifications, sheet(notifications = true))
        assertEquals(ConnectedSheet.OutputQuestion, sheet())
    }
}
