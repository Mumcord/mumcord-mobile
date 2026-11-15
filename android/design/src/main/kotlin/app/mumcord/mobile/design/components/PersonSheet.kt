// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * Head of the person sheet (`.phead`): large avatar, name, the channel the person is in and
 * the state icons at the end.
 */
@Composable
fun MumcordPersonHeader(
    name: String,
    initials: String,
    colorSlot: Int,
    channelName: String,
    modifier: Modifier = Modifier,
    status: List<MumcordPersonStatus> = emptyList(),
) {
    val colors = MumcordTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(vertical = MumcordSpace.s1),
    ) {
        MumcordAvatar(initials, colorSlot, size = MumcordAvatarSize.Sheet)
        Column(Modifier.weight(1f)) {
            LineText(name, MumcordTheme.typography.title, colors.text, Modifier.semantics { heading() })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MumcordIcon(MumcordIcons.Hash, contentDescription = null, tint = colors.textMuted, size = MumcordSize.iconSm)
                LineText(channelName, MumcordTheme.typography.small, colors.textMuted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2)) {
            status.forEach { MumcordIcon(it.icon, it.contentDescription, colors.localMuted, size = MumcordSize.iconMd) }
        }
    }
}

/**
 * A list row with a slider under its title (`.row.vol`), such as "Volume for you" with the
 * current value as [valueText] at the end. [value] goes from 0 to 1.
 */
@Composable
fun MumcordSliderRow(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val colors = MumcordTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = MumcordSpace.s4).padding(top = MumcordSpace.s2)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = MumcordSpace.s2),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(24.dp)) {
                MumcordIcon(icon, contentDescription = null, tint = colors.textSecondary)
            }
            LineText(title, MumcordTheme.typography.bodyStrong, colors.text, Modifier.weight(1f))
            MumcordListRowValue(valueText)
        }
        MumcordSlider(value, onValueChange, contentDescription = title)
    }
}

/**
 * A calm, informative line for something the app cannot show, such as "Jonas shared a file ·
 * available in the desktop app". It is not a control.
 */
@Composable
fun MumcordHintLine(text: String, modifier: Modifier = Modifier) {
    val colors = MumcordTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(horizontal = MumcordSpace.s4, vertical = MumcordSpace.s1),
    ) {
        MumcordIcon(MumcordIcons.Info, contentDescription = null, tint = colors.textMuted, size = MumcordSize.iconSm)
        LineText(text, MumcordTheme.typography.small, colors.textMuted, Modifier.weight(1f))
    }
}
