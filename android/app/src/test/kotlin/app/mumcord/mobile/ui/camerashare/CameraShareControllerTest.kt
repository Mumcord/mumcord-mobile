// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.camerashare

import app.mumcord.mobile.bridge.CameraProblem
import app.mumcord.mobile.bridge.CaptureFailure
import app.mumcord.mobile.bridge.OwnCamera
import app.mumcord.mobile.bridge.OwnCameraState
import app.mumcord.mobile.platform.camera.CameraFacing
import app.mumcord.mobile.platform.camera.CameraTrouble
import app.mumcord.mobile.session.CAMERA_OFF
import app.mumcord.mobile.session.CameraShare
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.AppViewModel
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Sharing the own camera through the view model, against the fake engine and a fake camera. */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraShareControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private val sources = mutableListOf<FakeCameraSource>()
    private lateinit var camera: CameraShare

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val source get() = sources.last()

    /** Connected, with the app on screen. */
    private fun TestScope.connected(): CameraShareController {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupServer(), servers = listOf(savedServer(1, "Nordlicht"))).also { engine = it }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        camera = CameraShare(repository, { onTrouble -> FakeCameraSource(onTrouble).also { sources += it } }, backgroundScope)
        val viewModel = AppViewModel(
            repository,
            DoneSetup,
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            cameraShare = camera,
        )
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        viewModel.connect(1)
        camera.setOnScreen(true)
        runCurrent()
        return checkNotNull(viewModel.cameraShare)
    }

    @Test
    fun thePreviewRunsTheFrontCameraAndNothingIsSharedBeforeStart() = runTest(dispatcher) {
        val controller = connected()
        assertNull("no camera before the preview", sources.firstOrNull())

        controller.open()
        runCurrent()
        assertNotNull("the engine got the app's camera", engine.appCamera)
        assertEquals(CameraFacing.Front, source.running)
        val sheet = checkNotNull(controller.state.value.sheet)
        assertEquals(CameraFacing.Front, sheet.facing)
        assertTrue(sheet.canSwitch)
        assertNull("FR-5.74: nothing is shared from the preview", controller.state.value.sharing)

        controller.switchCamera()
        runCurrent()
        assertEquals(CameraFacing.Back, source.running)

        controller.start()
        runCurrent()
        assertNull(controller.state.value.sheet)
        assertEquals(0, controller.state.value.sharing?.viewers)
        engine.watchCamera(2u)
        runCurrent()
        assertEquals(2, controller.state.value.sharing?.viewers)
        assertEquals(CameraFacing.Back, controller.state.value.sharing?.facing)
    }

    @Test
    fun theCameraPausesInTheBackgroundAndResumesWithTheSameCamera() = runTest(dispatcher) {
        val controller = connected()
        controller.open()
        runCurrent()
        controller.switchCamera()
        controller.start()
        runCurrent()

        camera.setOnScreen(false)
        runCurrent()
        assertNull("FR-5.75: the camera is released in the background", source.running)
        assertEquals("sharing goes on", OwnCameraState.SHARING, engine.ownCameraState)

        camera.setOnScreen(true)
        runCurrent()
        assertEquals("the same camera again", CameraFacing.Back, source.running)
        assertEquals(1, sources.size)
    }

    @Test
    fun cancelAndStopReleaseTheCamera() = runTest(dispatcher) {
        val controller = connected()
        controller.open()
        runCurrent()
        controller.stop()
        runCurrent()
        assertNull(source.running)
        assertFalse(controller.state.value.on)

        controller.open()
        runCurrent()
        assertEquals("the preview starts with the front camera again", CameraFacing.Front, source.running)
        controller.start()
        runCurrent()
        controller.stop()
        runCurrent()
        assertNull(source.running)
        assertEquals(CameraShareUiState(), controller.state.value)
    }

    @Test
    fun aRefusedStartStaysInThePreviewWithTheReason() = runTest(dispatcher) {
        val controller = connected()
        engine.cameraStartProblem = CameraProblem.DIRECT_CONNECTIONS_OFF
        controller.open()
        runCurrent()
        controller.start()
        runCurrent()
        val sheet = checkNotNull(controller.state.value.sheet)
        assertEquals(CameraProblem.DIRECT_CONNECTIONS_OFF, sheet.problem)
        assertFalse(sheet.starting)
        assertEquals("the preview keeps running", CameraFacing.Front, source.running)
    }

    @Test
    fun aCameraThatStopsByItselfEndsSharingWithANotice() = runTest(dispatcher) {
        val controller = connected()
        controller.open()
        runCurrent()
        controller.start()
        runCurrent()

        source.trouble(CameraTrouble.Failed)
        runCurrent()
        assertEquals(listOf(CaptureFailure.CAMERA_FAILED), engine.captureFailures.toList())
        assertNull(source.running)
        assertEquals(CameraProblem.CAMERA_FAILED, controller.state.value.notice)

        controller.dismissNotice()
        runCurrent()
        assertNull(controller.state.value.notice)
    }

    @Test
    fun onlyACameraThatWasSharedOrStartingLeavesANotice() {
        val off = CAMERA_OFF.copy(problem = CameraProblem.RELAY_LOST)
        val sharing = OwnCamera(OwnCameraState.SHARING, null, 1u)
        val preview = OwnCamera(OwnCameraState.PREVIEW, null, 0u)
        assertEquals(CameraProblem.RELAY_LOST, stoppedNotice(sharing, off))
        assertNull("the user's own cancel", stoppedNotice(preview, CAMERA_OFF))
        assertNull(stoppedNotice(sharing, CAMERA_OFF))
        assertNull(stoppedNotice(sharing, preview))
    }

    @Test
    fun aPhoneWithOneCameraHasNoSwitch() {
        val state = cameraShareUiState(
            OwnCamera(OwnCameraState.STARTING, null, 0u),
            CameraFacing.Front,
            setOf(CameraFacing.Front),
            notice = null,
        )
        assertFalse(checkNotNull(state.sheet).canSwitch)
        assertTrue(state.sheet!!.starting)
        assertTrue(state.on)
    }
}
