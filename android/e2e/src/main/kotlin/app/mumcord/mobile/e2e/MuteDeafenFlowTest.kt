// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.e2e

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.platform.audio.AudioProbe
import app.mumcord.mobile.ui.connected.PERSON_ROW_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_DEAFEN_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_LEAVE_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_VIEW_TEST_TAG
import app.mumcord.mobile.ui.servers.SERVER_CONNECT_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_VOICE_ACTIVITY_TEST_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Mute, deafen and leave in the real app (AC-5.7) with a second person on the host
 * (`e2e/voice-peer`) who talks without a pause once it has heard the phone. Mute is used from
 * the voice bar on another tab, deafen and leave from the full voice view. e2e/run.sh checks
 * in the second person's report that the phone was heard three separate times: before the
 * mute, between unmuting and deafening, and after undeafening.
 * Expects fresh app data with local network access and the microphone granted.
 */
class MuteDeafenFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }

    @Before
    fun talkIntoTheMicrophone() = AudioProbe.replaceMicrophone(AudioProbe.Signal.Speech)

    @After
    fun giveTheMicrophoneBack() = AudioProbe.replaceMicrophone(null)

    private fun awaitTheSecondPersonsVoice(why: String) =
        app.await("the second person's voice from the speaker $why") {
            VoiceChecks.speakerLoudFor(VoiceChecks.STEADY_POLLS)
        }

    @Test
    fun muteFromTheVoiceBarOnAnotherTabAndDeafenAndLeaveFromTheVoiceView() {
        app.chooseTalkMode(TALK_MODE_VOICE_ACTIVITY_TEST_TAG)
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        app.awaitOrFail("the second person ${VoiceChecks.PEER_NAME} in the channel list") {
            compose.onAllNodes(hasTestTag(PERSON_ROW_TEST_TAG) and hasText(VoiceChecks.PEER_NAME))
                .fetchSemanticsNodes().isNotEmpty()
        }
        app.joinLobby()
        // The second person heard the phone and talks from now on.
        awaitTheSecondPersonsVoice("after hearing the phone")

        // Mute from the voice bar on another tab: the microphone stream stops, so nothing can
        // be sent; listening goes on.
        compose.onNodeWithText(TAB_CHAT).performClick()
        compose.onNodeWithContentDescription(MUTE).performClick()
        app.await("the microphone stream to stop after muting") { VoiceChecks.microphoneFramesIn(1_000) == 0L }
        // Waited for instead of measured once: on a busy emulator a single gap in what the
        // speaker plays would otherwise fail the test.
        awaitTheSecondPersonsVoice("while muted")
        compose.onNodeWithContentDescription(UNMUTE).performClick()
        app.await("the microphone stream to run again after unmuting") { VoiceChecks.microphoneFramesIn(500) > 0 }
        // Long enough for the second person to hear the phone as a new stretch of voice.
        Thread.sleep(HEARD_AGAIN_MILLIS)

        // Deafen from the full voice view: the speaker plays silence although the second
        // person keeps talking, and the microphone stops too (deafen implies mute).
        compose.onNodeWithText(VOICE_CONNECTED, substring = true).performClick()
        app.await("the full voice view") { app.exists(VOICE_VIEW_TEST_TAG) }
        compose.onNodeWithTag(VOICE_DEAFEN_TEST_TAG).performClick()
        app.await("silence from the speaker after deafening") { VoiceChecks.speakerQuietFor(VoiceChecks.STEADY_POLLS) }
        assertEquals("Deafen implies mute", 0L, VoiceChecks.microphoneFramesIn(1_000))
        compose.onNodeWithTag(VOICE_DEAFEN_TEST_TAG).performClick()
        awaitTheSecondPersonsVoice("after undeafening")
        app.await("the microphone stream to run again after undeafening") { VoiceChecks.microphoneFramesIn(500) > 0 }
        Thread.sleep(HEARD_AGAIN_MILLIS)

        // Leave from the full voice view.
        compose.onNodeWithTag(VOICE_LEAVE_TEST_TAG).performClick()
        app.awaitOrFail("the server list after leaving") { app.exists(SERVER_CONNECT_TEST_TAG) }
        assertTrue("The voice bar goes with the connection", !app.exists(VOICE_BAR_TEST_TAG))
        // Long enough for the second person to notice that the phone has gone.
        Thread.sleep(SPAN_END_MILLIS)
    }

    private companion object {
        const val TAB_CHAT = "Chat"
        const val MUTE = "Mute microphone"
        const val UNMUTE = "Unmute microphone"
        const val VOICE_CONNECTED = "Voice connected"
        const val HEARD_AGAIN_MILLIS = 3_000L
        /** Well over the silence after which the second person reports a stretch as over (0.6 s). */
        const val SPAN_END_MILLIS = 3_000L
    }
}
