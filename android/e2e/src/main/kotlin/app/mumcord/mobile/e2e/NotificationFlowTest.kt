// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.ui.connected.NOTIFICATIONS_CONTINUE_TEST_TAG
import app.mumcord.mobile.ui.connected.NOTIFICATIONS_NOT_NOW_TEST_TAG
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The notification question in the real app, with the system's own dialog: it is explained
 * when the first session runs, and a "no" leaves the session running in the background.
 * Expects fresh app data with local network access and the microphone granted, and
 * notifications not yet asked for.
 */
class NotificationFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }
    private val phone = PhoneSystem()
    private val system = SystemDialog(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))

    private fun connect() {
        app.chooseTalkMode()
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitOrFail("the explanation for notifications") { app.exists(NOTIFICATIONS_CONTINUE_TEST_TAG) }
    }

    @Test
    fun itIsExplainedWithTheFirstSessionThenTheSystemAsksAndAllowingItShowsTheCall() {
        connect()
        compose.onNodeWithTag(NOTIFICATIONS_CONTINUE_TEST_TAG).performClick()
        system.answer(SystemDialog.ALLOW, "notifications")
        app.awaitChannels("the channel list after allowing notifications")
        assertFalse("The question is not put twice", app.exists(NOTIFICATIONS_CONTINUE_TEST_TAG))
        app.joinLobby()

        phone.openShade()
        assertTrue("The session's call notification is in the shade", phone.callNotification() != null)
        phone.closeShade()
        app.leave()
    }

    @Test
    fun notNowKeepsTheSessionRunningInTheBackground() {
        connect()
        compose.onNodeWithTag(NOTIFICATIONS_NOT_NOW_TEST_TAG).performClick()
        app.awaitChannels("the channel list after declining notifications")
        app.joinLobby()
        app.await("the session's foreground service without notifications") { phone.serviceTypes() != null }

        phone.device.pressHome()
        Thread.sleep(BACKGROUND_MILLIS)
        assertTrue("The session survives the background", phone.selfVoice() != null)
        assertTrue("The service survives the background", phone.serviceTypes() != null)
        // What the system shows of the session without the permission; for the device checklist.
        phone.openShade()
        VoiceChecks.report("notifications denied: call notification shown=${phone.callNotification() != null}")
        phone.closeShade()

        phone.shell("am start -n ${PhoneSystem.APP_PACKAGE}/.MainActivity")
        app.awaitOrFail("the channel list, still connected") { app.connected() }
        assertFalse("The question is not put again", app.exists(NOTIFICATIONS_CONTINUE_TEST_TAG))
        app.leave()
    }

    private companion object {
        const val BACKGROUND_MILLIS = 10_000L
    }
}
