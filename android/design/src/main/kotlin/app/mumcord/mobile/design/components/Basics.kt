// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordColors
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * One icon of the set in the given colour. Pass a [contentDescription] when the icon carries
 * meaning on its own, and null when the text next to it already says the same.
 */
@Composable
fun MumcordIcon(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = MumcordSize.icon,
) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

/** Single-line text that ends with an ellipsis when it does not fit, as everywhere in the mockups. */
@Composable
internal fun LineText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Small round state dot: green for online, grey for [off]. */
@Composable
fun MumcordDot(modifier: Modifier = Modifier, off: Boolean = false) {
    val colors = MumcordTheme.colors
    Box(modifier.size(8.dp).background(if (off) colors.borderStrong else colors.success, CircleShape))
}

/**
 * Count badge (unread messages). With a [ringColor] it gets a 2 dp ring in the colour of the bar
 * it sits on, so it stands off the icon below it.
 */
@Composable
fun MumcordBadge(text: String, modifier: Modifier = Modifier, ringColor: Color? = null) {
    val colors = MumcordTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .drawBehind {
                if (ringColor != null) {
                    val ring = 2.dp.toPx()
                    drawRoundRect(
                        color = ringColor,
                        topLeft = Offset(-ring, -ring),
                        size = Size(size.width + 2 * ring, size.height + 2 * ring),
                        cornerRadius = CornerRadius(size.height / 2 + ring),
                    )
                }
            }
            .defaultMinSize(minWidth = 20.dp)
            .height(20.dp)
            .background(colors.accentStop2, CircleShape)
            .padding(horizontal = 6.dp),
    ) {
        LineText(text, MumcordTheme.typography.badge, colors.onAccent)
    }
}

/** The brand gradient (peach to pink to violet at 135°) used by the server tile and the talk button. */
@Composable
internal fun accentGradient(): Brush {
    val colors = MumcordTheme.colors
    return Brush.linearGradient(
        0f to colors.accentStop1,
        0.5f to colors.accentStop2,
        1f to colors.accentStop3,
        start = Offset.Zero,
        end = Offset.Infinite,
    )
}

/** Square tile with initials for a server. [brand] uses the brand gradient. */
@Composable
fun MumcordTile(initials: String, modifier: Modifier = Modifier, brand: Boolean = false, small: Boolean = false) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(if (small) MumcordRadius.tileSmall else MumcordRadius.card)
    val background = if (brand) Modifier.background(accentGradient(), shape) else Modifier.background(colors.bgHover, shape)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(if (small) 40.dp else 48.dp).clip(shape).then(background),
    ) {
        LineText(
            text = initials,
            style = if (small) MumcordTheme.typography.tileSmall else MumcordTheme.typography.tile,
            color = if (brand) colors.onAccent else colors.text,
        )
    }
}

/** The avatar background for a colour slot from 1 to 8; other numbers wrap around. */
fun MumcordColors.avatar(slot: Int): Color =
    listOf(avatar1, avatar2, avatar3, avatar4, avatar5, avatar6, avatar7, avatar8)[Math.floorMod(slot - 1, 8)]

@Preview(name = "Basics dark")
@Composable
private fun BasicsDarkPreview() {
    PreviewFrame(darkTheme = true) { BasicsSample() }
}

@Preview(name = "Basics light")
@Composable
private fun BasicsLightPreview() {
    PreviewFrame(darkTheme = false) { BasicsSample() }
}

@Composable
internal fun BasicsSample() {
    val colors = MumcordTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = MumcordSpace.s4),
    ) {
        MumcordTile("NO", brand = true)
        MumcordTile("NO", small = true)
        MumcordBadge("2", ringColor = colors.bgServers)
        MumcordDot()
        MumcordDot(off = true)
        MumcordIcon(MumcordIcons.Mic, contentDescription = null, tint = colors.text)
    }
}
