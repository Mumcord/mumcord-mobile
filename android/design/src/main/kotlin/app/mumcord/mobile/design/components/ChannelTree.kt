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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.theme.tokenShadow
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace

/** Container of the channel tree (`.tree`). */
@Composable
fun MumcordTree(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = MumcordSpace.s2, top = MumcordSpace.s1, end = MumcordSpace.s2, bottom = MumcordSpace.s3),
        content = content,
    )
}

/** The indented children of a channel: people, stream tiles and sub-channels (`.channel-body`). */
@Composable
fun MumcordChannelBody(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth().padding(start = 28.dp), content = content)
}

/**
 * Channel row (`.channel`).
 *
 * @param expanded true or false for a channel that can be folded, null for one without children
 * (the chevron's space stays empty so that names line up).
 * @param current the channel the user is in: highlighted, with [meta] such as "you are here".
 * @param empty nobody is inside: the name is muted.
 * @param lockedDescription set for a password-protected channel; read by TalkBack for the lock.
 * @param toggleDescription what TalkBack reads for the chevron, such as "Collapse Lobby".
 */
@Composable
fun MumcordChannelRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = MumcordIcons.Hash,
    expanded: Boolean? = null,
    onToggleExpanded: () -> Unit = {},
    toggleDescription: String = "",
    memberCount: Int? = null,
    current: Boolean = false,
    empty: Boolean = false,
    meta: String? = null,
    lockedDescription: String? = null,
) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.md)
    val highlight = if (current) {
        Modifier.background(colors.bgSelected, shape).border(2.dp, colors.talking, shape)
    } else {
        Modifier
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MumcordSize.touchVisual)
            .clip(shape)
            .then(highlight)
            .clickable(onClick = onClick)
            .semantics { selected = current }
            // 2 dp of each side is the border of the current channel (transparent otherwise).
            .padding(start = 6.dp, end = 10.dp),
    ) {
        ChannelRowStart(name, icon, expanded, onToggleExpanded, toggleDescription, memberCount, current, empty, Modifier.weight(1f))
        if (meta != null) {
            LineText(
                text = meta,
                style = MumcordTheme.typography.small.copy(
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = colors.textMuted,
            )
        }
        if (lockedDescription != null) {
            MumcordIcon(MumcordIcons.Lock, lockedDescription, colors.textMuted, size = MumcordSize.iconSm)
        }
    }
}

/** Chevron, channel icon, name and member count; the name gives way when the row is too narrow. */
@Composable
private fun ChannelRowStart(
    name: String,
    icon: ImageVector,
    expanded: Boolean?,
    onToggleExpanded: () -> Unit,
    toggleDescription: String,
    memberCount: Int?,
    current: Boolean,
    empty: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MumcordTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        if (expanded != null) {
            MumcordIcon(
                icon = MumcordIcons.ChevronDown,
                contentDescription = toggleDescription,
                tint = colors.textMuted,
                size = 20.dp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onToggleExpanded)
                    .rotate(if (expanded) 0f else -90f),
            )
        } else {
            Spacer(Modifier.size(20.dp))
        }
        MumcordIcon(
            icon = icon,
            contentDescription = null,
            tint = if (current) colors.talking else colors.textMuted,
            size = MumcordSize.iconMd,
        )
        LineText(
            text = name,
            style = if (current) MumcordTheme.typography.channelStrong else MumcordTheme.typography.channel,
            color = if (empty) colors.textMuted else colors.text,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (memberCount != null) {
            LineText(
                text = memberCount.toString(),
                style = MumcordTheme.typography.small.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = colors.textMuted,
            )
        }
    }
}

enum class MumcordAvatarSize(internal val diameter: Dp, internal val fontSize: Int) {
    Small(24.dp, 11),
    Medium(32.dp, 14),
    Large(40.dp, 16),
    ExtraLarge(88.dp, 32),
}

/**
 * Round avatar with initials (`.avatar`). [colorSlot] picks one of the eight avatar colours;
 * [talking] adds the green ring and glow. The ring is drawn outside the avatar and does not
 * change its size.
 */
@Composable
fun MumcordAvatar(
    initials: String,
    colorSlot: Int,
    modifier: Modifier = Modifier,
    size: MumcordAvatarSize = MumcordAvatarSize.Medium,
    talking: Boolean = false,
) {
    val colors = MumcordTheme.colors
    val gap = colors.bgSidebar
    val ring = if (talking) {
        Modifier
            .drawBehind {
                // A 2 dp gap in the screen colour, then a 2 dp ring, both outside the avatar.
                val outer = this.size.minDimension / 2 + 4.dp.toPx()
                drawCircle(colors.talking, radius = outer - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                drawCircle(gap, radius = outer - 3.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
    } else {
        Modifier
    }
    val glow = if (talking) Modifier.tokenShadow(MumcordTheme.shadows.talking, CircleShape) else Modifier
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size.diameter)
            .then(glow)
            .then(ring)
            .background(colors.avatar(colorSlot), CircleShape)
            .clearAndSetSemantics {},
    ) {
        LineText(
            text = initials,
            style = MumcordTheme.typography.smallStrong.copy(fontSize = size.fontSize.sp),
            color = colors.avatarText,
        )
    }
}

enum class MumcordStatusTone { Muted, Live, Off }

/** A state icon at the end of a person row: muted, deafened, sharing the screen and so on. */
@Immutable
data class MumcordPersonStatus(
    val icon: ImageVector,
    val contentDescription: String,
    val tone: MumcordStatusTone = MumcordStatusTone.Muted,
)

/**
 * Person row (`.person`): avatar, name, an optional [note] such as "(you)", and state icons at
 * the end. [talkingDescription] is read by TalkBack while the person talks.
 */
@Composable
fun MumcordPersonRow(
    name: String,
    initials: String,
    colorSlot: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    talking: Boolean = false,
    talkingDescription: String = "",
    locallyMuted: Boolean = false,
    note: String? = null,
    status: List<MumcordPersonStatus> = emptyList(),
) {
    val colors = MumcordTheme.colors
    val nameColor = when {
        locallyMuted -> colors.localMuted
        talking -> colors.text
        else -> colors.textSecondary
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MumcordSize.touchVisual)
            .clip(RoundedCornerShape(MumcordRadius.md))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                if (talking) stateDescription = talkingDescription
            }
            .padding(start = 6.dp, end = MumcordSpace.s2),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            MumcordAvatar(initials, colorSlot, talking = talking)
            LineText(
                text = name,
                style = if (talking) MumcordTheme.typography.bodyStrong else MumcordTheme.typography.body,
                color = nameColor,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (note != null) LineText(note, MumcordTheme.typography.small, colors.textMuted)
        }
        if (status.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2)) {
                status.forEach {
                    val tint: Color = when (it.tone) {
                        MumcordStatusTone.Muted -> colors.textMuted
                        MumcordStatusTone.Live -> colors.accentText
                        MumcordStatusTone.Off -> colors.danger
                    }
                    MumcordIcon(it.icon, it.contentDescription, tint, size = MumcordSize.iconMd)
                }
            }
        }
    }
}

@Preview(name = "Channel tree dark")
@Composable
private fun ChannelTreeDarkPreview() {
    PreviewFrame(darkTheme = true) { ChannelTreeSample() }
}

@Preview(name = "Channel tree light")
@Composable
private fun ChannelTreeLightPreview() {
    PreviewFrame(darkTheme = false) { ChannelTreeSample() }
}

@Composable
internal fun ChannelTreeSample() {
    MumcordTree {
        MumcordChannelRow(
            name = "Lobby",
            onClick = {},
            icon = MumcordIcons.Volume,
            expanded = true,
            toggleDescription = "Collapse Lobby",
            memberCount = 2,
            current = true,
            meta = "you are here",
        )
        MumcordChannelBody {
            MumcordPersonRow("Anna", "A", 4, onClick = {})
            MumcordPersonRow("Ben", "B", 8, onClick = {}, talking = true, talkingDescription = "Talking")
            MumcordPersonRow(
                name = "Jonas",
                initials = "J",
                colorSlot = 3,
                onClick = {},
                locallyMuted = true,
                note = "(muted for you)",
                status = listOf(MumcordPersonStatus(MumcordIcons.MicOff, "Muted", MumcordStatusTone.Off)),
            )
        }
        MumcordChannelRow("Gaming", onClick = {}, expanded = false, toggleDescription = "Expand Gaming", memberCount = 2)
        MumcordChannelRow("Music", onClick = {}, empty = true, lockedDescription = "Password protected")
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3, alignment = Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = MumcordSpace.s4),
    ) {
        MumcordAvatarSize.entries.forEach { MumcordAvatar("L", colorSlot = it.ordinal + 1, size = it) }
    }
}
