// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.theme.tokenShadow
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * The frame of a bottom sheet (`.sheet`): rounded top, grip, optional [title], and the content
 * in a column. Showing and hiding it, and the dimmed backdrop behind it
 * (`MumcordTheme.colors.backdrop`), belong to the screen that uses it.
 */
@Composable
fun MumcordSheetFrame(
    modifier: Modifier = Modifier,
    title: String? = null,
    windowInsets: WindowInsets = WindowInsets.navigationBars,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(topStart = MumcordRadius.sheet, topEnd = MumcordRadius.sheet)
    Column(
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        modifier = modifier
            .fillMaxWidth()
            .tokenShadow(MumcordTheme.shadows.raised, shape)
            .clip(shape)
            .background(colors.bgElevated)
            .drawBehind {
                val y = 0.5.dp.toPx()
                drawLine(colors.border, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            .windowInsetsPadding(windowInsets)
            .padding(start = MumcordSpace.s4, top = 10.dp, end = MumcordSpace.s4, bottom = MumcordSpace.s2),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 40.dp, height = 5.dp)
                .alpha(0.7f)
                .background(colors.borderStrong, CircleShape),
        )
        if (title != null) {
            LineText(
                text = title,
                style = MumcordTheme.typography.title.copy(lineHeight = 1.4.em),
                color = colors.text,
                modifier = Modifier.semantics { heading() },
            )
        }
        content()
    }
}

enum class MumcordBannerTone { Info, Warning, Danger }

/**
 * Hint banner (`.banner`) with a 16 dp margin at the sides: an icon in the colour of its
 * [tone], an optional bold [title] and the hint [text]. Inside a bottom sheet, which has its
 * own margin, [horizontalMargin] is zero.
 */
@Composable
fun MumcordBanner(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector = MumcordIcons.Info,
    tone: MumcordBannerTone = MumcordBannerTone.Info,
    horizontalMargin: Dp = MumcordSpace.s4,
) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.card)
    val iconTint = when (tone) {
        MumcordBannerTone.Info -> colors.accentText
        MumcordBannerTone.Warning -> colors.warning
        MumcordBannerTone.Danger -> colors.danger
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalMargin, vertical = MumcordSpace.s2)
            .background(colors.bgSelected, shape)
            .border(1.dp, colors.border, shape)
            .padding(start = MumcordSpace.s4 + 1.dp, top = MumcordSpace.s3 + 1.dp, end = MumcordSpace.s3 + 1.dp, bottom = MumcordSpace.s3 + 1.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        MumcordIcon(icon, contentDescription = null, tint = iconTint)
        BasicText(
            text = buildAnnotatedString {
                if (title != null) {
                    withStyle(SpanStyle(color = colors.text, fontWeight = FontWeight.Bold)) { append(title) }
                    append(" ")
                }
                append(text)
            },
            style = MumcordTheme.typography.small.copy(color = colors.textSecondary),
            modifier = Modifier.weight(1f),
        )
    }
}

@Preview(name = "Overlays dark")
@Composable
private fun OverlaysDarkPreview() {
    PreviewFrame(darkTheme = true) { OverlaysSample() }
}

@Preview(name = "Overlays light")
@Composable
private fun OverlaysLightPreview() {
    PreviewFrame(darkTheme = false) { OverlaysSample() }
}

@Composable
internal fun OverlaysSample() {
    MumcordBanner(title = "Hold to talk is on.", text = "Press and hold the button while you speak.")
    MumcordBanner(
        text = "The connection is unstable.",
        icon = MumcordIcons.Wifi,
        tone = MumcordBannerTone.Warning,
    )
    MumcordBanner(text = "The server certificate has changed.", icon = MumcordIcons.Alert, tone = MumcordBannerTone.Danger)
    MumcordSheetFrame(title = "Audio output", windowInsets = WindowInsets()) {
        MumcordList(horizontalMargin = 0.dp, background = MumcordTheme.colors.bgHover) {
            MumcordListRow(title = "Speaker", icon = MumcordIcons.Speaker, selected = true)
            MumcordListRow(title = "Bluetooth headset", icon = MumcordIcons.Bluetooth, divider = true)
        }
    }
}
