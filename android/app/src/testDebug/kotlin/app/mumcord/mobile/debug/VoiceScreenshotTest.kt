// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.bridge.WhisperTarget
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.MOCKUP_LEA
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.fold
import app.mumcord.mobile.session.mockupVoiceServer
import app.mumcord.mobile.ui.connected.ConnectedController
import app.mumcord.mobile.ui.connected.ConnectedLocal
import app.mumcord.mobile.ui.connected.ConnectedSheetHost
import app.mumcord.mobile.ui.connected.ConnectedShell
import app.mumcord.mobile.ui.connected.connectedUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the voice bar, the full voice view and the whisper bar in both themes at the size of
 * the mockups, with the people of mockup `03-voice-expanded`: everybody in the Lobby.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class VoiceScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    // The status bar and home indicator areas of the mockup's phone.
    private val top = WindowInsets(top = 47.dp)
    private val bottom = WindowInsets(bottom = 34.dp)

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private lateinit var engine: FakeEngine

    // The fake engine only supplies the microphone level; nothing is tapped in a screenshot.
    private val repository = SessionRepository(
        { listener -> FakeEngine(listener, mockupVoiceServer()).also { engine = it } },
        scope,
        Dispatchers.Unconfined,
        log = {},
    )
    private val actions = ConnectedController(repository, scope)

    private fun session(talkMode: TalkMode, selfMuted: Boolean = false): SessionState {
        val server = mockupVoiceServer()
        return listOf(
            BridgeEvent.ConnectionChanged(ConnectionState.Connected),
            BridgeEvent.ServerSynced(
                server.copy(people = server.people.map { if (it.session == MOCKUP_LEA) it.copy(selfMuted = selfMuted) else it }),
            ),
        ).fold(SessionState(talkMode = talkMode)) { state, event -> state.fold(event) }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun capture(
        name: String,
        dark: Boolean,
        local: ConnectedLocal,
        session: SessionState = session(TalkMode.PUSH_TO_TALK),
    ) = composeRule.captureScreenshot("$name-${if (dark) "dark" else "light"}") {
        val state = checkNotNull(connectedUiState(session, local, "Nordlicht"))
        MumcordTheme(darkTheme = dark) {
            Box(Modifier.fillMaxSize()) {
                ConnectedShell(state, actions, onDisconnect = {}, onMicrophone = {}, topInsets = top, bottomInsets = bottom)
                state.sheet?.let {
                    ConnectedSheetHost(it, actions, top, bottom, onMicrophoneContinue = {}, onMicrophoneNotNow = {})
                }
            }
        }
    }

    private fun voiceBar(dark: Boolean) = capture("android-channels-voice-bar", dark, ConnectedLocal())

    private fun pushToTalk(dark: Boolean) = capture("android-voice-view-ptt", dark, ConnectedLocal(voiceOpen = true))

    // No mockup exists for the voice activity mode, the muted state and the whisper bar; they
    // are built from the design system.
    private fun voiceActivity(dark: Boolean) {
        runBlocking { repository.loadServers() }
        engine.emit(BridgeEvent.InputLevelChanged(60u))
        capture("android-voice-view-activity", dark, ConnectedLocal(voiceOpen = true), session(TalkMode.VOICE_ACTIVITY))
    }

    private fun muted(dark: Boolean) = capture(
        "android-voice-view-muted",
        dark,
        ConnectedLocal(voiceOpen = true),
        session(TalkMode.PUSH_TO_TALK, selfMuted = true),
    )

    private fun whisperBar(dark: Boolean) =
        capture("android-whisper-bar", dark, ConnectedLocal(whisperTarget = WhisperTarget.Person(MOCKUP_JONAS)))

    private fun personSheet(dark: Boolean) =
        capture("android-person-sheet-whisper", dark, ConnectedLocal(personSession = MOCKUP_JONAS))

    @Test fun voiceBarDark() = voiceBar(dark = true)

    @Test fun voiceBarLight() = voiceBar(dark = false)

    @Test fun pushToTalkDark() = pushToTalk(dark = true)

    @Test fun pushToTalkLight() = pushToTalk(dark = false)

    @Test fun voiceActivityDark() = voiceActivity(dark = true)

    @Test fun voiceActivityLight() = voiceActivity(dark = false)

    @Test fun mutedDark() = muted(dark = true)

    @Test fun mutedLight() = muted(dark = false)

    @Test fun whisperBarDark() = whisperBar(dark = true)

    @Test fun whisperBarLight() = whisperBar(dark = false)

    @Test fun personSheetDark() = personSheet(dark = true)

    @Test fun personSheetLight() = personSheet(dark = false)
}
