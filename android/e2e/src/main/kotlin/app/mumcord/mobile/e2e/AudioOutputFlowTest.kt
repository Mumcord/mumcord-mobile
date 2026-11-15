// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.e2e

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.TestSettings
import app.mumcord.mobile.platform.audio.AudioProbe
import app.mumcord.mobile.platform.call.OutputKind
import app.mumcord.mobile.platform.call.SelfVoice
import app.mumcord.mobile.ui.connected.OUTPUT_CHIP_TEST_TAG
import app.mumcord.mobile.ui.connected.OUTPUT_QUESTION_SPEAKER_TEST_TAG
import app.mumcord.mobile.ui.connected.PERSON_ROW_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_COLLAPSE_TEST_TAG
import app.mumcord.mobile.ui.connected.outputRowTestTag
import app.mumcord.mobile.ui.servers.SERVER_CONNECT_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_VOICE_ACTIVITY_TEST_TAG
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * The audio output and the headset's button in the real app (AC-5.11, AC-5.12).
 *
 * The emulator has a loudspeaker and nothing else: no earpiece, and a headset cannot be plugged
 * in or paired. So the output scenario covers what such a phone shows (the chip, the sheet
 * with the loudspeaker chosen and the wired headset as not connected, no first-connect
 * question), checked against where the phone system says the call's audio goes; the question
 * and switching are covered with played outputs by `OutputUiFlowTest` and need a real phone
 * beyond that. The button scenario presses the key a wired headset and a Bluetooth
 * headset send (`input keyevent`), with the app in the background and with the screen off,
 * while a debug-only setting makes the loudspeaker count as a headset; a second person on the
 * host (`e2e/voice-peer`) must hear the phone three separate times, the gaps being the mutes.
 *
 * Expects fresh app data with local network access and the microphone granted.
 */
class AudioOutputFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }
    private val phone = PhoneSystem()

    @After
    fun backToTheProductsBehaviour() {
        TestSettings.treatOutputAsHeadset = false
        AudioProbe.replaceMicrophone(null)
    }

    private fun awaitRoute(route: String, why: String) =
        app.await("the call's audio on $route $why") { phone.callRoute() == route }

    private fun openVoiceView() {
        compose.onNodeWithText(VOICE_CONNECTED, substring = true).performClick()
        app.await("the output chip in the voice view") { app.exists(OUTPUT_CHIP_TEST_TAG) }
    }

    @Test
    fun theVoiceViewShowsTheOutputAndTheSheetListsWhatThePhoneOffers() {
        app.chooseTalkMode()
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        awaitRoute(SPEAKER, "with the session")

        openVoiceView()
        compose.onNodeWithContentDescription("Audio output: Speaker").assertExists()
        // A phone without an earpiece has nothing to ask.
        assertFalse("The question was put on a phone without an earpiece", app.exists(OUTPUT_QUESTION_SPEAKER_TEST_TAG))

        compose.onNodeWithTag(OUTPUT_CHIP_TEST_TAG).performClick()
        app.await("the output sheet") { app.exists(outputRowTestTag(OutputKind.Speaker)) }
        compose.onNodeWithTag(outputRowTestTag(OutputKind.Speaker)).assertIsSelected()
        compose.onNodeWithTag(outputRowTestTag(OutputKind.WiredHeadset)).assertIsNotEnabled()
        assertFalse("The emulator has no earpiece", app.exists(outputRowTestTag(OutputKind.Earpiece)))
        assertFalse("The emulator has no Bluetooth headset", app.exists(outputRowTestTag(OutputKind.Bluetooth)))

        // Picking what is already used closes the sheet and changes nothing.
        compose.onNodeWithTag(outputRowTestTag(OutputKind.Speaker)).performClick()
        app.await("the output sheet to close") { !app.exists(outputRowTestTag(OutputKind.Speaker)) }
        awaitRoute(SPEAKER, "after picking the speaker")
        compose.onNodeWithContentDescription("Audio output: Speaker").assertExists()

        compose.onNodeWithTag(VOICE_COLLAPSE_TEST_TAG).performClick()
        app.leave()
        app.await("the call to end with the session") { phone.callRoute() == null }
    }

    private fun awaitVoice(what: String, expected: SelfVoice) =
        app.await("the user to be $what on the server") { phone.selfVoice() == expected }

    /** One press of a headset's button, once the phone system has the session's call (again). */
    private fun press(key: String, expected: SelfVoice, what: String) {
        app.await("the session's call in the phone system") { phone.callRoute() != null }
        phone.shell("input keyevent $key")
        awaitVoice(what, expected)
    }

    @Test
    fun aHeadsetsButtonTogglesTheMicrophoneInTheBackgroundAndWithTheScreenOff() {
        AudioProbe.replaceMicrophone(AudioProbe.Signal.Speech)
        val muted = SelfVoice(muted = true, deafened = false)
        val unmuted = SelfVoice(muted = false, deafened = false)

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
        app.await("the second person's voice from the speaker after hearing the phone") {
            VoiceChecks.speakerLoudFor(VoiceChecks.STEADY_POLLS)
        }

        // On the loudspeaker the system's "end call" would leave the server; the setting makes
        // it count as the headset the emulator cannot have.
        TestSettings.treatOutputAsHeadset = true
        phone.device.pressHome()

        // A wired headset's button, with the app in the background.
        press(WIRED_BUTTON, muted, "muted")
        Thread.sleep(GAP_MILLIS)
        press(WIRED_BUTTON, unmuted, "unmuted")
        Thread.sleep(HEARD_AGAIN_MILLIS)

        // A Bluetooth headset's button, with the screen off.
        phone.lock()
        press(BLUETOOTH_BUTTON, muted, "muted while the screen is off")
        Thread.sleep(GAP_MILLIS)
        press(BLUETOOTH_BUTTON, unmuted, "unmuted while the screen is off")
        Thread.sleep(HEARD_AGAIN_MILLIS)
        phone.unlock()

        // The session never ended, and the phone system has its call again.
        app.await("the session's call in the phone system") { phone.callRoute() != null }
        assertNotNull("The session's service still runs", phone.serviceTypes())
        phone.shell("am start -n ${PhoneSystem.APP_PACKAGE}/.MainActivity")
        app.awaitOrFail("the channel list, still connected") { app.connected() }

        // Without the setting the same key ends the session, as it does on a real loudspeaker.
        TestSettings.treatOutputAsHeadset = false
        phone.shell("input keyevent $WIRED_BUTTON")
        app.awaitOrFail("the server list after the system ended the call") { app.exists(SERVER_CONNECT_TEST_TAG) }
        // Long enough for the second person to notice that the phone has gone.
        Thread.sleep(HEARD_AGAIN_MILLIS)
    }

    private companion object {
        const val VOICE_CONNECTED = "Voice connected"
        const val SPEAKER = "SPEAKER"
        const val WIRED_BUTTON = "KEYCODE_HEADSETHOOK"
        const val BLUETOOTH_BUTTON = "KEYCODE_MEDIA_PLAY_PAUSE"

        /** Well over the silence after which the second person reports a stretch as over (0.6 s). */
        const val GAP_MILLIS = 2_500L
        const val HEARD_AGAIN_MILLIS = 3_000L
    }
}
