// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.theme.tokenShadow
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.design.tokens.ShadowToken

/**
 * Switch (`.toggle`). The track is 52 x 32 dp as in the mockups; Compose widens the area that
 * reacts to touch to 48 dp. [contentDescription] names the setting when no visible label is
 * merged with the switch.
 */
@Composable
fun MumcordToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val colors = MumcordTheme.colors
    Box(
        modifier = modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(CircleShape)
            .background(if (checked) colors.accent else colors.bgHover)
            .border(2.dp, if (checked) colors.accent else colors.borderStrong, CircleShape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        Box(
            Modifier
                // 2 dp border plus the 4 dp (off) or 24 dp (on) of the mockup.
                .offset(x = if (checked) 26.dp else 6.dp, y = 6.dp)
                .size(20.dp)
                .background(if (checked) colors.onAccent else colors.borderStrong, CircleShape),
        )
    }
}

/**
 * Slider (`.slider`) for a [value] from 0 to 1, such as a volume. [contentDescription] names
 * what it changes. TalkBack and keyboards change it in steps through the accessibility action.
 */
@Composable
fun MumcordSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = MumcordTheme.colors
    val fraction = value.coerceIn(0f, 1f)
    val onChange by rememberUpdatedState(onValueChange)
    val thumbShadow = ShadowToken(y = 2.dp, blur = 6.dp, spread = 0.dp, color = colors.sliderThumbShadow)
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .defaultMinSize(minWidth = 80.dp)
            .fillMaxWidth()
            .height(MumcordSize.touchVisual)
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { target ->
                    onChange(target.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(colors.bgHover, cornerRadius = radius)
            // The filled part ends square under the thumb, like the CSS gradient.
            val track = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius)) }
            clipPath(track) {
                drawRect(colors.accent, size = Size(size.width * fraction, size.height))
            }
            val inset = 0.5.dp.toPx()
            drawRoundRect(
                color = colors.border,
                topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                cornerRadius = radius,
                style = Stroke(1.dp.toPx()),
            )
        }
        Thumb(fraction, colors.text, thumbShadow)
    }
}

@Composable
private fun Thumb(fraction: Float, color: Color, shadow: ShadowToken) {
    Layout(
        content = {
            Box(Modifier.size(26.dp).tokenShadow(shadow, CircleShape).background(color, CircleShape))
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val thumb = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, thumb.height) {
            // Centred on the value, so it hangs over the ends of the track at 0 and 1.
            thumb.place((constraints.maxWidth * fraction - thumb.width / 2f).toInt(), 0)
        }
    }
}

/** Chip (`.chip`) for a filter or a choice; [selected] fills it with the accent colour. */
@Composable
fun MumcordChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val colors = MumcordTheme.colors
    val content = if (selected) colors.onAccent else colors.textSecondary
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (selected) colors.accent else Color.Transparent)
            .border(1.dp, if (selected) colors.accent else colors.borderStrong, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        if (icon != null) MumcordIcon(icon, contentDescription = null, tint = content, size = MumcordSize.iconSm)
        LineText(text, MumcordTheme.typography.smallStrong, content)
    }
}

/** Row of chips (`.chips`) that scrolls sideways when the chips do not fit. */
@Composable
fun MumcordChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MumcordSpace.s4, vertical = MumcordSpace.s1)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, option ->
            MumcordChip(option, selected = index == selectedIndex, onClick = { onSelect(index) })
        }
    }
}

/**
 * Segmented control: a choice between a few options that share the width equally. The mockups
 * draw such choices with chips, so this is a row of equally wide chips.
 */
@Composable
fun MumcordSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        modifier = modifier.fillMaxWidth().selectableGroup(),
    ) {
        options.forEachIndexed { index, option ->
            MumcordChip(
                text = option,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Preview(name = "Controls dark")
@Composable
private fun ControlsDarkPreview() {
    PreviewFrame(darkTheme = true) { ControlsSample() }
}

@Preview(name = "Controls light")
@Composable
private fun ControlsLightPreview() {
    PreviewFrame(darkTheme = false) { ControlsSample() }
}

@Composable
internal fun ControlsSample() {
    val padded = Modifier.padding(horizontal = MumcordSpace.s4)
    Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3), modifier = padded) {
        MumcordToggle(checked = false, onCheckedChange = {}, contentDescription = "Noise suppression")
        MumcordToggle(checked = true, onCheckedChange = {}, contentDescription = "Echo cancellation")
    }
    MumcordSlider(value = 0.6f, onValueChange = {}, contentDescription = "Volume", modifier = padded)
    MumcordChipRow(listOf("All", "Joins", "Messages", "Moderation"), selectedIndex = 0, onSelect = {})
    MumcordChip("Favourites", selected = false, onClick = {}, icon = MumcordIcons.Star, modifier = padded)
    MumcordSegmentedControl(listOf("System", "Dark", "Light"), selectedIndex = 1, onSelect = {}, modifier = padded)
}
