// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordScreen
import app.mumcord.mobile.design.components.MumcordSegmentedControl
import app.mumcord.mobile.design.gallery.ComponentGallery
import app.mumcord.mobile.design.tokens.MumcordSpace

enum class GalleryTheme { System, Dark, Light }

/**
 * The debug-only component gallery: a theme switch, a way to the channels reference screen and
 * every base component of the design system.
 *
 * @param scrollable false lets the content take its full height, for a screenshot of all of it.
 */
@Composable
fun GalleryScreen(
    theme: GalleryTheme,
    onThemeChange: (GalleryTheme) -> Unit,
    onOpenChannels: () -> Unit,
    scrollable: Boolean = true,
    topInsets: WindowInsets = WindowInsets.statusBars,
    bottomInsets: WindowInsets = WindowInsets.navigationBars,
) {
    MumcordScreen {
        MumcordAppBar(title = "Components", large = true, windowInsets = topInsets)
        Column(
            modifier = (if (scrollable) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier)
                .windowInsetsPadding(bottomInsets),
        ) {
            MumcordSegmentedControl(
                options = GalleryTheme.entries.map { it.name },
                selectedIndex = theme.ordinal,
                onSelect = { onThemeChange(GalleryTheme.entries[it]) },
                modifier = Modifier.padding(horizontal = MumcordSpace.s4, vertical = MumcordSpace.s2),
            )
            MumcordButton(
                text = "Channels reference screen",
                onClick = onOpenChannels,
                style = MumcordButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = MumcordSpace.s4),
            )
            ComponentGallery()
        }
    }
}
