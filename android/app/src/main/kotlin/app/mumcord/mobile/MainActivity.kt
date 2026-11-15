// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile

import android.app.PictureInPictureParams
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.platform.video.fitViewport
import app.mumcord.mobile.session.chatFromNotificationKey
import app.mumcord.mobile.ui.AppRoute
import app.mumcord.mobile.ui.AppViewModel
import app.mumcord.mobile.ui.streams.LocalStreamBounds
import app.mumcord.mobile.ui.streams.LocalStreamPicture
import app.mumcord.mobile.ui.streams.streamPicture
import app.mumcord.mobile.ui.themes.AppThemeHost
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels { AppViewModel.Factory }

    private var pictureInPicture by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not again after a rotation: the link that started the app was handled the first time.
        if (savedInstanceState == null) openLink(intent)
        followOpenedStream()
        // Also after a language change, which recreates the screens.
        viewModel.more.syncLanguage()
        setContent {
            val theme by viewModel.appTheme.collectAsStateWithLifecycle()
            AppThemeHost(theme) {
                SystemBarsFollowTheme()
                val picture = remember(viewModel) { streamPicture(viewModel.streamVideo) }
                CompositionLocalProvider(LocalStreamPicture provides picture, LocalStreamBounds provides ::streamMoved) {
                    AppRoute(viewModel, pictureInPicture = pictureInPicture)
                }
            }
        }
    }

    /**
     * The status and navigation bar icons follow the app's colours, not the phone's dark mode:
     * the user may show the app light on a dark phone and the other way round.
     */
    @Composable
    private fun SystemBarsFollowTheme() {
        val dark = MumcordTheme.isDark
        LaunchedEffect(dark) {
            val style = if (dark) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            }
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }
    }

    /**
     * While a stream is open (full screen or mini window), leaving the app moves it into the
     * system's picture-in-picture window (FR-5.76). Closing that window stops watching.
     */
    private fun followOpenedStream() {
        addOnPictureInPictureModeChangedListener { change ->
            pictureInPicture = change.isInPictureInPictureMode
            // The small window counts as leaving the app for the shared camera (FR-5.75).
            cameraShare.setOnScreen(!change.isInPictureInPictureMode && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            // Left the window without coming back to the app: the user closed it.
            if (!change.isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                viewModel.streams.close()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.streams.state.map { it.opened != null }.distinctUntilChanged().collect { open ->
                    streamOpen = open
                    updatePictureInPicture()
                }
            }
        }
    }

    private var streamOpen = false
    private var streamBounds: Rect? = null

    /** Where the opened stream's 16:9 picture is, so the system window grows out of it. */
    private fun streamMoved(bounds: Rect) {
        val viewport = fitViewport(bounds.width(), bounds.height(), PICTURE_WIDTH, PICTURE_HEIGHT)
        val picture = Rect(viewport[0], viewport[1], viewport[0] + viewport[2], viewport[1] + viewport[3])
            .apply { offset(bounds.left, bounds.top) }
        if (picture == streamBounds) return
        streamBounds = picture
        updatePictureInPicture()
    }

    private fun updatePictureInPicture() {
        setPictureInPictureParams(
            PictureInPictureParams.Builder()
                .setAutoEnterEnabled(streamOpen)
                .setSeamlessResizeEnabled(streamOpen)
                .setAspectRatio(Rational(PICTURE_WIDTH, PICTURE_HEIGHT))
                .setSourceRectHint(streamBounds.takeIf { streamOpen })
                .build(),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.streams.setOnScreen(true)
        cameraShare.setOnScreen(!isInPictureInPictureMode)
        // Only on screen may the session's background service be started or changed.
        (application as MumcordApplication).voiceCall.onAppVisible()
    }

    override fun onStop() {
        super.onStop()
        // Stopped while in the picture-in-picture window: the user closed it.
        if (isInPictureInPictureMode) viewModel.streams.close()
        // Nothing is decoded that nobody sees (FR-5.43).
        viewModel.streams.setOnScreen(false)
        // The shared camera pauses while the app is in the background (FR-5.75).
        cameraShare.setOnScreen(false)
    }

    private val cameraShare get() = (application as MumcordApplication).cameraShare

    /** A `mumble://` link from another app. It is only shown; the user decides what happens. */
    private fun openLink(intent: Intent) {
        // The session's notification was tapped: show the voice view.
        if (intent.action == ACTION_OPEN_VOICE) viewModel.connected.openVoice()
        // A message notification was tapped: show its chat.
        if (intent.action == ACTION_OPEN_CHAT) {
            intent.getStringExtra(EXTRA_CHAT)?.let(::chatFromNotificationKey)?.let(viewModel::openChat)
        }
        if (intent.action != Intent.ACTION_VIEW) return
        val url = intent.dataString ?: return
        viewModel.openLink(url)
    }

    companion object {
        // Streams are 16:9 as a rule; the system window keeps that shape.
        private const val PICTURE_WIDTH = 16
        private const val PICTURE_HEIGHT = 9

        const val ACTION_OPEN_VOICE = "app.mumcord.mobile.action.OPEN_VOICE"
        const val ACTION_OPEN_CHAT = "app.mumcord.mobile.action.OPEN_CHAT"

        /** The notification key of the chat to open, see `ChatId.notificationKey`. */
        const val EXTRA_CHAT = "app.mumcord.mobile.extra.CHAT"
    }
}
