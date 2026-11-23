// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.mumcord.mobile.bridge.DefaultOutput
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.platform.call.OutputKind
import app.mumcord.mobile.platform.call.OutputState
import app.mumcord.mobile.session.BUDS
import app.mumcord.mobile.session.EARPIECE
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.FakeOutputs
import app.mumcord.mobile.session.SPEAKER
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.VoiceOutput
import app.mumcord.mobile.session.mockupVoiceServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.OUTPUT_CHIP_TEST_TAG
import app.mumcord.mobile.ui.connected.OUTPUT_QUESTION_EARPIECE_TEST_TAG
import app.mumcord.mobile.ui.connected.OUTPUT_QUESTION_SPEAKER_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
import app.mumcord.mobile.ui.connected.outputRowTestTag
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The audio output on a device against a fake engine and fake outputs (FR-5.34, AC-5.11): the
 * first-connect question, the chip in the voice view and the output sheet. The test plays the
 * phone: it says which outputs there are and takes a choice at once.
 */
class OutputUiFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FakeEngine
    private val outputs = FakeOutputs()
    private val phone = OutputState(available = listOf(SPEAKER, EARPIECE), current = EARPIECE)

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @After
    fun tearDown() = scope.cancel()

    /** Starts the app, connects to Nordlicht and gives the session the phone's outputs. */
    private fun connect(default: DefaultOutput) {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupVoiceServer(), servers = listOf(savedServer(1, "Nordlicht"))).also {
                    it.mobileSettings = it.mobileSettings.copy(defaultOutput = default)
                    engine = it
                }
            },
            scope = scope,
            engineDispatcher = Dispatchers.IO,
            log = {},
        )
        runBlocking { repository.loadServers() }
        val viewModel = AppViewModel(
            repository,
            DoneSetup,
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            outputs = VoiceOutput(repository, outputs, CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))),
        )
        compose.setContent {
            MumcordTheme {
                AppRoute(
                    viewModel,
                    LocalNetworkSystem(ask = {}, openSettings = {}),
                    MicrophoneSystem(ask = {}, openSettings = {}),
                )
            }
        }
        compose.connectToNordlicht()
        await { shown(VOICE_BAR_TEST_TAG) }
        outputs.state.value = phone
    }

    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun await(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS, condition)

    private fun openVoiceView() {
        compose.onNodeWithText("Nordlicht · Voice connected").performClick()
        await { shown(OUTPUT_CHIP_TEST_TAG) }
    }

    @Test
    fun theFirstConnectionAsksLoudspeakerOrEarpieceAndRemembersTheAnswer() {
        connect(DefaultOutput.ASK)

        await { shown(OUTPUT_QUESTION_SPEAKER_TEST_TAG) }
        compose.onNodeWithText("Loudspeaker or earpiece?").assertIsDisplayed()
        compose.onNodeWithTag(OUTPUT_QUESTION_EARPIECE_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OUTPUT_QUESTION_SPEAKER_TEST_TAG).performClick()

        await { !shown(OUTPUT_QUESTION_SPEAKER_TEST_TAG) }
        await { engine.mobileSettings.defaultOutput == DefaultOutput.SPEAKER }
        assertEquals(listOf(SPEAKER.id), outputs.selected)

        openVoiceView()
        compose.onNodeWithContentDescription("Audio output: Speaker").assertIsDisplayed()
    }

    @Test
    fun aLaterConnectionDoesNotAskAndTheSheetSwitchesBetweenAllOutputs() {
        connect(DefaultOutput.SPEAKER)
        openVoiceView()
        await { outputs.state.value.current == SPEAKER }
        check(!shown(OUTPUT_QUESTION_SPEAKER_TEST_TAG)) { "The question was asked although an answer is saved" }

        compose.onNodeWithTag(OUTPUT_CHIP_TEST_TAG).performClick()
        compose.onNodeWithText("Audio output").assertIsDisplayed()
        compose.onNodeWithTag(outputRowTestTag(OutputKind.Speaker)).assertIsSelected()
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithTag(outputRowTestTag(OutputKind.WiredHeadset)).assertIsNotEnabled()
        compose.onNodeWithText("A connected headset is used automatically").assertIsDisplayed()

        compose.onNodeWithTag(outputRowTestTag(OutputKind.Earpiece)).performClick()

        await { outputs.state.value.current == EARPIECE }
        compose.onNodeWithContentDescription("Audio output: Earpiece").assertIsDisplayed()
    }

    @Test
    fun aBluetoothHeadsetThatConnectsTakesTheVoiceAndIsListedByItsName() {
        connect(DefaultOutput.SPEAKER)
        openVoiceView()

        outputs.state.value = OutputState(available = listOf(SPEAKER, EARPIECE, BUDS), current = SPEAKER)

        await { outputs.state.value.current == BUDS }
        compose.onNodeWithContentDescription("Audio output: Lea's Buds").performClick()
        compose.onNodeWithTag(outputRowTestTag(OutputKind.Bluetooth)).assertIsSelected()
        compose.onNodeWithText("Bluetooth").assertIsDisplayed()

        // The headset is switched off: the phone takes the voice back and the app follows.
        outputs.state.value = phone
        await { outputs.state.value.current == SPEAKER }
        await { compose.onAllNodesWithTag(outputRowTestTag(OutputKind.Bluetooth)).fetchSemanticsNodes().isEmpty() }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
