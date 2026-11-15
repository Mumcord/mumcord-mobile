// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.TestSettings
import app.mumcord.mobile.platform.audio.AudioProbe
import app.mumcord.mobile.platform.camera.CameraFacing
import app.mumcord.mobile.ui.camera.CAMERA_CONTINUE_TEST_TAG
import app.mumcord.mobile.ui.camerashare.CAMERA_BAR_STOP_TEST_TAG
import app.mumcord.mobile.ui.camerashare.CAMERA_BAR_SWITCH_TEST_TAG
import app.mumcord.mobile.ui.camerashare.CAMERA_BAR_TEST_TAG
import app.mumcord.mobile.ui.camerashare.CAMERA_NOTICE_DISMISS_TEST_TAG
import app.mumcord.mobile.ui.camerashare.CAMERA_NOTICE_TEST_TAG
import app.mumcord.mobile.ui.camerashare.CAMERA_SHARE_PREVIEW_TEST_TAG
import app.mumcord.mobile.ui.camerashare.CAMERA_SHARE_START_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_CAMERA_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_COLLAPSE_TEST_TAG
import app.mumcord.mobile.ui.streams.DIRECT_CONNECTIONS_YES_TEST_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Sharing the phone's camera (FR-5.74, FR-5.75, AC-5.24): the phone's camera is a test pattern
 * ([TestPatternCamera]) that goes through the app's real camera input, the engine's VP9
 * encoder and a direct connection to a second person on the host (`e2e/stream-peer --viewer`),
 * who decodes it and checks every picture. The camera is asked for and explained first; the
 * preview sends nothing; "Start sharing" does; the switch to the back camera reaches the
 * viewer; in the background the camera is released and nothing is sent, and on return it goes
 * on. e2e/run.sh checks the viewer's notes at the marks this test sets.
 */
class CameraFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val system = SystemDialog(UiDevice.getInstance(instrumentation))
    private val phone = PhoneSystem()

    @Volatile
    private var camera: TestPatternCamera? = null

    @Before
    fun setUp() {
        AudioProbe.replaceMicrophone(AudioProbe.Signal.Silence)
        TestSettings.cameraSource = { frames, _ -> TestPatternCamera(frames).also { camera = it } }
    }

    @After
    fun tearDown() {
        TestSettings.cameraSource = null
        AudioProbe.replaceMicrophone(null)
    }

    /** Asks e2e/run.sh to have the viewer write down what it saw now. */
    private fun mark(word: String) {
        Log.i(LOG_TAG, "stream mark $word")
    }

    private fun onScreen(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun watching(viewers: Int): Boolean = onScreen("Camera on · $viewers watching")

    private fun answerDirectConnections(): Boolean {
        if (!app.exists(DIRECT_CONNECTIONS_YES_TEST_TAG)) return false
        compose.onNodeWithTag(DIRECT_CONNECTIONS_YES_TEST_TAG).performClick()
        return true
    }

    @Test
    fun theCameraIsSharedAfterThePreviewSwitchesToTheBackAndPausesInTheBackground() {
        app.chooseTalkMode()
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()

        // The voice view's camera button; the camera is explained before the system asks.
        compose.onNodeWithTag(VOICE_BAR_TEST_TAG).performTouchInput { swipeUp() }
        app.awaitOrFail("the camera button in the voice view") { app.exists(VOICE_CAMERA_TEST_TAG) }
        compose.onNodeWithTag(VOICE_CAMERA_TEST_TAG).performClick()
        app.await("the explanation or the preview") { app.exists(CAMERA_CONTINUE_TEST_TAG) || app.exists(CAMERA_SHARE_PREVIEW_TEST_TAG) }
        if (app.exists(CAMERA_CONTINUE_TEST_TAG)) {
            assertTrue("the explanation says nothing is sent before sharing", onScreen("Nothing is sent before you start sharing"))
            compose.onNodeWithTag(CAMERA_CONTINUE_TEST_TAG).performClick()
            system.answer(SystemDialog.ALLOW_WHILE_IN_USE, CAMERA_PERMISSION)
        }

        // The preview runs the front camera and sends nothing (FR-5.74).
        app.awaitOrFail("the preview with the front camera") {
            app.exists(CAMERA_SHARE_PREVIEW_TEST_TAG) && onScreen("Front camera") && (camera?.running == CameraFacing.Front)
        }
        // The viewer notes that no camera is on and no picture came (e2e/run.sh).
        SystemClock.sleep(UNTOUCHED_MILLIS)
        mark("preview")

        mark("start-tap")
        compose.onNodeWithTag(CAMERA_SHARE_START_TEST_TAG).performClick()
        val tapped = SystemClock.elapsedRealtime()
        app.awaitOrFail("the camera bar") { app.exists(CAMERA_BAR_TEST_TAG) }
        // The viewer's direct connection may bring the engine's question first.
        var asked = false
        var restarted = false
        app.awaitOrFail("the viewer watching") {
            if (answerDirectConnections()) asked = true
            // Known engine gap: the answer can end the camera on a phone (see the report).
            if (app.exists(CAMERA_NOTICE_TEST_TAG) && !restarted) {
                restarted = true
                report("camera: gap_a=camera_stopped_after_direct_answer")
                compose.onNodeWithTag(CAMERA_NOTICE_DISMISS_TEST_TAG).performClick()
                compose.onNodeWithTag(VOICE_CAMERA_TEST_TAG).performClick()
                app.awaitOrFail("the preview again") { app.exists(CAMERA_SHARE_START_TEST_TAG) }
                compose.onNodeWithTag(CAMERA_SHARE_START_TEST_TAG).performClick()
            }
            watching(1)
        }
        report("camera: watched_ms=${SystemClock.elapsedRealtime() - tapped} asked=$asked restarted=$restarted")
        SystemClock.sleep(SETTLE_MILLIS)
        mark("front")

        // AC-5.24: front and back.
        compose.onNodeWithTag(CAMERA_BAR_SWITCH_TEST_TAG).performClick()
        app.awaitOrFail("the back camera") { camera?.running == CameraFacing.Back }
        SystemClock.sleep(SETTLE_MILLIS)
        mark("back")

        // FR-5.75: the background pauses the camera; voice and sharing go on.
        // The app is not on screen now, so only what does not read the screen is waited for.
        phone.device.pressHome()
        app.await("the camera released in the background") { camera?.running == null }
        val sentBefore = camera?.sent?.get()
        SystemClock.sleep(SETTLE_MILLIS)
        mark("background-start")
        SystemClock.sleep(SETTLE_MILLIS)
        mark("background-end")
        assertEquals("nothing is sent in the background", sentBefore, camera?.sent?.get())

        phone.shell("am start -n ${PhoneSystem.APP_PACKAGE}/.MainActivity")
        app.await("the camera back on return") { camera?.running == CameraFacing.Back }
        app.awaitOrFail("still sharing") { app.exists(CAMERA_BAR_TEST_TAG) && watching(1) }
        SystemClock.sleep(SETTLE_MILLIS)
        mark("resumed")

        compose.onNodeWithTag(CAMERA_BAR_STOP_TEST_TAG).performClick()
        app.awaitOrFail("the camera off") { !app.exists(CAMERA_BAR_TEST_TAG) && camera?.running == null }
        assertFalse("no notice after the user's own stop", app.exists(CAMERA_NOTICE_TEST_TAG))
        SystemClock.sleep(SETTLE_MILLIS)
        mark("stopped")
        compose.onNodeWithTag(VOICE_COLLAPSE_TEST_TAG).performClick()
        app.disconnect()
    }

    private fun report(line: String) {
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("camera", "\n$line\n") })
    }

    private companion object {
        const val CAMERA_PERMISSION = "android.permission.CAMERA"
        /** How long the preview is left alone, to see that nothing is sent from it. */
        const val UNTOUCHED_MILLIS = 3_000L
        /** Time for the viewer to see a change (a keyframe a second at most). */
        const val SETTLE_MILLIS = 3_000L
        const val LOG_TAG = "MumcordE2E"
    }
}
