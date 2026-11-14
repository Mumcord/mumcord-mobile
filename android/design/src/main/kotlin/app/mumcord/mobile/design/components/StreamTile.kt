// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.theme.ellipticalGlow
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace

// 8 dp from the inner edge of the 1 dp border, as CSS positions the overlays.
private val OverlayInset = 9.dp

/**
 * Tile of a running stream (`.stream`) in 16:9 with a LIVE badge, a label and the number of
 * viewers. It shows a placeholder that stays dark in both themes, like real video; the video
 * itself is drawn by a later task. The tile is as wide as its container; set a width for the
 * compact form in the channel tree (232 dp in the mockups).
 *
 * @param contentDescription the whole tile in words for TalkBack, such as
 * "Mira's screen, live, 1 viewer".
 */
@Composable
fun MumcordStreamTile(
    label: String,
    liveLabel: String,
    viewerCount: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.lg)
    val chip = RoundedCornerShape(MumcordRadius.sm)
    // A 2 dp accent outline, 2 dp away from the tile, without changing its size.
    val outline = if (selected) {
        Modifier.drawBehind {
            val offset = 3.dp.toPx()
            drawRoundRect(
                color = colors.accent,
                topLeft = Offset(-offset, -offset),
                size = Size(size.width + 2 * offset, size.height + 2 * offset),
                cornerRadius = CornerRadius(MumcordRadius.lg.toPx() + offset),
                style = Stroke(2.dp.toPx()),
            )
        }
    } else {
        Modifier
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .aspectRatio(16f / 9f)
            .then(outline)
            .clip(shape)
            .background(colors.mediaBg)
            .drawBehind {
                // 120 % x 90 % at 80 % / 0 % and 90 % x 80 % at 0 % / 100 %, as in mock.css.
                ellipticalGlow(colors.mediaGlow1, Offset(size.width * 0.8f, 0f), size.width * 1.2f, size.height * 0.9f)
                ellipticalGlow(colors.mediaGlow2, Offset(0f, size.height), size.width * 0.9f, size.height * 0.8f)
            }
            .border(1.dp, colors.border, shape)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription },
    ) {
        MumcordIcon(
            icon = MumcordIcons.Monitor,
            contentDescription = null,
            tint = colors.mediaText,
            size = MumcordSize.iconLg,
            modifier = Modifier.alpha(0.7f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(OverlayInset)
                .height(22.dp)
                .background(colors.live, chip)
                .padding(horizontal = MumcordSpace.s2),
        ) {
            Box(Modifier.size(6.dp).background(colors.onLive, CircleShape))
            LineText(liveLabel, MumcordTheme.typography.live, colors.onLive)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(OverlayInset)
                .height(24.dp)
                .background(colors.mediaScrim, chip)
                .padding(horizontal = MumcordSpace.s2),
        ) {
            LineText(label, MumcordTheme.typography.captionStrong, colors.mediaText)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(OverlayInset)
                .height(24.dp)
                .background(colors.mediaScrim, chip)
                .padding(horizontal = MumcordSpace.s2),
        ) {
            MumcordIcon(MumcordIcons.User, contentDescription = null, tint = colors.mediaText, size = MumcordSize.iconSm)
            LineText(viewerCount.toString(), MumcordTheme.typography.captionStrong, colors.mediaText)
        }
    }
}

@Preview(name = "Stream tile dark")
@Composable
private fun StreamTileDarkPreview() {
    PreviewFrame(darkTheme = true) { StreamTileSample() }
}

@Preview(name = "Stream tile light")
@Composable
private fun StreamTileLightPreview() {
    PreviewFrame(darkTheme = false) { StreamTileSample() }
}

@Composable
internal fun StreamTileSample() {
    MumcordStreamTile(
        label = "Mira's screen",
        liveLabel = "LIVE",
        viewerCount = 1,
        contentDescription = "Mira's screen, live, 1 viewer",
        onClick = {},
        modifier = Modifier.padding(horizontal = MumcordSpace.s4).width(232.dp),
    )
    MumcordStreamTile(
        label = "Mira's screen",
        liveLabel = "LIVE",
        viewerCount = 3,
        contentDescription = "Mira's screen, live, 3 viewers",
        onClick = {},
        selected = true,
        modifier = Modifier.padding(horizontal = MumcordSpace.s4).width(232.dp),
    )
}
