// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.e2e

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.MumcordApplication
import app.mumcord.mobile.platform.audio.AudioProbe
import app.mumcord.mobile.platform.call.SelfVoice
import app.mumcord.mobile.session.ConnectionPhase
import app.mumcord.mobile.session.RECONNECT_NOW_FROM_ATTEMPT
import app.mumcord.mobile.ui.connected.MORE_SETTINGS_TEST_TAG
import app.mumcord.mobile.ui.connected.PERSON_ROW_TEST_TAG
import app.mumcord.mobile.ui.connected.SETTINGS_REDUCED_VOICE_TEST_TAG
import app.mumcord.mobile.ui.connected.SETTINGS_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_VOICE_ACTIVITY_TEST_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Network changes in the real app (FR-5.40, FR-5.41; AC-5.13). The emulator has mobile data
 * and a simulated Wi-Fi ("AndroidWifi") that it does not join by itself; the tests join it
 * and switch the networks with the phone's own commands (`svc wifi`, `svc data`). The test
 * server on the host is reachable over both.
 *
 * Expects fresh app data with local network access and the microphone granted. Whatever
 * happens, the emulator is left as it was found: on mobile data, no Wi-Fi network saved.
 */
class NetworkFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }
    private val phone = PhoneSystem()
    private val repository
        get() = (phone.instrumentation.targetContext.applicationContext as MumcordApplication).sessionRepository

    @Before
    fun joinTheWifi() {
        phone.shell("svc data enable")
        phone.shell("svc wifi enable")
        phone.shell("cmd wifi connect-network $WIFI_NAME open")
        app.await("the phone to use the Wi-Fi") { onTransport(NetworkCapabilities.TRANSPORT_WIFI) }
    }

    @After
    fun leaveThePhoneAsItWas() {
        AudioProbe.replaceMicrophone(null)
        phone.shell("svc data enable")
        phone.shell("svc wifi enable")
        SAVED_NETWORK.findAll(phone.shell("cmd wifi list-networks")).forEach {
            phone.shell("cmd wifi forget-network ${it.groupValues[1]}")
        }
    }

    /** Whether the phone's default network is of this kind (Wi-Fi or mobile data). */
    private fun onTransport(transport: Int): Boolean {
        val connectivity = phone.instrumentation.targetContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(transport) == true
    }

    private fun phase() = repository.state.value.phase

    private fun ownChannel(): String? = repository.state.value.server?.let { it.channels[it.selfChannelId]?.name }

    private fun bitrateLimit(): UInt? = runBlocking { repository.voiceSettings() }?.bitrateLimitKbps

    private fun connectAndJoinTheLobby(talkMode: String) {
        app.chooseTalkMode(talkMode)
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        app.joinLobby()
        app.await("the user in the Lobby") { ownChannel() == AppDriver.LOBBY }
    }

    @Test
    fun switchingFromWifiToMobileDataReconnectsIntoTheSameChannelWithTheSameMuteState() {
        AudioProbe.replaceMicrophone(AudioProbe.Signal.Speech)
        val muted = SelfVoice(muted = true, deafened = false)
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
        assertEquals("On Wi-Fi nothing is limited", null, bitrateLimit())

        compose.onNodeWithContentDescription(MUTE).performClick()
        app.await("the user to be muted on the server") { phone.selfVoice() == muted }

        // The Wi-Fi goes away; the phone falls back to mobile data.
        val switchedAt = System.nanoTime()
        phone.shell("svc wifi disable")
        app.await("the connection to be noticed as lost") { phase() is ConnectionPhase.Reconnecting }
        val lostAfter = (System.nanoTime() - switchedAt) / 1_000_000
        app.await("the app to be connected again by itself") { phase() == ConnectionPhase.Connected }
        val backAfter = (System.nanoTime() - switchedAt) / 1_000_000
        VoiceChecks.report("network: lost_ms=$lostAfter reconnected_ms=$backAfter")

        assertTrue("The phone is on mobile data now", onTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        app.await("the user in the same channel again") { ownChannel() == AppDriver.LOBBY }
        app.await("the user to be muted as before") { phone.selfVoice() == muted }
        app.awaitOrFail("the channel list, still shown") { app.connected() }

        // Mobile data is metered: with the switch on, the voice bitrate is limited to 24 kbit/s.
        compose.onNodeWithText(TAB_MORE).performClick()
        compose.onNodeWithTag(MORE_SETTINGS_TEST_TAG).performClick()
        app.await("the settings") { app.exists(SETTINGS_SCREEN_TEST_TAG) }
        compose.onNodeWithTag(SETTINGS_REDUCED_VOICE_TEST_TAG).performScrollTo().performClick()
        app.await("the bitrate limit on mobile data") { bitrateLimit() == REDUCED_KBPS }
        VoiceChecks.report("network: metered_limit_kbps=${bitrateLimit()}")

        // Unmuted, the second person hears the phone again, over mobile data and at 24 kbit/s.
        compose.onNodeWithContentDescription(UNMUTE).performClick()
        app.await("the microphone stream to run again after unmuting") { VoiceChecks.microphoneFramesIn(500) > 0 }
        Thread.sleep(HEARD_AGAIN_MILLIS)

        // Back on Wi-Fi the limit is lifted, with the switch still on.
        phone.shell("svc wifi enable")
        phone.shell("cmd wifi connect-network $WIFI_NAME open")
        app.await("the phone to use the Wi-Fi again") { onTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        app.await("the limit to be lifted on Wi-Fi") { bitrateLimit() == null }
        VoiceChecks.report("network: unmetered_limit_kbps=${bitrateLimit() ?: 0u}")

        app.leave()
        // Long enough for the second person to notice that the phone has gone.
        Thread.sleep(HEARD_AGAIN_MILLIS)
    }

    @Test
    fun afterALongerGapTheSessionIsBackAsSoonAsANetworkIs() {
        connectAndJoinTheLobby(TALK_MODE_VOICE_ACTIVITY_TEST_TAG)

        // No network at all, until the engine waits long between its own attempts.
        phone.shell("svc wifi disable")
        phone.shell("svc data disable")
        app.await("the engine to wait between its attempts") {
            (phase() as? ConnectionPhase.Reconnecting)?.let { it.attempt >= RECONNECT_NOW_FROM_ATTEMPT } == true
        }
        // Into the engine's wait, which is four seconds or more from here on.
        Thread.sleep(INTO_THE_WAIT_MILLIS)
        val attempt = (phase() as? ConnectionPhase.Reconnecting)?.attempt

        val backAt = System.nanoTime()
        phone.shell("svc data enable")
        app.await("mobile data to be back") { onTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
        val networkAfter = (System.nanoTime() - backAt) / 1_000_000
        app.await("the app to be connected again by itself") { phase() == ConnectionPhase.Connected }
        val backAfter = (System.nanoTime() - backAt) / 1_000_000
        VoiceChecks.report("network: gap_attempt=$attempt network_back_ms=$networkAfter reconnected_ms=$backAfter")

        app.await("the user in the same channel again") { ownChannel() == AppDriver.LOBBY }
        app.awaitOrFail("the channel list, still shown") { app.connected() }
        app.leave()
    }

    private companion object {
        const val WIFI_NAME = "AndroidWifi"
        const val TAB_MORE = "More"
        const val MUTE = "Mute microphone"
        const val UNMUTE = "Unmute microphone"
        const val REDUCED_KBPS: UInt = 24u
        const val HEARD_AGAIN_MILLIS = 3_000L
        const val INTO_THE_WAIT_MILLIS = 1_000L
        val SAVED_NETWORK = Regex("""(?m)^(\d+)\s+AndroidWifi\b""")
    }
}
