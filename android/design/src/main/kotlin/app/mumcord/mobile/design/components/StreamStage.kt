// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * Round button on a stream's dark stage (`.stage .iconbtn` of the full screen, `.pip__btn` of
 * the mini window): a white icon on a light veil, or on the live red for [danger] ("Stop
 * watching"). The stage is dark in both themes. [size] is the visible circle; the touch target
 * is never smaller than 48 dp.
 */
@Composable
fun MumcordStageButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    size: Dp = 48.dp,
    iconSize: Dp = MumcordSize.icon,
) {
    val colors = MumcordTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(maxOf(size, 48.dp))
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .background(if (danger) colors.live else colors.stageControl, CircleShape),
        ) {
            MumcordIcon(icon, contentDescription = null, tint = colors.mediaText, size = iconSize)
        }
    }
}

/** A hint on the stage (`.hint`), such as "Rotate for landscape": an icon and a short text in a pill. */
@Composable
fun MumcordStageHint(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    val colors = MumcordTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(colors.stageControl, RoundedCornerShape(percent = 50))
            .padding(horizontal = 14.dp, vertical = MumcordSpace.s2),
    ) {
        MumcordIcon(icon, contentDescription = null, tint = colors.stageTextSecondary, size = MumcordSize.iconMd)
        LineText(text, MumcordTheme.typography.small, colors.stageTextSecondary)
    }
}

@Preview(name = "Stream stage dark")
@Composable
private fun StreamStageDarkPreview() {
    PreviewFrame(darkTheme = true) { StreamStageSample() }
}

@Preview(name = "Stream stage light")
@Composable
private fun StreamStageLightPreview() {
    PreviewFrame(darkTheme = false) { StreamStageSample() }
}

@Composable
internal fun StreamStageSample() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.background(MumcordTheme.colors.stageBg).padding(MumcordSpace.s3),
    ) {
        MumcordStageButton(MumcordIcons.ChevronDown, "Collapse", onClick = {})
        MumcordStageButton(MumcordIcons.X, "Stop watching", onClick = {}, danger = true)
        MumcordStageHint(MumcordIcons.Maximize, "Rotate for landscape")
    }
}
