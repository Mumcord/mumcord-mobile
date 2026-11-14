// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.content.pm.ServiceInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.platform.audio.AudioProbe
import app.mumcord.mobile.platform.call.SelfVoice
import app.mumcord.mobile.ui.connected.VOICE_VIEW_TEST_TAG
import app.mumcord.mobile.ui.servers.SERVER_CONNECT_TEST_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * While connected the system shows the session as a call (AC-5.9): a call notification with
 * Mute, Deafen and the system's hang-up button, each of which works with the app in the
 * background; tapping it opens the voice view; and the recent calls stay empty.
 * Expects fresh app data with local network access, the microphone and notifications granted.
 */
class CallNotificationFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }
    private val phone = PhoneSystem()

    @Before
    fun silenceTheMicrophone() = AudioProbe.replaceMicrophone(AudioProbe.Signal.Silence)

    @After
    fun giveTheMicrophoneBack() = AudioProbe.replaceMicrophone(null)

    private fun tapInShade(label: String) {
        checkNotNull(phone.inShade(label)) { "the notification has no button \"$label\"" }.click()
    }

    private fun awaitVoice(what: String, expected: SelfVoice) =
        app.await("the user to be $what on the server") { phone.selfVoice() == expected }

    @Test
    fun theSessionIsACallWithMuteDeafenAndLeaveAndLeavesNoRecentCall() {
        app.chooseTalkMode()
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        app.joinLobby()

        app.await("the session's foreground service") { phone.serviceTypes() != null }
        assertEquals(
            "The service keeps a call and the microphone alive",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            phone.serviceTypes(),
        )

        // From another app's point of view: the app is in the background.
        phone.device.pressHome()
        phone.openShade()
        checkNotNull(phone.callNotification()) { "the session's call notification is not in the shade" }

        tapInShade(MUTE)
        awaitVoice("muted", SelfVoice(muted = true, deafened = false))
        tapInShade(UNMUTE)
        awaitVoice("unmuted", SelfVoice(muted = false, deafened = false))
        tapInShade(DEAFEN)
        awaitVoice("deafened", SelfVoice.Silenced)
        tapInShade(UNDEAFEN)
        app.await("the deafen to end") { phone.selfVoice()?.deafened == false }

        // Tapping the notification opens the app on the voice view.
        checkNotNull(phone.callNotification()).click()
        app.awaitOrFail("the voice view after tapping the notification") { app.exists(VOICE_VIEW_TEST_TAG) }

        // The system's own button of an ongoing call leaves the server.
        phone.device.pressHome()
        phone.openShade()
        tapInShade(HANG_UP)
        app.await("the session to end after hanging up") { phone.selfVoice() == null }
        app.await("the service to stop with the session") { phone.serviceTypes() == null }
        assertNull("The notification goes with the session", phone.inShade(MUTE, GONE_TIMEOUT_MILLIS))
        phone.closeShade()

        assertEquals("The session must not be in the recent calls", 0, phone.recentCallsOfTheSession())

        // Back in the app, the server list is shown.
        phone.shell("am start -n ${PhoneSystem.APP_PACKAGE}/.MainActivity")
        app.awaitOrFail("the server list after leaving") { app.exists(SERVER_CONNECT_TEST_TAG) }
    }

    private companion object {
        const val MUTE = "Mute"
        const val UNMUTE = "Unmute"
        const val DEAFEN = "Deafen"
        const val UNDEAFEN = "Undeafen"

        /** The system's label of an ongoing call's own button. */
        const val HANG_UP = "Hang up"
        const val GONE_TIMEOUT_MILLIS = 2_000L
    }
}
