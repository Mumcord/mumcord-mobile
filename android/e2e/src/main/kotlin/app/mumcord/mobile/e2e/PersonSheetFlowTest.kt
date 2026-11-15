// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.platform.audio.AudioProbe
import app.mumcord.mobile.ui.connected.PERSON_MUTE_TEST_TAG
import app.mumcord.mobile.ui.connected.PERSON_ROW_TEST_TAG
import app.mumcord.mobile.ui.connected.PERSON_SHEET_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_VOICE_ACTIVITY_TEST_TAG
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The person sheet's "Volume for you" and "Mute for me" (AC-5.14) in the real app with a second
 * person on the host (`e2e/voice-peer`), who talks without a pause once it has heard the phone,
 * and a third (`e2e/voice-peer --listen-only`) who only listens. The app's test probe measures
 * what the phone's speaker plays: lowering the volume makes the second person quieter, "Mute for
 * me" silences them, and switching it off brings them back. That this changes only the phone is
 * the third person's half of the proof: e2e/run.sh checks that it heard the second person
 * without a break for at least as long as the phone had them muted. Expects fresh app data with
 * local network access and the microphone granted.
 */
class PersonSheetFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }

    @Before
    fun silenceTheMicrophone() = AudioProbe.replaceMicrophone(AudioProbe.Signal.Silence)

    @After
    fun giveTheMicrophoneBack() = AudioProbe.replaceMicrophone(null)

    @Test
    fun theVolumeAndMuteForMeChangeOnlyWhatThePhonePlays() {
        app.chooseTalkMode(TALK_MODE_VOICE_ACTIVITY_TEST_TAG)
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        app.awaitOrFail("the second person ${VoiceChecks.PEER_NAME} in the channel list") { peerRowShown() }
        app.joinLobby()

        // The phone talks until the second person heard it and talks back, then stays silent,
        // so that the speaker plays nothing but the second person.
        AudioProbe.replaceMicrophone(AudioProbe.Signal.Speech)
        app.await("the second person's voice in what the speaker plays") { VoiceChecks.speakerLoudFor(VoiceChecks.STEADY_POLLS) }
        AudioProbe.replaceMicrophone(AudioProbe.Signal.Silence)
        val normal = steadyLevel()

        compose.onNode(hasTestTag(PERSON_ROW_TEST_TAG) and hasText(VoiceChecks.PEER_NAME)).performClick()
        app.awaitOrFail("the person sheet") { app.exists(PERSON_SHEET_TEST_TAG) }

        setVolume(LOW_VOLUME)
        var lowered = 0f
        app.await("the second person to be played more quietly") {
            lowered = steadyLevel()
            lowered < normal * QUIETER_FACTOR
        }
        VoiceChecks.report("person sheet: playback level $normal at 100 %, $lowered at 20 %")
        setVolume(NORMAL_VOLUME)

        compose.onNodeWithTag(PERSON_MUTE_TEST_TAG).performClick()
        val mutedAt = System.nanoTime()
        app.await("the speaker to play silence with the second person muted for the phone") {
            VoiceChecks.speakerQuietFor(VoiceChecks.STEADY_POLLS)
        }
        // A stretch long enough for the third person's report to tell it from a short dropout.
        Thread.sleep(MUTED_MILLIS)
        assertTrue("The speaker played the muted person", VoiceChecks.speakerQuietFor(VoiceChecks.STEADY_POLLS))
        compose.onNodeWithTag(PERSON_MUTE_TEST_TAG).performClick()
        app.await("the second person's voice again after unmuting") { VoiceChecks.speakerLoudFor(VoiceChecks.STEADY_POLLS) }
        VoiceChecks.report("person sheet: muted for me ms=${(System.nanoTime() - mutedAt) / 1_000_000}")
    }

    private fun peerRowShown(): Boolean =
        compose.onAllNodes(hasTestTag(PERSON_ROW_TEST_TAG) and hasText(VoiceChecks.PEER_NAME))
            .fetchSemanticsNodes().isNotEmpty()

    /** The median level of a few polls: one loud block or one gap does not decide. */
    private fun steadyLevel(): Float {
        AudioProbe.takePlaybackLevel()
        val levels = List(LEVEL_POLLS) {
            Thread.sleep(VoiceChecks.POLL_MILLIS)
            AudioProbe.takePlaybackLevel()
        }
        return levels.sorted()[LEVEL_POLLS / 2]
    }

    /** Moves the volume slider of the open person sheet; 1.0 is its top, 200 %. */
    private fun setVolume(fraction: Float) {
        // The sheet's slider is the only one on the screen.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(fraction) }
    }

    private companion object {
        const val NORMAL_VOLUME = 0.5f
        const val LOW_VOLUME = 0.1f
        /** At 20 % the level must fall clearly, well beyond what the voice itself varies. */
        const val QUIETER_FACTOR = 0.6f
        const val LEVEL_POLLS = 8
        const val MUTED_MILLIS = 4_000L
    }
}
