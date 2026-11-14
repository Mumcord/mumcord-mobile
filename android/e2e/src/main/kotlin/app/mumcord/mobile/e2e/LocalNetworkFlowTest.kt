// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.ui.connect.ACCESS_LOCAL_NETWORK
import app.mumcord.mobile.ui.connect.LOCAL_NETWORK_CONTINUE_TEST_TAG
import app.mumcord.mobile.ui.servers.CONNECT_RETRY_TEST_TAG
import app.mumcord.mobile.ui.servers.LOCAL_NETWORK_SETTINGS_TEST_TAG
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The local network permission (AC-5.37) in the real app with the system's own dialog: the test
 * server on the emulator's host is on the emulator's local network. Each test expects fresh app
 * data WITHOUT the permission (the scenarios "ask" of e2e/run.sh), on Android 17 or later.
 */
class LocalNetworkFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val system = SystemDialog(device)

    @Before
    fun requireTheLocalNetworkPermission() {
        assumeTrue("Android before 17 has no local network permission", Build.VERSION.SDK_INT >= ANDROID_17)
    }

    @Test
    fun aLocalServerIsExplainedThenTheSystemAsksAndAllowingItConnects() {
        app.chooseTalkMode()
        app.addServer()

        app.tapConnect()
        app.awaitOrFail("the explanation of local network access") { app.exists(LOCAL_NETWORK_CONTINUE_TEST_TAG) }
        compose.onNodeWithTag(LOCAL_NETWORK_CONTINUE_TEST_TAG).performClick()
        system.answer(SystemDialog.ALLOW, PERMISSION)

        // No second tap on Connect: the connection goes on by itself.
        app.trustCertificate()
        app.awaitChannels()
        app.disconnect()
    }

    @Test
    fun refusingItIsNamedInTheErrorWhichLeadsToTheSystemSettingsAndBackToAConnection() {
        app.chooseTalkMode()
        app.addServer()

        app.tapConnect()
        app.awaitOrFail("the explanation of local network access") { app.exists(LOCAL_NETWORK_CONTINUE_TEST_TAG) }
        compose.onNodeWithTag(LOCAL_NETWORK_CONTINUE_TEST_TAG).performClick()
        system.answer(SystemDialog.DENY, PERMISSION)
        awaitRefusalError()

        // The way to the system settings: the app's own page there, and back.
        compose.onNodeWithTag(LOCAL_NETWORK_SETTINGS_TEST_TAG).performClick()
        assertTrue("The system settings did not open", system.settingsOpened())
        device.pressBack()
        awaitRefusalError()

        // "Try again" explains and asks once more; a second refusal is final for the system.
        compose.onNodeWithTag(CONNECT_RETRY_TEST_TAG).performClick()
        app.await("the explanation after Try again") { app.exists(LOCAL_NETWORK_CONTINUE_TEST_TAG) }
        compose.onNodeWithTag(LOCAL_NETWORK_CONTINUE_TEST_TAG).performClick()
        system.answer(SystemDialog.DENY, PERMISSION)
        awaitRefusalError()

        // From now on the system does not ask, so the app does not explain: the error stays.
        compose.onNodeWithTag(CONNECT_RETRY_TEST_TAG).performClick()
        compose.waitForIdle()
        assertFalse("Explained again although the system no longer asks", app.exists(LOCAL_NETWORK_CONTINUE_TEST_TAG))
        awaitRefusalError()

        // Allowed in the system settings (done here the way the settings page does it).
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            ACCESS_LOCAL_NETWORK,
        )
        compose.onNodeWithTag(CONNECT_RETRY_TEST_TAG).performClick()
        app.trustCertificate()
        app.awaitChannels()
        app.disconnect()
    }

    private fun awaitRefusalError() =
        app.await("the error that names the missing permission") {
            app.connectError()?.contains(REFUSAL_TEXT) == true && app.exists(LOCAL_NETWORK_SETTINGS_TEST_TAG)
        }

    private companion object {
        const val ANDROID_17 = 37
        const val PERMISSION = "local network access"
        const val REFUSAL_TEXT = "may not access your local network"
    }
}
