// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.espresso.Espresso
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.bridge.WhisperTarget
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.MOCKUP_GAMING
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.MOCKUP_LEA
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupVoiceServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connected.CHANNEL_MENU_SHOUT_TEST_TAG
import app.mumcord.mobile.ui.connected.DENIED_BANNER_TEST_TAG
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.MicrophoneNotice
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.PERSON_WHISPER_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_COLLAPSE_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_DEAFEN_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_HOLD_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_LEAVE_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_LEVEL_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_MUTE_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_TILE_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_VIEW_TEST_TAG
import app.mumcord.mobile.ui.connected.WHISPER_BAR_TEST_TAG
import app.mumcord.mobile.ui.connected.WHISPER_CLOSE_TEST_TAG
import app.mumcord.mobile.ui.connected.WHISPER_HOLD_TEST_TAG
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The voice bar, the full voice view and the whisper bar on a device against a fake engine,
 * with the people of mockup `03-voice-expanded`: everybody in the Lobby, the user is Lea.
 */
class VoiceUiFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FakeEngine
    private val microphone = FakeMicrophoneAccess()
    private var microphoneAsked = 0

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @After
    fun tearDown() = scope.cancel()

    /** Starts the app, connects to Nordlicht and waits for the voice bar. */
    private fun connect(talkMode: TalkMode = TalkMode.PUSH_TO_TALK): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupVoiceServer(), servers = listOf(savedServer(1, "Nordlicht")), talkMode = talkMode)
                    .also { engine = it }
            },
            scope = scope,
            engineDispatcher = Dispatchers.IO,
            log = {},
        )
        val viewModel = AppViewModel(repository, DoneSetup, FakeLocalNetworkGateway(), microphone)
        compose.setContent {
            MumcordTheme {
                AppRoute(
                    viewModel,
                    LocalNetworkSystem(ask = {}, openSettings = {}),
                    MicrophoneSystem(ask = { microphoneAsked++ }, openSettings = {}),
                )
            }
        }
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()
        await { shown(VOICE_BAR_TEST_TAG) }
        return viewModel
    }

    private fun AppViewModel.self() = uiState.value.session.server?.people?.get(MOCKUP_LEA)

    @Test
    fun theVoiceBarIsOnEveryTabAndMutesDeafensAndLeavesFromThere() {
        val viewModel = connect()
        compose.onNodeWithText("Nordlicht · Voice connected").assertIsDisplayed()

        for (tab in listOf("Chat", "Messages", "More")) {
            compose.onNodeWithText(tab).performClick()
            compose.onNodeWithTag(VOICE_BAR_TEST_TAG).assertIsDisplayed()
        }

        compose.onNodeWithContentDescription("Mute microphone").performClick()
        await { viewModel.self()?.selfMuted == true }
        compose.onNodeWithContentDescription("Unmute microphone").performClick()
        await { viewModel.self()?.selfMuted == false }

        compose.onNodeWithContentDescription("Deafen").performClick()
        await { viewModel.self()?.selfDeafened == true && viewModel.self()?.selfMuted == true }
        compose.onNodeWithContentDescription("Undeafen").performClick()
        await { viewModel.self()?.selfDeafened == false }

        compose.onNodeWithContentDescription("Leave server").performClick()
        await { !shown(VOICE_BAR_TEST_TAG) && exists("Connect to Nordlicht") }
    }

    @Test
    fun theVoiceViewOpensByTapAndBySwipeShowsThePeopleAndClosesByChevronAndBack() {
        connect()
        compose.onNodeWithText("Nordlicht · Voice connected").performClick()
        await { shown(VOICE_VIEW_TEST_TAG) }
        assertEquals(5, compose.onAllNodesWithTag(VOICE_TILE_TEST_TAG).fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Lea (you)").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ben").assertIsDisplayed()

        compose.onNodeWithTag(VOICE_COLLAPSE_TEST_TAG).performClick()
        await { !shown(VOICE_VIEW_TEST_TAG) }

        compose.onNodeWithTag(VOICE_BAR_TEST_TAG).performTouchInput { swipeUp() }
        await { shown(VOICE_VIEW_TEST_TAG) }
        Espresso.pressBack()
        await { !shown(VOICE_VIEW_TEST_TAG) && shown(VOICE_BAR_TEST_TAG) }
    }

    @Test
    fun holdToTalkTalksOnlyWhileHeldAndIsLetGoWhenTheViewCloses() {
        connect()
        compose.onNodeWithTag(VOICE_BAR_TEST_TAG).performTouchInput { swipeUp() }
        await { shown(VOICE_HOLD_TEST_TAG) }
        assertFalse(engine.pushToTalkPressed)

        compose.onNodeWithTag(VOICE_HOLD_TEST_TAG).performTouchInput { down(center) }
        await { engine.pushToTalkPressed && exists("Talking") }
        compose.onNodeWithTag(VOICE_HOLD_TEST_TAG).performTouchInput { up() }
        await { !engine.pushToTalkPressed && exists("Hold to talk") }

        // A finger that is still down when the view goes away must not keep talking.
        compose.onNodeWithTag(VOICE_HOLD_TEST_TAG).performTouchInput { down(center) }
        await { engine.pushToTalkPressed }
        Espresso.pressBack()
        await { !shown(VOICE_VIEW_TEST_TAG) && !engine.pushToTalkPressed }
    }

    @Test
    fun theTalkModeSwitchChangesTheEngineAtOnceAndSwapsTheButtonForTheLevel() {
        val viewModel = connect()
        compose.runOnUiThread { viewModel.connected.openVoice() }
        await { shown(VOICE_HOLD_TEST_TAG) }

        compose.onNodeWithText("Voice activity").performClick()
        await { engine.talkMode == TalkMode.VOICE_ACTIVITY && shown(VOICE_LEVEL_TEST_TAG) && !shown(VOICE_HOLD_TEST_TAG) }
        engine.emit(BridgeEvent.InputLevelChanged(70u))
        await { viewModel.connected.inputLevel.value == 70 }

        compose.onNodeWithText("Push-to-talk").performClick()
        await { engine.talkMode == TalkMode.PUSH_TO_TALK && shown(VOICE_HOLD_TEST_TAG) }
    }

    @Test
    fun theVoiceViewMutesDeafensAndLeaves() {
        val viewModel = connect()
        compose.runOnUiThread { viewModel.connected.openVoice() }
        await { shown(VOICE_VIEW_TEST_TAG) }

        compose.onNodeWithTag(VOICE_MUTE_TEST_TAG).performClick()
        await { viewModel.self()?.selfMuted == true && exists("Unmute microphone") }
        compose.onNodeWithTag(VOICE_DEAFEN_TEST_TAG).performClick()
        await { viewModel.self()?.selfDeafened == true && exists("Undeafen") }
        compose.onNodeWithTag(VOICE_MUTE_TEST_TAG).performClick()
        await { viewModel.self()?.selfMuted == false }

        compose.onNodeWithTag(VOICE_LEAVE_TEST_TAG).performClick()
        await { !shown(VOICE_VIEW_TEST_TAG) && exists("Connect to Nordlicht") }
    }

    @Test
    fun aMuteByTheServerDisablesTheButtonAndSaysWhy() {
        val viewModel = connect()
        engine.emit(BridgeEvent.PersonChanged(viewModel.self()!!.copy(serverMuted = true)))
        await { viewModel.self()?.serverMuted == true }
        // The user's row in the channel list says the same; the button is the one that is disabled.
        val disabledMute = hasContentDescription("Muted by the server") and isNotEnabled()
        compose.onNode(disabledMute).assertIsDisplayed()

        compose.runOnUiThread { viewModel.connected.openVoice() }
        await { shown(VOICE_VIEW_TEST_TAG) }
        compose.onNode(disabledMute).assertIsDisplayed()
        assertFalse("A disabled button mutes nothing", engine.selfMuted)
    }

    @Test
    fun withoutTheMicrophoneTheMuteButtonLeadsToThePermission() {
        microphone.notice.value = MicrophoneNotice.Denied
        val viewModel = connect()
        compose.onNodeWithContentDescription("Microphone not allowed. Allow the microphone").performClick()
        await { microphoneAsked == 1 }
        assertFalse("The mute itself is not touched", engine.selfMuted)

        compose.runOnUiThread { viewModel.connected.openVoice() }
        await { shown(VOICE_VIEW_TEST_TAG) }
        compose.onNodeWithTag(VOICE_MUTE_TEST_TAG).performClick()
        await { microphoneAsked == 2 }
    }

    @Test
    fun whisperToAPersonSendsOnlyWhileHeldAndClosingTheBarEndsIt() {
        val viewModel = connect()
        compose.onNodeWithText("Jonas").performClick()
        await { shown(PERSON_WHISPER_TEST_TAG) }
        compose.onNodeWithText("Whisper to Jonas").performClick()
        await { shown(WHISPER_BAR_TEST_TAG) && !shown(PERSON_WHISPER_TEST_TAG) }
        compose.onNodeWithText("Hold to whisper to Jonas").assertIsDisplayed()
        assertNull(engine.whisperTarget)

        compose.onNodeWithTag(WHISPER_HOLD_TEST_TAG).performTouchInput { down(center) }
        await { engine.whisperTarget == WhisperTarget.Person(MOCKUP_JONAS) }
        compose.onNodeWithTag(WHISPER_HOLD_TEST_TAG).performTouchInput { up() }
        await { engine.whisperTarget == null }
        compose.onNodeWithTag(WHISPER_CLOSE_TEST_TAG).assertIsDisplayed()

        // A second finger closes the bar while the first still holds the button.
        compose.onNodeWithTag(WHISPER_HOLD_TEST_TAG).performTouchInput { down(center) }
        await { engine.whisperTarget != null }
        compose.runOnUiThread { viewModel.connected.closeWhisper() }
        await { !shown(WHISPER_BAR_TEST_TAG) && engine.whisperTarget == null }
    }

    @Test
    fun shoutToAChannelARefusalIsShownCalmlyAndTheBarGoesWhenTheTargetLeaves() {
        val viewModel = connect()
        compose.onNodeWithText("Gaming").performTouchInput { longClick() }
        await { shown(CHANNEL_MENU_SHOUT_TEST_TAG) }
        compose.onNodeWithTag(CHANNEL_MENU_SHOUT_TEST_TAG).performClick()
        await { exists("Hold to shout to Gaming") }

        engine.whisperFailure = BridgeException.PermissionDenied("Shouting is not allowed here.")
        compose.onNodeWithTag(WHISPER_HOLD_TEST_TAG).performTouchInput { down(center) }
        // The banner reads its title and the server's words as one sentence.
        await {
            shown(DENIED_BANNER_TEST_TAG) &&
                compose.onAllNodesWithText("Shouting is not allowed here.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(WHISPER_HOLD_TEST_TAG).performTouchInput { up() }
        assertNull(engine.whisperTarget)

        engine.emit(BridgeEvent.ChannelRemoved(MOCKUP_GAMING))
        await { !shown(WHISPER_BAR_TEST_TAG) && viewModel.connected.local.value.whisperTarget == null }
    }

    private fun shown(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun exists(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()

    private fun await(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS, condition)

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
