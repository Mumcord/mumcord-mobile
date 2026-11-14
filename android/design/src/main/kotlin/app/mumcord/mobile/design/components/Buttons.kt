// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.theme.tokenShadow
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace

private const val DISABLED_ALPHA = 0.5f

enum class MumcordButtonStyle { Primary, Secondary, Danger, Ghost }

/**
 * Text button (`.btn`): [MumcordButtonStyle.Primary] for the main action of a screen, Secondary
 * with an outline, Danger for destructive actions, Ghost for quiet text actions. Make it as wide
 * as its container with `Modifier.fillMaxWidth()`.
 */
@Composable
fun MumcordButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MumcordButtonStyle = MumcordButtonStyle.Primary,
    small: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = MumcordTheme.colors
    val background = when (style) {
        MumcordButtonStyle.Primary -> colors.primaryBg
        MumcordButtonStyle.Danger -> colors.danger
        MumcordButtonStyle.Secondary, MumcordButtonStyle.Ghost -> Color.Transparent
    }
    val content = when (style) {
        MumcordButtonStyle.Primary -> colors.primaryText
        MumcordButtonStyle.Danger -> colors.onAccent
        MumcordButtonStyle.Secondary -> colors.text
        MumcordButtonStyle.Ghost -> colors.accentText
    }
    val horizontalPadding = when {
        style == MumcordButtonStyle.Ghost -> MumcordSpace.s3
        small -> MumcordSpace.s4
        else -> MumcordSpace.s5
    }
    val glow = if (style == MumcordButtonStyle.Primary && enabled) {
        Modifier.tokenShadow(MumcordTheme.shadows.primaryGlow, CircleShape)
    } else {
        Modifier
    }
    val outline = if (style == MumcordButtonStyle.Secondary) {
        Modifier.border(1.dp, colors.borderStrong, CircleShape)
    } else {
        Modifier
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .then(glow)
            .defaultMinSize(minHeight = if (small) MumcordSize.touchVisual else 48.dp)
            .clip(CircleShape)
            .background(background)
            .then(outline)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding),
    ) {
        if (icon != null) MumcordIcon(icon, contentDescription = null, tint = content)
        LineText(
            text = text,
            style = if (small) MumcordTheme.typography.smallStrong else MumcordTheme.typography.bodyStrong,
            color = content,
        )
    }
}

/**
 * Looks of the round icon button (`.iconbtn`): Plain without a background, Tonal on a soft
 * background, Outline with a ring, On for a switched-on state, Off for a switched-off state
 * (muted, deafened), Danger for leaving or ending, Accent for the main action (send).
 */
enum class MumcordIconButtonStyle { Plain, Tonal, Outline, On, Off, Danger, Accent }

/**
 * Round icon button. It is drawn 44 dp wide as in the mockups (56 dp when [large]); Compose
 * widens the area that reacts to touch to 48 dp around it. [contentDescription] is what
 * TalkBack reads and must name the action.
 */
@Composable
fun MumcordIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MumcordIconButtonStyle = MumcordIconButtonStyle.Plain,
    large: Boolean = false,
    iconSize: Dp = MumcordSize.icon,
    enabled: Boolean = true,
) {
    val colors = MumcordTheme.colors
    val background = when (style) {
        MumcordIconButtonStyle.Plain, MumcordIconButtonStyle.Outline -> Color.Transparent
        MumcordIconButtonStyle.Tonal -> colors.bgHover
        MumcordIconButtonStyle.On -> colors.accent
        MumcordIconButtonStyle.Off -> colors.danger.copy(alpha = MumcordTheme.opacities.offTint)
        MumcordIconButtonStyle.Danger -> colors.danger
        MumcordIconButtonStyle.Accent -> colors.primaryBg
    }
    val tint = when (style) {
        MumcordIconButtonStyle.Plain -> colors.textSecondary
        MumcordIconButtonStyle.Tonal, MumcordIconButtonStyle.Outline -> colors.text
        MumcordIconButtonStyle.On, MumcordIconButtonStyle.Danger -> colors.onAccent
        MumcordIconButtonStyle.Off -> colors.danger
        MumcordIconButtonStyle.Accent -> colors.primaryText
    }
    val outline = if (style == MumcordIconButtonStyle.Outline) {
        Modifier.border(1.dp, colors.borderStrong, CircleShape)
    } else {
        Modifier
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .size(if (large) 56.dp else MumcordSize.touchVisual)
            .clip(CircleShape)
            .background(background)
            .then(outline)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        MumcordIcon(icon, contentDescription = contentDescription, tint = tint, size = iconSize)
    }
}

@Preview(name = "Buttons dark")
@Composable
private fun ButtonsDarkPreview() {
    PreviewFrame(darkTheme = true) { ButtonsSample() }
}

@Preview(name = "Buttons light")
@Composable
private fun ButtonsLightPreview() {
    PreviewFrame(darkTheme = false) { ButtonsSample() }
}

@Composable
internal fun ButtonsSample() {
    val padded = Modifier.padding(horizontal = MumcordSpace.s4)
    MumcordButton("Connect", onClick = {}, modifier = padded.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2), modifier = padded) {
        MumcordButton("Cancel", onClick = {}, style = MumcordButtonStyle.Secondary)
        MumcordButton("Remove", onClick = {}, style = MumcordButtonStyle.Danger, icon = MumcordIcons.Trash)
        MumcordButton("Skip", onClick = {}, style = MumcordButtonStyle.Ghost)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2), modifier = padded) {
        MumcordButton("Small", onClick = {}, small = true)
        MumcordButton("Disabled", onClick = {}, enabled = false)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s1), modifier = padded) {
        MumcordIconButtonStyle.entries.forEach { style ->
            MumcordIconButton(MumcordIcons.Mic, contentDescription = style.name, onClick = {}, style = style)
        }
    }
    MumcordIconButton(
        icon = MumcordIcons.PhoneOff,
        contentDescription = "Leave",
        onClick = {},
        style = MumcordIconButtonStyle.Danger,
        large = true,
        modifier = padded,
    )
}
