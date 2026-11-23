// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.util.Log
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
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
        ringWithoutInterruption(before)

        // The call is accepted: muted and deafened on the server, still in the channel.
        if (dropFirstAnswer()) {
            // Debugging switch: the network hangs up just before the answer, so the answer
            // finds nothing to accept and the phone falls back to idle, as after a lost answer.
            phone.network("cancel")
            app.await("the phone to stop ringing (forced loss)") { phone.phoneCallState() == PhoneSystem.PHONE_IDLE }
        }
        phone.answerPhoneCall()
        var lostAnswers = 0
        if (!acceptedOrLost()) {
            // The emulator now and then loses a call at the moment it is answered: its modem
            // reports no calls, the phone books the call as missed (also when no app is
            // running), and the modem lets nobody else ring until that call is hung up. The
            // session was on hold for a moment; once it is back, somebody calls again, once.
            lostAnswers = 1
            Log.w(LOG_TAG, "the emulator lost the answered call, calling again")
            phone.network("cancel")
            Thread.sleep(HANG_UP_MILLIS)
            app.await("the previous state after the emulator lost the phone call") { phone.selfVoice() == before }
            app.await("the second person's voice after the emulator lost the phone call") {
                VoiceChecks.speakerLoudFor(VoiceChecks.STEADY_POLLS)
            }
            app.await("the microphone stream to run again after the emulator lost the phone call") {
                VoiceChecks.microphoneFramesIn(500) > 0
            }
            ringWithoutInterruption(before)
            phone.answerPhoneCall()
            if (!acceptedOrLost()) {
                throw AssertionError(
                    "The emulator lost the answered phone call twice in a row: its simulated modem " +
                        "reported no calls after the answer. This is the emulator, not the app " +
                        "(phone state ${phone.phoneCallState()}, voice ${phone.selfVoice()}).",
                )
            }
        }
        // e2e/run.sh allows the second person one more stretch of voice for a lost call.
        VoiceChecks.report("phone: lost_answers=$lostAnswers")
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

    /** Somebody calls, and for three seconds of ringing nothing about the session changes. */
    private fun ringWithoutInterruption(before: SelfVoice) {
        phone.network("call")
        app.await("the phone to ring") { phone.phoneCallState() == PhoneSystem.PHONE_RINGING }
        assertTrue("Ringing must not interrupt playback", VoiceChecks.speakerLoudFor(RINGING_POLLS))
        assertEquals("Ringing must not mute", before, phone.selfVoice())
    }

    /** True when e2e/run.sh was asked (E2E_DROP_FIRST_ANSWER=1) to lose the first answer on purpose. */
    private fun dropFirstAnswer(): Boolean =
        InstrumentationRegistry.getArguments().getString("dropFirstAnswer") == "true"

    /** Waits for the answered call: true once the phone is in it, false when it is gone instead. */
    private fun acceptedOrLost(): Boolean {
        var state: Int? = null
        app.await("the phone call to be accepted") {
            state = phone.phoneCallState()
            state == PhoneSystem.PHONE_IN_CALL || state == PhoneSystem.PHONE_IDLE
        }
        return state == PhoneSystem.PHONE_IN_CALL
    }

    private companion object {
        const val LOG_TAG = "MumcordE2E"

        /** Three seconds of ringing. */
        const val RINGING_POLLS = 12
        /** For e2e/run.sh to hang up a call the emulator lost, before the next one. */
        const val HANG_UP_MILLIS = 2_000L
        const val IN_CALL_MILLIS = 5_000L
        const val HEARD_AGAIN_MILLIS = 3_000L
        /** Well over the silence after which the second person reports a stretch as over (0.6 s). */
        const val SPAN_END_MILLIS = 3_000L
    }
}
