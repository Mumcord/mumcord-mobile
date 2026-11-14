// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.platform.audio.AudioProbe
import app.mumcord.mobile.platform.call.SelfVoice
import app.mumcord.mobile.ui.connected.PERSON_ROW_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_VOICE_ACTIVITY_TEST_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A normal phone call during a session (AC-5.10), with the emulator's simulated phone network:
 * while the phone only rings nothing changes; once the call is accepted the user is muted and
 * deafened on the server and stays in the channel; when the call ends the previous state is
 * back and the second person hears the phone again. e2e/run.sh plays the network and checks
 * that the second person heard the phone in two separate stretches.
 * Expects fresh app data with local network access, the microphone and notifications granted.
 */
class PhoneCallFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }
    private val phone = PhoneSystem()

    @Before
    fun silenceTheMicrophone() = AudioProbe.replaceMicrophone(AudioProbe.Signal.Silence)

    @After
    fun endThePhoneCall() {
        AudioProbe.replaceMicrophone(null)
        phone.network("cancel")
    }

    @Test
    fun anAcceptedPhoneCallMutesAndDeafensAndItsEndRestoresThePreviousState() {
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
        AudioProbe.replaceMicrophone(AudioProbe.Signal.Speech)
        app.await("the second person's voice in what the speaker plays") {
            VoiceChecks.speakerLoudFor(VoiceChecks.STEADY_POLLS)
        }
        val before = SelfVoice(muted = false, deafened = false)
        assertEquals(before, phone.selfVoice())

        // Somebody calls. While the phone only rings, the session is not interrupted.
        phone.network("call")
        app.await("the phone to ring") { phone.phoneCallState() == PhoneSystem.PHONE_RINGING }
        assertTrue("Ringing must not interrupt playback", VoiceChecks.speakerLoudFor(RINGING_POLLS))
        assertEquals("Ringing must not mute", before, phone.selfVoice())

        // The call is accepted: muted and deafened on the server, still in the channel.
        phone.answerPhoneCall()
        app.await("the phone call to be accepted") { phone.phoneCallState() == PhoneSystem.PHONE_IN_CALL }
        app.await("the user to be muted and deafened during the phone call") {
            phone.selfVoice() == SelfVoice.Silenced
        }
        app.await("silence from the speaker during the phone call") {
            VoiceChecks.speakerQuietFor(VoiceChecks.STEADY_POLLS)
        }
        // Long enough for the second person to notice that the phone has gone silent.
        Thread.sleep(IN_CALL_MILLIS)
        assertEquals(SelfVoice.Silenced, phone.selfVoice())

        // The caller hangs up: the previous state is back without anybody touching the phone.
        phone.network("cancel")
        app.await("the previous state after the phone call") { phone.selfVoice() == before }
        app.await("the second person's voice after the phone call") {
            VoiceChecks.speakerLoudFor(VoiceChecks.STEADY_POLLS)
        }
        app.await("the microphone stream to run again") { VoiceChecks.microphoneFramesIn(500) > 0 }
        // Long enough for the second person to hear the phone as a new stretch of voice.
        Thread.sleep(HEARD_AGAIN_MILLIS)

        // The phone call's screen may still be in front; the app's own state is what counts.
        phone.shell("am start -n ${PhoneSystem.APP_PACKAGE}/.MainActivity")
        app.awaitOrFail("the channel list, still connected") { app.connected() }
        app.leave()
        Thread.sleep(SPAN_END_MILLIS)
        assertEquals("The session must not be in the recent calls", 0, phone.recentCallsOfTheSession())
    }

    private companion object {
        /** Three seconds of ringing. */
        const val RINGING_POLLS = 12
        const val IN_CALL_MILLIS = 5_000L
        const val HEARD_AGAIN_MILLIS = 3_000L
        const val SPAN_END_MILLIS = 1_500L
    }
}
