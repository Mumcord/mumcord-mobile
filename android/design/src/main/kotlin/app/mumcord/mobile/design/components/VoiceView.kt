// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * A person in the full voice view (`.vtile`): a 72 dp avatar with the name below. [talking]
 * draws the green ring and makes the name strong; [mutedDescription] puts the muted badge on
 * the avatar. TalkBack reads the tile as one item: the name and, as its state, talking and
 * muted.
 */
@Composable
fun MumcordVoiceTile(
    name: String,
    initials: String,
    colorSlot: Int,
    talking: Boolean,
    talkingDescription: String,
    modifier: Modifier = Modifier,
    mutedDescription: String? = null,
) {
    val colors = MumcordTheme.colors
    val state = listOfNotNull(talkingDescription.takeIf { talking }, mutedDescription).joinToString()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        modifier = modifier.clearAndSetSemantics {
            contentDescription = name
            if (state.isNotEmpty()) stateDescription = state
        },
    ) {
        Box {
            val ring = if (talking) {
                Modifier.drawBehind {
                    // A 3 dp gap in the screen colour, then a 3 dp ring, both outside the avatar.
                    val radius = size.minDimension / 2 + 4.5.dp.toPx()
                    drawCircle(colors.talking, radius = radius, style = Stroke(3.dp.toPx()))
                }
            } else {
                Modifier
            }
            MumcordAvatar(initials, colorSlot, modifier = ring, size = MumcordAvatarSize.Tile)
            if (mutedDescription != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        // The badge sits on the lower right edge of the avatar.
                        .offset(x = 27.dp, y = 48.dp)
                        .size(26.dp)
                        .background(colors.bgChat, CircleShape)
                        .padding(2.dp)
                        .background(colors.bgElevated, CircleShape),
                ) {
                    MumcordIcon(MumcordIcons.MicOff, contentDescription = null, tint = colors.localMuted, size = MumcordSize.iconSm)
                }
            }
        }
        LineText(
            text = name,
            style = if (talking) MumcordTheme.typography.bodyStrong else MumcordTheme.typography.body,
            color = if (talking) colors.text else colors.textSecondary,
        )
    }
}

/**
 * A large round action of the full voice view with its name below (`.vaction`): a 64 dp
 * [MumcordIconButton] and a label. [contentDescription] is what TalkBack reads for the button;
 * the visible [label] is not read a second time.
 */
@Composable
fun MumcordVoiceAction(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MumcordIconButtonStyle = MumcordIconButtonStyle.Tonal,
    enabled: Boolean = true,
) {
    val colors = MumcordTheme.colors
    val danger = style == MumcordIconButtonStyle.Danger
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        val tint = when (style) {
            MumcordIconButtonStyle.Off -> colors.danger
            MumcordIconButtonStyle.Danger -> colors.onAccent
            else -> colors.text
        }
        val background = when (style) {
            MumcordIconButtonStyle.Off -> colors.danger.copy(alpha = MumcordTheme.opacities.offTint)
            MumcordIconButtonStyle.Danger -> colors.danger
            else -> colors.bgHover
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .size(64.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        ) {
            MumcordIcon(icon, contentDescription = contentDescription, tint = tint, size = MumcordSize.iconLg)
        }
        BasicText(
            text = label,
            style = MumcordTheme.typography.smallStrong.copy(
                color = if (danger) colors.danger else colors.textSecondary,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * A choice between two or three options in one pill (`.seg`): the options share the width and
 * the selected one is filled with the accent colour.
 */
@Composable
fun MumcordSegmentedPill(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MumcordTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgElevated, CircleShape)
            .border(1.dp, colors.border, CircleShape)
            .padding(4.dp)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 44.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.accent else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(horizontal = MumcordSpace.s2),
            ) {
                BasicText(
                    text = option,
                    style = MumcordTheme.typography.smallStrong.copy(
                        color = if (selected) colors.onAccent else colors.textSecondary,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

/**
 * Level meter of the microphone: a track that fills from the start with the talking colour.
 * [level] is read while drawing, so a new level redraws the bar and nothing else.
 * [contentDescription] names the meter for TalkBack; the moving level itself is not announced.
 */
@Composable
fun MumcordLevelMeter(
    level: () -> Float,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = MumcordTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(colors.bgHover)
            .semantics { this.contentDescription = contentDescription }
            .drawBehind {
                val width = size.width * level().coerceIn(0f, 1f)
                if (width > 0f) {
                    drawRoundRect(
                        color = colors.talking,
                        size = size.copy(width = width.coerceAtLeast(size.height)),
                        cornerRadius = CornerRadius(size.height / 2),
                    )
                }
            },
    )
}

@Preview(name = "Voice view parts dark")
@Composable
private fun VoiceViewDarkPreview() {
    PreviewFrame(darkTheme = true) { VoiceViewSample() }
}

@Preview(name = "Voice view parts light")
@Composable
private fun VoiceViewLightPreview() {
    PreviewFrame(darkTheme = false) { VoiceViewSample() }
}

@Composable
internal fun VoiceViewSample() {
    val padded = Modifier.padding(MumcordSpace.s4)
    Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s5), modifier = padded) {
        MumcordVoiceTile("Anna", "A", 4, talking = false, talkingDescription = "Talking")
        MumcordVoiceTile("Ben", "B", 8, talking = true, talkingDescription = "Talking")
        MumcordVoiceTile("Jonas", "J", 3, talking = false, talkingDescription = "Talking", mutedDescription = "Muted")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s5), modifier = padded) {
        MumcordVoiceAction(MumcordIcons.Mic, "Mute", "Mute microphone", {})
        MumcordVoiceAction(MumcordIcons.HeadphonesOff, "Undeafen", "Undeafen", {}, style = MumcordIconButtonStyle.Off)
        MumcordVoiceAction(MumcordIcons.PhoneOff, "Leave", "Leave server", {}, style = MumcordIconButtonStyle.Danger)
    }
    MumcordSegmentedPill(listOf("Voice activity", "Push-to-talk"), selectedIndex = 1, onSelect = {}, modifier = padded)
    MumcordLevelMeter(level = { 0.6f }, contentDescription = "Microphone level", modifier = padded)
}
