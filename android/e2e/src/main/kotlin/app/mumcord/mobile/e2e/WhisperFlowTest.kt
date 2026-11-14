// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.e2e

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.platform.audio.AudioProbe
import app.mumcord.mobile.ui.connected.DENIED_BANNER_TEST_TAG
import app.mumcord.mobile.ui.connected.PERSON_ROW_TEST_TAG
import app.mumcord.mobile.ui.connected.PERSON_WHISPER_TEST_TAG
import app.mumcord.mobile.ui.connected.WHISPER_BAR_TEST_TAG
import app.mumcord.mobile.ui.connected.WHISPER_CLOSE_TEST_TAG
import app.mumcord.mobile.ui.connected.WHISPER_HOLD_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_PUSH_TO_TALK_TEST_TAG
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Whisper in the real app (AC-5.15) with two more people on the host in the same channel, who
 * only listen (`e2e/voice-peer --listen-only`). The phone whispers to the first while the
 * button of the whisper bar is held. e2e/run.sh checks their reports: the first heard the
 * phone, the second heard nothing. The phone is in push-to-talk mode with a voice at the
 * microphone, so nothing but the whisper can reach anybody.
 * Expects fresh app data with local network access and the microphone granted.
 */
class WhisperFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }

    @Before
    fun talkIntoTheMicrophone() = AudioProbe.replaceMicrophone(AudioProbe.Signal.Speech)

    @After
    fun giveTheMicrophoneBack() = AudioProbe.replaceMicrophone(null)

    private fun personRow(name: String) = hasTestTag(PERSON_ROW_TEST_TAG) and hasText(name)

    @Test
    fun onlyThePersonWhisperedToHearsThePhoneWhileTheButtonIsHeld() {
        app.chooseTalkMode(TALK_MODE_PUSH_TO_TALK_TEST_TAG)
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        for (name in listOf(VoiceChecks.PEER_NAME, BYSTANDER_NAME)) {
            app.awaitOrFail("$name in the channel list") {
                compose.onAllNodes(personRow(name)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        app.joinLobby()
        app.await("the microphone stream to run") { VoiceChecks.microphoneFramesIn(500) > 0 }

        compose.onNode(personRow(VoiceChecks.PEER_NAME)).performClick()
        app.await("the person sheet with the whisper row") { app.exists(PERSON_WHISPER_TEST_TAG) }
        compose.onNodeWithTag(PERSON_WHISPER_TEST_TAG).performClick()
        app.await("the whisper bar") { app.exists(WHISPER_BAR_TEST_TAG) }

        val held = VoiceChecks.hold(compose, WHISPER_HOLD_TEST_TAG, HOLD_MILLIS)
        VoiceChecks.report("whisper: held_ms=$held")
        assertFalse("The server refused the whisper", app.exists(DENIED_BANNER_TEST_TAG))

        compose.onNodeWithTag(WHISPER_CLOSE_TEST_TAG).performClick()
        app.await("the whisper bar to close") { !app.exists(WHISPER_BAR_TEST_TAG) }
        // Long enough for the others to notice that the phone has gone silent.
        Thread.sleep(SPAN_END_MILLIS)
        app.leave()
    }

    private companion object {
        /** The name of the second `e2e/voice-peer`, who must not hear the whisper. */
        const val BYSTANDER_NAME = "e2e-bystander"
        const val HOLD_MILLIS = 4_000L
        /** Well over the silence after which the second person reports a stretch as over (0.6 s). */
        const val SPAN_END_MILLIS = 3_000L
    }
}
