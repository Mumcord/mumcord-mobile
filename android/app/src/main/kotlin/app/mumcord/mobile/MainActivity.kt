// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.chatFromNotificationKey
import app.mumcord.mobile.ui.AppRoute
import app.mumcord.mobile.ui.AppViewModel
import app.mumcord.mobile.ui.themes.AppThemeHost

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels { AppViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not again after a rotation: the link that started the app was handled the first time.
        if (savedInstanceState == null) openLink(intent)
        setContent {
            val theme by viewModel.appTheme.collectAsStateWithLifecycle()
            AppThemeHost(theme) {
                SystemBarsFollowTheme()
                AppRoute(viewModel)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    override fun onStart() {
        super.onStart()
        // Only on screen may the session's background service be started or changed.
        (application as MumcordApplication).voiceCall.onAppVisible()
    }

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
        const val ACTION_OPEN_VOICE = "app.mumcord.mobile.action.OPEN_VOICE"
        const val ACTION_OPEN_CHAT = "app.mumcord.mobile.action.OPEN_CHAT"

        /** The notification key of the chat to open, see `ChatId.notificationKey`. */
        const val EXTRA_CHAT = "app.mumcord.mobile.extra.CHAT"
    }
}
