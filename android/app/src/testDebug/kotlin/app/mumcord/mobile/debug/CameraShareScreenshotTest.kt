// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.CameraProblem
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.platform.camera.CameraFacing
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.fold
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.ui.camerashare.CameraShareActions
import app.mumcord.mobile.ui.camerashare.CameraShareUi
import app.mumcord.mobile.ui.camerashare.CameraShareUiState
import app.mumcord.mobile.ui.camerashare.CameraSharingState
import app.mumcord.mobile.ui.camerashare.CameraSheetState
import app.mumcord.mobile.ui.camerashare.FakeCameraSource
import app.mumcord.mobile.ui.connected.ConnectedController
import app.mumcord.mobile.ui.connected.ConnectedLocal
import app.mumcord.mobile.ui.connected.ConnectedShell
import app.mumcord.mobile.ui.connected.connectedUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws camera sharing in both themes at the size of the mockups: the preview sheet of mockup
 * `13-camera-share` (without its "Blur background" row), and, without a mockup, the preview
 * after a refused start, the "Camera on" bar above the voice bar and the voice view with its
 * camera button. A drawn person stands in for the camera's picture, as in the mockup.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class CameraShareScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    // The status bar and home indicator areas of the mockup's phone.
    private val top = WindowInsets(top = 47.dp)
    private val bottom = WindowInsets(bottom = 34.dp)

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    // Nothing is tapped in a screenshot, so the engine is never asked for.
    private val repository = SessionRepository({ error("no engine in screenshots") }, scope, Dispatchers.Unconfined, log = {})
    private val actions = ConnectedController(repository, scope)
    private val chatActions = screenshotChatController(repository, scope)
    private val messagesTab = ScreenshotMessages(repository, actions, scope)
    private val more = ScreenshotMore(repository, actions, scope)
    private val camera = FakeCameraSource()

    private object NoActions : CameraShareActions {
        override fun open() = Unit
        override fun switchCamera() = Unit
        override fun start() = Unit
        override fun stop() = Unit
        override fun dismissNotice() = Unit
    }

    private val session: SessionState = listOf(
        BridgeEvent.ConnectionChanged(ConnectionState.Connected),
        BridgeEvent.ServerSynced(mockupServer()),
    ).fold(SessionState()) { state, event -> state.fold(event) }

    @After
    fun tearDown() = scope.cancel()

    private fun capture(name: String, dark: Boolean, cameraState: CameraShareUiState, local: ConnectedLocal = ConnectedLocal()) =
        composeRule.captureScreenshot("$name-${if (dark) "dark" else "light"}") {
            val state = checkNotNull(connectedUiState(session, local, "Nordlicht"))
            MumcordTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize()) {
                    ConnectedShell(
                        state,
                        actions,
                        onDisconnect = {},
                        onMicrophone = {},
                        chat = null,
                        chatBadge = 0,
                        chatActions = chatActions,
                        chatEnvironment = screenshotChatEnvironment(),
                        messages = messagesTab.state(session),
                        messagesActions = messagesTab.actions,
                        conversation = null,
                        conversationActions = messagesTab.conversationActions,
                        more = more.state(session),
                        moreActions = more.actions,
                        onNotificationSettings = {},
                        onIdentities = {},
                        topInsets = top,
                        bottomInsets = bottom,
                        camera = CameraShareUi(cameraState, NoActions, onCameraButton = {}, preview = { camera.Preview(it) }),
                    )
                }
            }
        }

    private val preview = CameraShareUiState(sheet = CameraSheetState(CameraFacing.Front, canSwitch = true, starting = false, problem = null))
    private val refused = CameraShareUiState(
        sheet = CameraSheetState(CameraFacing.Back, canSwitch = true, starting = false, problem = CameraProblem.DIRECT_CONNECTIONS_OFF),
    )
    private val sharing = CameraShareUiState(sharing = CameraSharingState(viewers = 2, CameraFacing.Front, canSwitch = true))

    @Test
    fun previewDark() = capture("android-camera-preview", true, preview)

    @Test
    fun previewLight() = capture("android-camera-preview", false, preview)

    @Test
    fun refusedDark() = capture("android-camera-refused", true, refused)

    @Test
    fun refusedLight() = capture("android-camera-refused", false, refused)

    @Test
    fun sharingDark() = capture("android-camera-sharing", true, sharing)

    @Test
    fun sharingLight() = capture("android-camera-sharing", false, sharing)

    @Test
    fun voiceViewDark() = capture("android-camera-voice-view", true, sharing, ConnectedLocal(voiceOpen = true))

    @Test
    fun voiceViewLight() = capture("android-camera-voice-view", false, sharing, ConnectedLocal(voiceOpen = true))
}
