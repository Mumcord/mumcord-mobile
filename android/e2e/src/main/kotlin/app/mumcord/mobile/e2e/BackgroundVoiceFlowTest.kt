// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.platform.audio.AudioDirection
import app.mumcord.mobile.platform.audio.AudioProbe
import app.mumcord.mobile.ui.connected.PERSON_ROW_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_HOLD_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_LEAVE_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_VIEW_TEST_TAG
import app.mumcord.mobile.ui.servers.SERVER_CONNECT_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_PUSH_TO_TALK_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_VOICE_ACTIVITY_TEST_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A voice session goes on with the app in the background and with the screen locked (AC-5.8,
 * shortened; the time locked is the runner argument `lockSeconds`). The second person on the
 * host keeps talking once it has heard the phone; e2e/run.sh checks from its report that it
 * heard the phone for the whole time locked (voice activity) or not at all (push-to-talk).
 * Expects fresh app data with local network access, the microphone and notifications granted.
 */
class BackgroundVoiceFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }
    private val phone = PhoneSystem()
    private val lockMillis =
        (InstrumentationRegistry.getArguments().getString("lockSeconds")?.toLong() ?: DEFAULT_LOCK_SECONDS) * 1_000

    @Before
    fun silenceTheMicrophone() = AudioProbe.replaceMicrophone(AudioProbe.Signal.Silence)

    @After
    fun giveThePhoneBack() {
        AudioProbe.replaceMicrophone(null)
        phone.unlock()
    }

    private fun connectAndJoinLobby(talkMode: String) {
        app.chooseTalkMode(talkMode)
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        app.awaitOrFail("the second person ${VoiceChecks.PEER_NAME} in the channel list") {
            compose.onAllNodes(hasTestTag(PERSON_ROW_TEST_TAG) and hasText(VoiceChecks.PEER_NAME))
                .fetchSemanticsNodes().isNotEmpty()
        }
        app.joinLobby()
        app.await("the session's foreground service") { phone.serviceTypes() != null }
    }

    /**
     * Checks every few seconds for [millis] that the speaker plays the second person without a
     * gap, and returns how many frames the microphone stream delivered meanwhile.
     */
    private fun listenFor(millis: Long, where: String): Long {
        val framesBefore = AudioProbe.frames(AudioDirection.Capture)
        val end = System.nanoTime() + millis * 1_000_000
        var checks = 0
        while (System.nanoTime() < end) {
            assertTrue(
                "The second person must be heard $where (check ${checks + 1})",
                VoiceChecks.speakerLoudFor(VoiceChecks.STEADY_POLLS),
            )
            checks += 1
        }
        return AudioProbe.frames(AudioDirection.Capture) - framesBefore
    }

    @Test
    fun withVoiceActivityBothDirectionsKeepWorkingInTheBackgroundAndWhileLocked() {
        connectAndJoinLobby(TALK_MODE_VOICE_ACTIVITY_TEST_TAG)
        AudioProbe.replaceMicrophone(AudioProbe.Signal.Speech)
        app.await("the second person's voice in what the speaker plays") {
            VoiceChecks.speakerLoudFor(VoiceChecks.STEADY_POLLS)
        }

        phone.device.pressHome()
        val backgroundFrames = listenFor(BACKGROUND_MILLIS, "with the app in the background")
        assertTrue("The microphone must keep running in the background", backgroundFrames > 0)

        phone.lock()
        val lockedFrames = listenFor(lockMillis, "with the screen locked")
        // 48 kHz; at least nine tenths of the time must have been recorded.
        assertTrue(
            "The microphone delivered only $lockedFrames frames in ${lockMillis / 1000} s locked",
            lockedFrames > lockMillis * 48 * 9 / 10,
        )
        VoiceChecks.report("lock: locked_ms=${lockMillis + BACKGROUND_MILLIS} microphone_frames=$lockedFrames")
        assertEquals("Still in the Lobby, neither muted nor deafened", false, phone.selfVoice()?.muted)

        phone.unlock()
        // The home button was pressed before locking: the notification brings the app back.
        phone.openShade()
        checkNotNull(phone.callNotification()) { "the session's notification is missing after unlocking" }.click()
        app.awaitOrFail("the voice view after tapping the notification") { app.exists(VOICE_VIEW_TEST_TAG) }
        compose.onNodeWithTag(VOICE_LEAVE_TEST_TAG).performClick()
        app.awaitOrFail("the server list after leaving") { app.exists(SERVER_CONNECT_TEST_TAG) }
        // Long enough for the second person to notice that the phone has gone.
        Thread.sleep(SPAN_END_MILLIS)
    }

    @Test
    fun withPushToTalkThePhoneStaysSilentWhileLockedAndStillPlaysTheOthers() {
        connectAndJoinLobby(TALK_MODE_PUSH_TO_TALK_TEST_TAG)
        compose.onNodeWithTag(VOICE_BAR_TEST_TAG).performTouchInput { swipeUp() }
        app.await("the hold-to-talk button of the voice view") { app.exists(VOICE_HOLD_TEST_TAG) }
        AudioProbe.replaceMicrophone(AudioProbe.Signal.Speech)

        // One press, so that the second person hears the phone once and talks from then on.
        val held = VoiceChecks.hold(compose, VOICE_HOLD_TEST_TAG, HOLD_MILLIS)
        VoiceChecks.report("ptt: held_ms=$held")
        app.await("the second person's answer in what the speaker plays") {
            VoiceChecks.speakerLoudFor(VoiceChecks.STEADY_POLLS)
        }

        // The voice stays at the microphone the whole time; only the button could send it.
        phone.lock()
        listenFor(lockMillis, "with the screen locked in push-to-talk mode")
        VoiceChecks.report("lock: locked_ms=$lockMillis")

        phone.unlock()
        app.awaitOrFail("the app on screen again") { app.exists(VOICE_HOLD_TEST_TAG) }
        Thread.sleep(SPAN_END_MILLIS)
        compose.onNodeWithTag(VOICE_LEAVE_TEST_TAG).performClick()
        app.awaitOrFail("the server list after leaving") { app.exists(SERVER_CONNECT_TEST_TAG) }
    }

    private companion object {
        const val DEFAULT_LOCK_SECONDS = 180L
        const val BACKGROUND_MILLIS = 20_000L
        const val HOLD_MILLIS = 4_000L
        /** Well over the silence after which the second person reports a stretch as over (0.6 s). */
        const val SPAN_END_MILLIS = 3_000L
    }
}
