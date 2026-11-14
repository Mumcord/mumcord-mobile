// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.e2e

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.platform.audio.AudioProbe
import app.mumcord.mobile.ui.connected.PERSON_ROW_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_HOLD_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_PUSH_TO_TALK_TEST_TAG
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Push-to-talk in the real app (AC-5.6) with a second person on the host (`e2e/voice-peer`).
 * The microphone delivers a voice the whole time, so whatever the phone lets through is heard.
 * The second person starts talking only after hearing the phone, so silence from the speaker
 * proves that nothing was sent. e2e/run.sh compares how long the second person heard the
 * phone with how long the button was held, which this test reports.
 * Expects fresh app data with local network access and the microphone granted.
 */
class PushToTalkFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }

    @Before
    fun talkIntoTheMicrophone() = AudioProbe.replaceMicrophone(AudioProbe.Signal.Speech)

    @After
    fun giveTheMicrophoneBack() = AudioProbe.replaceMicrophone(null)

    @Test
    fun nothingIsSentUntilHoldToTalkIsHeldAndItStopsWhenReleased() {
        app.chooseTalkMode(TALK_MODE_PUSH_TO_TALK_TEST_TAG)
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        app.awaitOrFail("the second person ${VoiceChecks.PEER_NAME} in the channel list") {
            compose.onAllNodes(hasTestTag(PERSON_ROW_TEST_TAG) and hasText(VoiceChecks.PEER_NAME))
                .fetchSemanticsNodes().isNotEmpty()
        }
        app.joinLobby()
        app.await("the microphone stream to run between presses") { VoiceChecks.microphoneFramesIn(500) > 0 }

        compose.onNodeWithTag(VOICE_BAR_TEST_TAG).performTouchInput { swipeUp() }
        app.await("the hold-to-talk button of the voice view") { app.exists(VOICE_HOLD_TEST_TAG) }

        // The voice is at the microphone, but the button is not held: the second person would
        // answer after two seconds of hearing it, and does not.
        app.await("the engine's own sounds to end") { VoiceChecks.speakerQuietFor(VoiceChecks.STEADY_POLLS) }
        assertTrue(
            "Nothing may be sent while the button is not held",
            VoiceChecks.speakerQuietFor(QUIET_POLLS_BEFORE_HOLDING),
        )

        val held = VoiceChecks.hold(compose, VOICE_HOLD_TEST_TAG, HOLD_MILLIS)
        VoiceChecks.report("ptt: held_ms=$held")

        // Having heard the phone, the second person talks from now on.
        app.await("the second person's answer in what the speaker plays") {
            VoiceChecks.speakerLoudFor(VoiceChecks.STEADY_POLLS)
        }
        // Long enough for the second person to notice that the phone has gone silent.
        Thread.sleep(SPAN_END_MILLIS)
        app.leave()
    }

    private companion object {
        const val HOLD_MILLIS = 4_000L

        /** Six seconds: three times what the second person needs to answer. */
        const val QUIET_POLLS_BEFORE_HOLDING = 24
        const val SPAN_END_MILLIS = 1_500L
    }
}
