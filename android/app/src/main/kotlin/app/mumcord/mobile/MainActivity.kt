// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.ui.AppRoute
import app.mumcord.mobile.ui.AppViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels { AppViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not again after a rotation: the link that started the app was handled the first time.
        if (savedInstanceState == null) openLink(intent)
        setContent {
            MumcordTheme {
                AppRoute(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    /** A `mumble://` link from another app. It is only shown; the user decides what happens. */
    private fun openLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val url = intent.dataString ?: return
        viewModel.openLink(url)
    }
}
