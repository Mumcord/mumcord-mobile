// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.theme.tokenShadow
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * Large hold-to-talk button (`.ptt`): round and 208 dp wide, or a 72 dp high bar over the full
 * width when [wide] (96 dp with larger text when also [tall], as in the full voice view).
 * [onPressedChange] reports finger down (true) and up (false); [active]
 * shows the talking state the caller derives from it.
 *
 * Holding is not possible with TalkBack or a switch, so the accessibility action toggles
 * instead: one activation starts talking, the next one stops. [stateDescription] says which
 * state it is in.
 */
@Composable
fun MumcordTalkButton(
    label: String,
    active: Boolean,
    onPressedChange: (Boolean) -> Unit,
    stateDescription: String,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    tall: Boolean = false,
    icon: ImageVector = MumcordIcons.Mic,
) {
    val colors = MumcordTheme.colors
    val onPressed by rememberUpdatedState(onPressedChange)
    val isActive by rememberUpdatedState(active)
    val content = if (active) colors.onTalking else colors.text
    val frame = if (active) {
        Modifier
            .drawBehind {
                // Two soft rings around the button while talking: 24 dp at 10 % and 10 dp at 22 %.
                for ((spread, alpha) in listOf(24.dp to 0.10f, 10.dp to 0.22f)) {
                    val grow = spread.toPx()
                    drawRoundRect(
                        color = colors.talking.copy(alpha = alpha),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + 2 * grow, size.height + 2 * grow),
                        cornerRadius = CornerRadius(size.height / 2 + grow),
                    )
                }
            }
            .background(colors.talking, CircleShape)
    } else {
        Modifier.tokenShadow(MumcordTheme.shadows.glow, CircleShape).background(accentGradient(), CircleShape)
    }
    Box(
        modifier = modifier
            .then(if (wide) Modifier.fillMaxWidth().height(if (tall) 96.dp else 72.dp) else Modifier.size(208.dp))
            .then(frame)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    onPressed(true)
                    waitForUpOrCancellation()
                    onPressed(false)
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.stateDescription = stateDescription
                onClick {
                    onPressed(!isActive)
                    true
                }
            }
            .padding(if (wide) 3.dp else 5.dp),
    ) {
        val inner = Modifier.fillMaxSize().background(if (active) colors.talking else colors.bgElevated, CircleShape)
        if (wide) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    if (tall) MumcordSpace.s3 else MumcordSpace.s2,
                    Alignment.CenterHorizontally,
                ),
                verticalAlignment = Alignment.CenterVertically,
                modifier = inner,
            ) {
                val style = MumcordTheme.typography.talkButton
                MumcordIcon(icon, contentDescription = null, tint = content, size = if (tall) MumcordSize.iconLg else MumcordSize.icon)
                LineText(label, if (tall) style.copy(fontSize = 22.sp) else style, content)
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MumcordSpace.s2, Alignment.CenterVertically),
                modifier = inner,
            ) {
                MumcordIcon(icon, contentDescription = null, tint = content, size = MumcordSize.iconXl)
                LineText(label, MumcordTheme.typography.talkButton, content)
            }
        }
    }
}

@Preview(name = "Talk button dark")
@Composable
private fun TalkButtonDarkPreview() {
    PreviewFrame(darkTheme = true) { TalkButtonSample() }
}

@Preview(name = "Talk button light")
@Composable
private fun TalkButtonLightPreview() {
    PreviewFrame(darkTheme = false) { TalkButtonSample() }
}

@Composable
internal fun TalkButtonSample() {
    val padded = Modifier.padding(horizontal = MumcordSpace.s5, vertical = MumcordSpace.s5)
    MumcordTalkButton("Hold to talk", active = false, onPressedChange = {}, stateDescription = "Not talking", modifier = padded)
    MumcordTalkButton("Talking", active = true, onPressedChange = {}, stateDescription = "Talking", modifier = padded)
    MumcordTalkButton("Hold to talk", false, {}, "Not talking", modifier = padded, wide = true)
}
