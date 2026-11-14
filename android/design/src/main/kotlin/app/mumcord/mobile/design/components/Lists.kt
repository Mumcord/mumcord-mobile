// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * Section header above a list (`.section`): small capitals, with an optional text action at the
 * end such as "Edit".
 */
@Composable
fun MumcordSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = MumcordTheme.colors
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = MumcordSpace.s4, top = MumcordSpace.s4, end = MumcordSpace.s4, bottom = MumcordSpace.s2),
    ) {
        LineText(
            text = title.uppercase(),
            style = MumcordTheme.typography.section,
            color = colors.textMuted,
            modifier = Modifier.weight(1f, fill = false).semantics { heading() },
        )
        if (action != null) {
            LineText(
                text = action,
                style = MumcordTheme.typography.smallStrong,
                color = colors.accentText,
                modifier = Modifier.clickable(role = Role.Button, onClick = onAction),
            )
        }
    }
}

/**
 * Card that groups list rows (`.list`), with a 16 dp margin at the sides. [flat] drops the card
 * and the margin (`.list--flat`). Inside a bottom sheet the card has no margin and uses the hover
 * colour as [background].
 */
@Composable
fun MumcordList(
    modifier: Modifier = Modifier,
    flat: Boolean = false,
    horizontalMargin: Dp = MumcordSpace.s4,
    background: Color = MumcordTheme.colors.bgElevated,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(MumcordRadius.list)
    val card = if (flat) {
        Modifier
    } else {
        Modifier
            .padding(horizontal = horizontalMargin)
            .clip(shape)
            .background(background)
            .border(1.dp, MumcordTheme.colors.border, shape)
    }
    Column(modifier = modifier.fillMaxWidth().then(card), content = content)
}

/**
 * Row of a list (`.row`). Every row but the first of a list sets [divider] to get the line
 * above it.
 *
 * @param leading replaces [icon] with other content, such as an avatar or a server tile.
 * @param trailing content at the end: a value, a chevron, a toggle.
 * @param tall the taller variant for rows with a tile.
 * @param danger destructive action: title and icon in the danger colour.
 */
@Composable
fun MumcordListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    divider: Boolean = false,
    tall: Boolean = false,
    danger: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = MumcordTheme.colors
    val topLine = if (divider) {
        Modifier.drawBehind {
            val y = 0.5.dp.toPx()
            drawLine(colors.border, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
    } else {
        Modifier
    }
    val vertical = if (tall) MumcordSpace.s3 else MumcordSpace.s2
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (tall) 76.dp else 56.dp)
            .background(if (selected) colors.bgSelected else Color.Transparent)
            .then(topLine)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = MumcordSpace.s4)
            // The divider line is part of the row's height, as with the CSS border.
            .padding(top = if (divider) vertical + 1.dp else vertical, bottom = vertical),
    ) {
        if (leading != null) {
            leading()
        } else if (icon != null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(24.dp)) {
                MumcordIcon(icon, contentDescription = null, tint = if (danger) colors.danger else colors.textSecondary)
            }
        }
        Column(Modifier.weight(1f)) {
            LineText(title, MumcordTheme.typography.bodyStrong, if (danger) colors.danger else colors.text)
            if (subtitle != null) LineText(subtitle, MumcordTheme.typography.small, colors.textMuted)
        }
        if (trailing != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
                verticalAlignment = Alignment.CenterVertically,
                content = trailing,
            )
        }
    }
}

/** Small muted text for the end of a list row, such as the current value of a setting. */
@Composable
fun MumcordListRowValue(text: String, modifier: Modifier = Modifier) {
    LineText(text, MumcordTheme.typography.small, MumcordTheme.colors.textMuted, modifier)
}

@Preview(name = "Lists dark")
@Composable
private fun ListsDarkPreview() {
    PreviewFrame(darkTheme = true) { ListsSample() }
}

@Preview(name = "Lists light")
@Composable
private fun ListsLightPreview() {
    PreviewFrame(darkTheme = false) { ListsSample() }
}

@Composable
internal fun ListsSample() {
    val colors = MumcordTheme.colors
    MumcordSectionHeader("Voice", action = "Edit")
    MumcordList {
        MumcordListRow(
            title = "Talk mode",
            icon = MumcordIcons.Mic,
            onClick = {},
            trailing = {
                MumcordListRowValue("Hold to talk")
                MumcordIcon(MumcordIcons.ChevronRight, contentDescription = null, tint = colors.textMuted, size = 18.dp)
            },
        )
        MumcordListRow(
            title = "Noise suppression",
            subtitle = "Removes background noise",
            icon = MumcordIcons.Activity,
            divider = true,
            selected = true,
        )
        MumcordListRow(
            title = "Nordlicht",
            subtitle = "nordlicht.example · 5 online",
            leading = { MumcordTile("NO", brand = true) },
            divider = true,
            tall = true,
            onClick = {},
        )
        MumcordListRow(title = "Remove server", icon = MumcordIcons.Trash, divider = true, danger = true, onClick = {})
    }
}
