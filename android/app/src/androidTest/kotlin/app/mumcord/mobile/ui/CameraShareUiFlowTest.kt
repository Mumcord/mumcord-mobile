// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.mumcord.mobile.bridge.CaptureFailure
import app.mumcord.mobile.bridge.OwnCameraState
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.platform.camera.CameraFacing
import app.mumcord.mobile.platform.camera.CameraTrouble
import app.mumcord.mobile.session.CameraShare
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.camerashare.CAMERA_BAR_STOP_TEST_TAG
import app.mumcord.mobile.ui.camerashare.CAMERA_BAR_TEST_TAG
import app.mumcord.mobile.ui.camerashare.CAMERA_NOTICE_DISMISS_TEST_TAG
import app.mumcord.mobile.ui.camerashare.CAMERA_NOTICE_TEST_TAG
import app.mumcord.mobile.ui.camerashare.CAMERA_SHARE_CANCEL_TEST_TAG
import app.mumcord.mobile.ui.camerashare.CAMERA_SHARE_PREVIEW_TEST_TAG
import app.mumcord.mobile.ui.camerashare.CAMERA_SHARE_START_TEST_TAG
import app.mumcord.mobile.ui.camerashare.CAMERA_SHARE_SWITCH_TEST_TAG
import app.mumcord.mobile.ui.camerashare.FAKE_CAMERA_PICTURE_TEST_TAG
import app.mumcord.mobile.ui.camerashare.FakeCameraSource
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.VOICE_CAMERA_TEST_TAG
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sharing the own camera on a device against a fake engine and a fake camera (CameraX cannot
 * run without a real camera scene): the voice view's camera button, the preview with the
 * camera's picture and the switch, "Start sharing", the "Camera on" bar, the pause while the
 * app is not on screen, and the notice of a camera that stops by itself.
 */
class CameraShareUiFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sources = CopyOnWriteArrayList<FakeCameraSource>()
    private lateinit var engine: FakeEngine
    private lateinit var camera: CameraShare

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @Before
    fun grantCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
    }

    @After
    fun tearDown() = scope.cancel()

    private val source get() = sources.last()

    private fun connect() {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupServer(), servers = listOf(savedServer(1, "Nordlicht"))).also { engine = it }
            },
            scope = scope,
            engineDispatcher = Dispatchers.IO,
            log = {},
        )
        camera = CameraShare(
            repository,
            { onTrouble -> FakeCameraSource(onTrouble).also { sources += it } },
            CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate),
        )
        camera.setOnScreen(true)
        val viewModel = AppViewModel(
            repository,
            DoneSetup,
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            cameraShare = camera,
        )
        compose.setContent {
            MumcordTheme {
                AppRoute(
                    viewModel,
                    LocalNetworkSystem(ask = {}, openSettings = {}),
                    MicrophoneSystem(ask = {}, openSettings = {}),
                )
            }
        }
        compose.connectToNordlicht()
        await { exists("Nordlicht · Voice connected") }
        compose.onNodeWithText("Nordlicht · Voice connected").performClick()
        await { shown(VOICE_CAMERA_TEST_TAG) }
    }

    @Test
    fun thePreviewComesFirstThenTheCameraIsSharedPausesOffScreenAndStops() {
        connect()
        compose.onNodeWithTag(VOICE_CAMERA_TEST_TAG).performClick()
        await { shown(CAMERA_SHARE_PREVIEW_TEST_TAG) && shown(FAKE_CAMERA_PICTURE_TEST_TAG) && exists("Front camera") }
        assertEquals(CameraFacing.Front, source.running)
        assertEquals("FR-5.74: nothing is shared from the preview", OwnCameraState.PREVIEW, engine.ownCameraState)

        compose.onNodeWithTag(CAMERA_SHARE_SWITCH_TEST_TAG).performClick()
        await { exists("Back camera") && source.running == CameraFacing.Back }

        compose.onNodeWithTag(CAMERA_SHARE_START_TEST_TAG).performClick()
        await { shown(CAMERA_BAR_TEST_TAG) && exists("Camera on · 0 watching") }
        assertTrue("the preview closed", !shown(CAMERA_SHARE_PREVIEW_TEST_TAG))
        engine.watchCamera(1u)
        await { exists("Camera on · 1 watching") }

        // FR-5.75: off screen the camera is released, sharing goes on; back on screen it resumes.
        compose.runOnUiThread { camera.setOnScreen(false) }
        await { source.running == null }
        assertEquals(OwnCameraState.SHARING, engine.ownCameraState)
        compose.runOnUiThread { camera.setOnScreen(true) }
        await { source.running == CameraFacing.Back }

        compose.onNodeWithTag(CAMERA_BAR_STOP_TEST_TAG).performClick()
        await { !shown(CAMERA_BAR_TEST_TAG) && source.running == null }
        assertTrue("no notice after the user's own stop", !shown(CAMERA_NOTICE_TEST_TAG))
    }

    @Test
    fun cancelInThePreviewSendsNothingAndACameraThatStopsSaysSo() {
        connect()
        compose.onNodeWithTag(VOICE_CAMERA_TEST_TAG).performClick()
        await { shown(CAMERA_SHARE_CANCEL_TEST_TAG) }
        compose.onNodeWithTag(CAMERA_SHARE_CANCEL_TEST_TAG).performClick()
        await { !shown(CAMERA_SHARE_PREVIEW_TEST_TAG) && source.running == null }
        assertEquals(OwnCameraState.OFF, engine.ownCameraState)

        compose.onNodeWithTag(VOICE_CAMERA_TEST_TAG).performClick()
        await { shown(CAMERA_SHARE_START_TEST_TAG) }
        compose.onNodeWithTag(CAMERA_SHARE_START_TEST_TAG).performClick()
        await { shown(CAMERA_BAR_TEST_TAG) }
        compose.runOnUiThread { source.trouble(CameraTrouble.Failed) }
        await { shown(CAMERA_NOTICE_TEST_TAG) && exists("Camera stopped.", substring = true) }
        assertEquals(listOf(CaptureFailure.CAMERA_FAILED), engine.captureFailures.toList())
        assertNull(source.running)
        compose.onNodeWithTag(CAMERA_NOTICE_DISMISS_TEST_TAG).performClick()
        await { !shown(CAMERA_NOTICE_TEST_TAG) }
    }

    private fun shown(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun exists(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun await(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS, condition)

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
