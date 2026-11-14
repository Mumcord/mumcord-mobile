// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.mumcord.mobile.bridge.engineVersion
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.ui.slice.SliceRoute
import app.mumcord.mobile.ui.slice.SliceViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: SliceViewModel by viewModels { SliceViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Read here, not in a composable: UI code never calls the engine while drawing.
        val version = engineVersion()
        setContent {
            MumcordTheme {
                SliceRoute(viewModel = viewModel, engineVersion = version)
            }
        }
    }
}
