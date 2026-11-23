// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * The chip in the header of the voice view that names the audio output and opens the choice
 * (`.vchip`): icon, name, chevron. The pill is drawn at the mockup's height inside a touch
 * target of the platform's size.
 */
@Composable
fun MumcordOutputChip(
    text: String,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MumcordTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minHeight = MumcordSize.touchTargetAndroid)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .defaultMinSize(minHeight = MumcordSize.touchVisual)
                .background(colors.bgElevated, CircleShape)
                .border(1.dp, colors.borderStrong, CircleShape)
                .padding(horizontal = MumcordSpace.s3),
        ) {
            MumcordIcon(icon, contentDescription = null, tint = colors.text, size = MumcordSize.iconSm)
            LineText(text, MumcordTheme.typography.smallStrong, colors.text)
            MumcordIcon(MumcordIcons.ChevronDown, contentDescription = null, tint = colors.text, size = MumcordSize.iconSm)
        }
    }
}

/**
 * One choice of a list in which exactly one is chosen (`.row.opt`): the chosen one is tinted
 * and gets a check mark. An [unavailable] choice is shown faded and cannot be picked.
 *
 * @param subtitle a second line, such as the kind of device or why it is unavailable.
 */
@Composable
fun MumcordOptionRow(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    unavailable: Boolean = false,
    divider: Boolean = false,
) {
    MumcordListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        divider = divider,
        selected = selected,
        trailing = if (selected) {
            { MumcordIcon(MumcordIcons.Check, contentDescription = null, tint = MumcordTheme.colors.accentText) }
        } else {
            null
        },
        modifier = modifier
            .defaultMinSize(minHeight = 64.dp)
            .alpha(if (unavailable) 0.55f else 1f)
            .selectable(selected = selected, enabled = !unavailable, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { },
    )
}

/** A quiet remark under a list (`.note`): a small info icon and one or two lines of text. */
@Composable
fun MumcordNote(text: String, modifier: Modifier = Modifier) {
    val colors = MumcordTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = MumcordSpace.s1),
    ) {
        MumcordIcon(MumcordIcons.Info, contentDescription = null, tint = colors.textMuted, size = MumcordSize.iconSm)
        BasicText(text, style = MumcordTheme.typography.small.copy(color = colors.textMuted))
    }
}

@Preview(name = "Audio output dark")
@Composable
private fun AudioOutputDarkPreview() {
    PreviewFrame(darkTheme = true) { AudioOutputSample() }
}

@Preview(name = "Audio output light")
@Composable
private fun AudioOutputLightPreview() {
    PreviewFrame(darkTheme = false) { AudioOutputSample() }
}

@Composable
internal fun AudioOutputSample() {
    MumcordOutputChip("Speaker", MumcordIcons.Speaker, "Audio output: Speaker", {}, Modifier.padding(MumcordSpace.s4))
    MumcordList {
        MumcordOptionRow("Speaker", MumcordIcons.Speaker, selected = true, onClick = {})
        MumcordOptionRow("Lea's Buds", MumcordIcons.Bluetooth, selected = false, onClick = {}, subtitle = "Bluetooth", divider = true)
        MumcordOptionRow(
            "Wired headset",
            MumcordIcons.Headphones,
            selected = false,
            onClick = {},
            subtitle = "Not connected",
            unavailable = true,
            divider = true,
        )
    }
    MumcordNote("A connected headset is used automatically", Modifier.padding(MumcordSpace.s4))
}
