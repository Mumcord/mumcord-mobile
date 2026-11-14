// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.os.Bundle
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.platform.audio.AudioDirection
import app.mumcord.mobile.platform.audio.AudioProbe
import app.mumcord.mobile.ui.connected.PERSON_ROW_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_VOICE_ACTIVITY_TEST_TAG
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Voice in both directions in the real app, through the real microphone and speaker streams,
 * with a second person on the host (`e2e/voice-peer`, started by e2e/run.sh). The emulator has
 * no real microphone (it delivers a sound of its own) and no listener, so the app's test probe
 * stands in for both: it decides what the microphone stream sends, silence first and then a
 * speech-like signal, and measures what the speaker stream plays.
 *
 * The second person only starts talking after hearing the phone, so the level this test
 * measures proves both directions; e2e/run.sh also checks the second person's own report.
 * Expects fresh app data with local network access and the microphone granted.
 */
class VoiceFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }

    // The emulator's microphone delivers a sound of its own; until the test talks, it is silent.
    @Before
    fun silenceTheMicrophone() = AudioProbe.replaceMicrophone(AudioProbe.Signal.Silence)

    @After
    fun giveTheMicrophoneBack() = AudioProbe.replaceMicrophone(null)

    @Test
    fun thePhoneIsHeardByASecondPersonAndHearsThemTalk() {
        app.chooseTalkMode(TALK_MODE_VOICE_ACTIVITY_TEST_TAG)
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        app.awaitOrFail("the second person $PEER_NAME in the channel list") {
            compose.onAllNodes(hasTestTag(PERSON_ROW_TEST_TAG) and hasText(PEER_NAME)).fetchSemanticsNodes().isNotEmpty()
        }
        // The second person waits in the Lobby.
        app.joinLobby()

        // Both streams run because the engine wants them, not because the test started them.
        for (direction in AudioDirection.entries) {
            val before = AudioProbe.frames(direction)
            app.await("the ${direction.name.lowercase()} stream to exchange audio with the engine") {
                AudioProbe.frames(direction) > before + HALF_A_SECOND_OF_FRAMES
            }
        }
        // Nobody talks yet. The engine's own short sounds (somebody entered the channel) pass,
        // then the speaker plays silence for as long as the phone stays silent itself.
        app.await("the speaker to play silence while nobody talks") { quietFor(QUIET_POLLS) }

        AudioProbe.replaceMicrophone(AudioProbe.Signal.Speech)
        var loudest = 0f
        var loudPolls = 0
        app.await("the second person's voice in what the speaker plays") {
            Thread.sleep(POLL_MILLIS)
            val level = AudioProbe.takePlaybackLevel()
            loudest = maxOf(loudest, level)
            loudPolls = if (level >= HEARD) loudPolls + 1 else 0
            // Two seconds without a gap: a voice, not a short sound of the engine.
            loudPolls >= HEARD_POLLS
        }
        report("voice: playback level $loudest (threshold $HEARD) after silence")

        app.disconnect()
    }

    /** Whether the speaker played nothing but silence during the next [polls] polls. */
    private fun quietFor(polls: Int): Boolean {
        AudioProbe.takePlaybackLevel()
        repeat(polls) {
            Thread.sleep(POLL_MILLIS)
            if (AudioProbe.takePlaybackLevel() >= HEARD) return false
        }
        return true
    }

    /** A line in the output of the test run, next to the test's result. */
    private fun report(line: String) {
        InstrumentationRegistry.getInstrumentation()
            .sendStatus(0, Bundle().apply { putString("stream", "\n$line\n") })
    }

    private companion object {
        /** The name `e2e/voice-peer` connects with. */
        const val PEER_NAME = "e2e-peer"
        const val HALF_A_SECOND_OF_FRAMES = 24_000L
        const val POLL_MILLIS = 250L
        const val QUIET_POLLS = 8
        const val HEARD_POLLS = 8

        /** Level (RMS of the loudest block) from which the speaker counts as playing a voice. */
        const val HEARD = 0.02f
    }
}
