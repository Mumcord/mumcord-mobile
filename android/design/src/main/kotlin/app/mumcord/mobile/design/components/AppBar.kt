// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * Top app bar (`.appbar`). [large] is the big-title variant of top-level screens; pass
 * [onBack] for the variant with a back arrow. [leading] holds a tile or avatar before the
 * title, [subtitle] a line of small muted content under it, [actions] the icon buttons at the
 * end. The bar keeps clear of the status bar through [windowInsets].
 */
@Composable
fun MumcordAppBar(
    title: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    border: Boolean = false,
    onBack: (() -> Unit)? = null,
    backContentDescription: String = "",
    windowInsets: WindowInsets = WindowInsets.statusBars,
    leading: (@Composable () -> Unit)? = null,
    subtitle: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = MumcordTheme.colors
    val bottomBorder = if (border) {
        Modifier.drawBehind {
            val y = size.height - 0.5.dp.toPx()
            drawLine(colors.border, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
    } else {
        Modifier
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(bottomBorder)
            .windowInsetsPadding(windowInsets)
            .defaultMinSize(minHeight = if (large) 64.dp else 56.dp)
            // The back button sits 10 dp further out so that its arrow lines up with the content.
            .padding(start = if (onBack != null) 6.dp else 16.dp, top = if (large) 10.dp else 6.dp, end = 8.dp, bottom = 6.dp),
    ) {
        if (onBack != null) {
            MumcordIconButton(MumcordIcons.ChevronLeft, contentDescription = backContentDescription, onClick = onBack)
        }
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            LineText(
                text = title,
                style = if (large) MumcordTheme.typography.displayLarge else MumcordTheme.typography.title,
                color = colors.text,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = subtitle,
                )
            }
        }
        actions()
    }
}

/** Text of an app bar subtitle in the right style. */
@Composable
fun MumcordAppBarSubtitle(text: String, modifier: Modifier = Modifier) {
    LineText(text, MumcordTheme.typography.small, MumcordTheme.colors.textMuted, modifier)
}

@Preview(name = "App bar dark")
@Composable
private fun AppBarDarkPreview() {
    PreviewFrame(darkTheme = true) { AppBarSample() }
}

@Preview(name = "App bar light")
@Composable
private fun AppBarLightPreview() {
    PreviewFrame(darkTheme = false) { AppBarSample() }
}

@Composable
internal fun AppBarSample() {
    MumcordAppBar(title = "Servers", large = true, windowInsets = WindowInsets()) {
        MumcordIconButton(MumcordIcons.Search, contentDescription = "Search servers", onClick = {})
        MumcordIconButton(MumcordIcons.Settings, contentDescription = "Settings", onClick = {})
    }
    MumcordAppBar(
        title = "Settings",
        onBack = {},
        backContentDescription = "Back",
        border = true,
        windowInsets = WindowInsets(),
    )
    MumcordAppBar(
        title = "Nordlicht",
        windowInsets = WindowInsets(),
        leading = { MumcordTile("NO", brand = true, small = true) },
        subtitle = {
            MumcordDot()
            MumcordAppBarSubtitle("5 online · 24 ms")
        },
    ) {
        MumcordIconButton(MumcordIcons.Search, contentDescription = "Search channels and people", onClick = {})
        MumcordIconButton(MumcordIcons.More, contentDescription = "Server menu", onClick = {})
    }
}
