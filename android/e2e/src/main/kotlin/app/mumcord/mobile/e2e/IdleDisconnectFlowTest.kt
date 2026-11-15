// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.e2e

import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.TestSettings
import app.mumcord.mobile.session.IdleTimes
import app.mumcord.mobile.ui.connect.SESSION_ENDED_RECONNECT_TEST_TAG
import app.mumcord.mobile.ui.connected.MORE_SETTINGS_TEST_TAG
import app.mumcord.mobile.ui.connected.SETTINGS_IDLE_TEST_TAG
import app.mumcord.mobile.ui.connected.SETTINGS_SCREEN_TEST_TAG
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * "Disconnect when I'm alone in a channel" in the real app (FR-5.42, AC-5.30), with the times
 * shortened by the debug build's test setting: 20 seconds alone, 15 seconds of warning.
 *
 * The phone is put to sleep and into the system's deepest idle mode (Doze) while the app
 * waits, each time: the warning and the disconnect must come all the same, because the
 * session's foreground service keeps the app running.
 *
 * Expects fresh app data with local network access, the microphone and notifications granted.
 */
class IdleDisconnectFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }
    private val phone = PhoneSystem()
    private val notifications
        get() = phone.instrumentation.targetContext.getSystemService(NotificationManager::class.java)

    @Before
    fun shortenTheTimes() {
        TestSettings.idleTimes = IdleTimes(aloneMillis = ALONE_MILLIS, warningMillis = WARNING_MILLIS)
    }

    @After
    fun backToTheProductsBehaviour() {
        TestSettings.idleTimes = IdleTimes()
        wakeUp()
    }

    /** Whether the app shows a notification with this title. */
    private fun notified(title: String): Boolean = notifications.activeNotifications.any {
        it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title
    }

    /** Screen off, unplugged, and forced into the deepest idle mode. */
    private fun sleepDeeply() {
        phone.lock()
        phone.shell("dumpsys battery unplug")
        val mode = phone.shell("dumpsys deviceidle force-idle")
        assertTrue("The phone did not enter its idle mode: $mode", mode.contains("Now forced in to deep idle mode"))
    }

    private fun wakeUp() {
        phone.shell("dumpsys deviceidle unforce")
        phone.shell("dumpsys battery reset")
        phone.unlock()
    }

    @Test
    fun aloneTheAppWarnsStayConnectedKeepsTheSessionAndWithoutATapItDisconnectsAndSaysSo() {
        app.chooseTalkMode()
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        app.joinLobby()

        compose.onNodeWithText(TAB_MORE).performClick()
        compose.onNodeWithTag(MORE_SETTINGS_TEST_TAG).performClick()
        app.await("the settings") { app.exists(SETTINGS_SCREEN_TEST_TAG) }
        compose.onNodeWithTag(SETTINGS_IDLE_TEST_TAG).performScrollTo().performClick()
        val enabledAt = System.nanoTime()

        // Alone, asleep: the warning comes after the shortened time.
        sleepDeeply()
        app.await("the warning while the phone sleeps") { notified(WARNING) }
        val warnedAfter = (System.nanoTime() - enabledAt) / 1_000_000
        assertTrue("The warning came before its time ($warnedAfter ms)", warnedAfter >= ALONE_MILLIS)
        wakeUp()

        // "Stay connected" in the notification keeps the session.
        phone.device.pressHome()
        phone.openShade()
        assertNotNull("The warning is not in the shade", phone.inShade(WARNING))
        phone.shell("screencap -p $SHADE_PICTURE")
        val stayedAt = System.nanoTime()
        checkNotNull(phone.inShade(STAY_CONNECTED)) { "the warning has no \"$STAY_CONNECTED\" button" }.click()
        app.await("the warning to go away after \"$STAY_CONNECTED\"") { !notified(WARNING) }
        phone.closeShade()

        // Asleep again and still alone: past the moment the first warning would have ended,
        // the session is still there, and after the full time alone the warning returns.
        sleepDeeply()
        Thread.sleep(WARNING_MILLIS + MARGIN_MILLIS)
        assertNotNull("The session ended although the user asked to stay", phone.selfVoice())
        app.await("the second warning") { notified(WARNING) }
        val warnedAgainAfter = (System.nanoTime() - stayedAt) / 1_000_000
        assertTrue("The second warning came before its time ($warnedAgainAfter ms)", warnedAgainAfter >= ALONE_MILLIS)

        // Without a tap the app disconnects and says so, still asleep.
        val warnedAt = System.nanoTime()
        app.await("the app to disconnect by itself") { phone.selfVoice() == null }
        val disconnectedAfter = (System.nanoTime() - warnedAt) / 1_000_000
        app.await("the notification that says so") { notified(DISCONNECTED) }
        assertTrue("The warning stays although the session is over", !notified(WARNING))
        app.await("the session's service to stop") { phone.serviceTypes() == null }
        VoiceChecks.report(
            "idle: warned_ms=$warnedAfter warned_again_ms=$warnedAgainAfter disconnected_ms=$disconnectedAfter " +
                "(alone ${ALONE_MILLIS} ms, warning ${WARNING_MILLIS} ms)",
        )
        wakeUp()

        // The app says it too, on the server list, and offers to reconnect.
        phone.shell("am start -n ${PhoneSystem.APP_PACKAGE}/.MainActivity")
        app.await("the notice in the app") { app.exists(SESSION_ENDED_RECONNECT_TEST_TAG) }
        compose.onNodeWithTag(SESSION_ENDED_RECONNECT_TEST_TAG).performClick()
        app.awaitChannels("the channel list after reconnecting")
        assertNull("The notification stays although the notice was answered", DISCONNECTED.takeIf(::notified))
        app.leave()
    }

    private companion object {
        const val TAB_MORE = "More"
        const val WARNING = "Disconnecting in 1 minute"
        const val STAY_CONNECTED = "Stay connected"
        const val DISCONNECTED = "Disconnected"
        const val ALONE_MILLIS = 20_000L
        const val WARNING_MILLIS = 15_000L
        const val MARGIN_MILLIS = 2_000L

        /** Where the picture of the shade with the warning is left for e2e/run.sh. */
        const val SHADE_PICTURE = "/data/local/tmp/mumcord-idle-notification.png"
    }
}
