// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.ui.connected.MICROPHONE_BANNER_TEST_TAG
import app.mumcord.mobile.ui.connected.MICROPHONE_CONTINUE_TEST_TAG
import app.mumcord.mobile.ui.connected.MICROPHONE_NOT_NOW_TEST_TAG
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The microphone permission (FR-5.1) in the real app with the system's own dialog: asked when
 * talking first becomes possible, and listening works without it. Each test expects fresh app
 * data with local network access but WITHOUT the microphone (the scenarios "ask" of e2e/run.sh).
 */
class MicrophoneFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val system = SystemDialog(UiDevice.getInstance(instrumentation))

    private fun connect() {
        app.chooseTalkMode()
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        // Only where the user may talk is the microphone needed, and only then it is asked for.
        assertFalse("Asked for the microphone before it is needed", app.exists(MICROPHONE_CONTINUE_TEST_TAG))
        app.joinLobby()
    }

    @Test
    fun itIsExplainedWhenTalkingBecomesPossibleThenTheSystemAsksAndAllowingItLeavesNoNotice() {
        connect()

        app.awaitOrFail("the explanation of the microphone") { app.exists(MICROPHONE_CONTINUE_TEST_TAG) }
        compose.onNodeWithTag(MICROPHONE_CONTINUE_TEST_TAG).performClick()
        system.answer(SystemDialog.ALLOW_WHILE_IN_USE, PERMISSION)

        app.await("the explanation to close") { !app.exists(MICROPHONE_CONTINUE_TEST_TAG) }
        assertFalse("A notice about the microphone although it is allowed", app.exists(MICROPHONE_BANNER_TEST_TAG))
        app.disconnect()
    }

    @Test
    fun refusingItKeepsTheConnectionWithANoticeThatLeadsToTheQuestionAndThenToTheSystemSettings() {
        connect()

        // "Not now": no system dialog, the channels stay, a notice says what is missing.
        app.awaitOrFail("the explanation of the microphone") { app.exists(MICROPHONE_NOT_NOW_TEST_TAG) }
        compose.onNodeWithTag(MICROPHONE_NOT_NOW_TEST_TAG).performClick()
        awaitNotice()
        app.assertSeededChannels()

        // Tapping the notice lets the system ask; a second refusal is final for the system.
        compose.onNodeWithTag(MICROPHONE_BANNER_TEST_TAG).performClick()
        system.answer(SystemDialog.DENY, PERMISSION)
        awaitNotice()
        compose.onNodeWithTag(MICROPHONE_BANNER_TEST_TAG).performClick()
        system.answer(SystemDialog.DENY, PERMISSION)
        awaitNotice()
        assertFalse("Explained again after a refusal", app.exists(MICROPHONE_CONTINUE_TEST_TAG))

        // From now on the notice leads to the app's page in the system settings.
        compose.onNodeWithTag(MICROPHONE_BANNER_TEST_TAG).performClick()
        assertTrue("The system settings did not open", system.settingsOpened())
        // Allowed there (done here the way the settings page does it), and back to the app.
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        UiDevice.getInstance(instrumentation).pressBack()
        app.await("the notice to go away once the microphone is allowed") { !app.exists(MICROPHONE_BANNER_TEST_TAG) }
        app.disconnect()
    }

    private fun awaitNotice() =
        app.await("the notice that the others cannot hear the user") { app.exists(MICROPHONE_BANNER_TEST_TAG) }

    private companion object {
        const val PERMISSION = "the microphone"
    }
}
