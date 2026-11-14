// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * Chat message (`.msg`): avatar, author with time, and the text. [attachment] is shown under
 * the text (an image). The text is plain: it comes from the server and is shown as it is, never
 * interpreted here.
 */
@Composable
fun MumcordMessageRow(
    author: String,
    time: String,
    text: String,
    initials: String,
    colorSlot: Int,
    modifier: Modifier = Modifier,
    attachment: (@Composable () -> Unit)? = null,
) {
    val colors = MumcordTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        verticalAlignment = Alignment.Top,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        MumcordAvatar(initials, colorSlot, size = MumcordAvatarSize.Large, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2)) {
                LineText(author, MumcordTheme.typography.bodyStrong, colors.text, Modifier.alignByBaseline().weight(1f, fill = false))
                LineText(time, MumcordTheme.typography.caption, colors.textMuted, Modifier.alignByBaseline())
            }
            BasicText(text = text, style = MumcordTheme.typography.body.copy(color = colors.text))
            if (attachment != null) {
                Box(Modifier.padding(top = MumcordSpace.s2)) { attachment() }
            }
        }
    }
}

/**
 * Text input bar at the bottom of a chat (`.inputbar`): buttons before the field ([leading],
 * such as image and GIF), the field, and buttons after it ([trailing], such as send). When it
 * is the last element of a screen, [windowInsets] keeps it clear of the system bar and the
 * keyboard.
 */
@Composable
fun MumcordInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets(),
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = MumcordTheme.colors
    val textStyle = MumcordTheme.typography.body.copy(color = colors.text)
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgChat)
            .drawBehind {
                val y = 0.5.dp.toPx()
                drawLine(colors.border, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            .windowInsetsPadding(windowInsets)
            // 1 dp of the top padding is the border line.
            .padding(start = MumcordSpace.s2, top = MumcordSpace.s2 + 1.dp, end = MumcordSpace.s2, bottom = MumcordSpace.s2),
    ) {
        leading()
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.accent),
            maxLines = 4,
            modifier = Modifier.weight(1f),
            decorationBox = { field ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .defaultMinSize(minHeight = MumcordSize.touchVisual)
                        .background(colors.bgElevated, CircleShape)
                        .border(1.dp, colors.border, CircleShape)
                        .padding(horizontal = MumcordSpace.s4),
                ) {
                    if (value.isEmpty()) {
                        LineText(placeholder, MumcordTheme.typography.body, colors.textMuted)
                    }
                    field()
                }
            },
        )
        trailing()
    }
}

@Preview(name = "Chat dark")
@Composable
private fun ChatDarkPreview() {
    PreviewFrame(darkTheme = true) { ChatSample() }
}

@Preview(name = "Chat light")
@Composable
private fun ChatLightPreview() {
    PreviewFrame(darkTheme = false) { ChatSample() }
}

@Composable
internal fun ChatSample() {
    MumcordMessageRow(
        author = "Anna",
        time = "20:14",
        text = "Anyone up for a round tonight? I can host after nine.",
        initials = "A",
        colorSlot = 4,
        modifier = Modifier.padding(horizontal = MumcordSpace.s4),
    )
    MumcordInputBar(
        value = "",
        onValueChange = {},
        placeholder = "Message Lobby…",
        leading = {
            MumcordIconButton(MumcordIcons.Image, contentDescription = "Add image", onClick = {})
            MumcordIconButton(MumcordIcons.Gif, contentDescription = "Add GIF", onClick = {})
        },
        trailing = {
            MumcordIconButton(MumcordIcons.Send, "Send", onClick = {}, style = MumcordIconButtonStyle.Accent)
        },
    )
}
