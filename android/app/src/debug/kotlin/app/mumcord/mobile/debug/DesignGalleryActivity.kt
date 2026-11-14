// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.mumcord.mobile.design.theme.MumcordTheme

/**
 * Debug-only entry to the design system: the component gallery and the channels reference
 * screen built from static demo data. Start it with
 * `adb shell am start -n app.mumcord.mobile/.debug.DesignGalleryActivity`, optionally with
 * `--es screen channels` and `--es theme dark` or `--es theme light`.
 */
class DesignGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startOnChannels = intent.getStringExtra(EXTRA_SCREEN) == SCREEN_CHANNELS
        val startTheme = when (intent.getStringExtra(EXTRA_THEME)) {
            "dark" -> GalleryTheme.Dark
            "light" -> GalleryTheme.Light
            else -> GalleryTheme.System
        }
        setContent {
            var theme by rememberSaveable { mutableStateOf(startTheme) }
            var showChannels by rememberSaveable { mutableStateOf(startOnChannels) }
            val dark = when (theme) {
                GalleryTheme.System -> isSystemInDarkTheme()
                GalleryTheme.Dark -> true
                GalleryTheme.Light -> false
            }
            MumcordTheme(darkTheme = dark) {
                if (showChannels) {
                    ChannelsReferenceScreen(onOpenGallery = { showChannels = false })
                } else {
                    GalleryScreen(
                        theme = theme,
                        onThemeChange = { theme = it },
                        onOpenChannels = { showChannels = true },
                    )
                }
            }
        }
    }

    private companion object {
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_THEME = "theme"
        const val SCREEN_CHANNELS = "channels"
    }
}
